import { sendBrowserDirectMessage } from '../crypto/transport.js';

const id = value => {
  const text = String(value || '').toLowerCase();
  if (!/^[0-9a-f-]{36}$/i.test(text)) throw new Error('Некорректный идентификатор');
  return text;
};

export async function loadConversationMessages(supabase, chatId, { before, limit = 30 } = {}) {
  let query = supabase.from('messages')
    .select('id,sender_id,content,created_at,message_type,reply_to_id,edited_at,deleted_at')
    .eq('conversation_id', id(chatId)).is('deleted_at', null)
    .order('created_at', { ascending: false }).order('id', { ascending: false })
    .limit(Math.max(1, Math.min(limit, 100)));
  if (before?.created_at && before?.id) query = query.or(
    'created_at.lt.' + before.created_at + ',and(created_at.eq.' + before.created_at + ',id.lt.' + id(before.id) + ')'
  );
  const { data, error } = await query;
  if (error) throw error;
  return (data || []).reverse();
}

export async function markConversationRead(supabase, chatId, share = true) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  const now = new Date().toISOString();
  const patch = { last_read_at: now };
  if (share) patch.last_receipt_at = now;
  const { error } = await supabase.from('conversation_members').update(patch)
    .eq('conversation_id', id(chatId)).eq('user_id', user.id);
  if (error) throw error;
  return now;
}

export async function getPeerReadReceipt(supabase, chatId, peerId) {
  if (!peerId) return null;
  const { data, error } = await supabase.from('conversation_members').select('last_receipt_at')
    .eq('conversation_id', id(chatId)).eq('user_id', id(peerId)).maybeSingle();
  if (error) throw error;
  return data?.last_receipt_at || null;
}

export async function loadMessageReactions(supabase, chatId) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  const { data, error } = await supabase.from('message_reactions')
    .select('message_id,user_id,emoji').eq('conversation_id', id(chatId));
  if (error) throw error;
  const grouped = {};
  for (const row of data || []) {
    const list = grouped[row.message_id] ||= {};
    const item = list[row.emoji] ||= { emoji: row.emoji, count: 0, selectedByMe: false };
    item.count++;
    if (row.user_id === user.id) item.selectedByMe = true;
  }
  return Object.fromEntries(Object.entries(grouped).map(([key, values]) => [key, Object.values(values)]));
}

export async function toggleMessageReaction(supabase, chatId, messageId, emoji) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  const filter = { message_id: id(messageId), user_id: user.id };
  const { data, error: readError } = await supabase.from('message_reactions').select('emoji').match(filter);
  if (readError) throw readError;
  const active = (data || []).some(row => row.emoji === emoji);
  if (data?.length) {
    const { error } = await supabase.from('message_reactions').delete().match(filter);
    if (error) throw error;
  }
  if (!active) {
    const { error } = await supabase.from('message_reactions').insert({
      ...filter, conversation_id: id(chatId), emoji
    });
    if (error) throw error;
  }
}

export async function canManageConversation(supabase, chatId) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) return false;
  const { data, error } = await supabase.from('conversation_members').select('member_role')
    .eq('conversation_id', id(chatId)).eq('user_id', user.id).maybeSingle();
  if (error) throw error;
  return ['owner', 'admin'].includes(data?.member_role);
}

export async function getPinnedMessage(supabase, chatId) {
  const { data, error } = await supabase.from('conversation_pins').select('message_id')
    .eq('conversation_id', id(chatId)).maybeSingle();
  if (error) throw error;
  return data?.message_id || null;
}

export async function setPinnedMessage(supabase, chatId, messageId) {
  const conversation_id = id(chatId);
  if (!messageId) {
    const { error } = await supabase.from('conversation_pins').delete().eq('conversation_id', conversation_id);
    if (error) throw error;
    return;
  }
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  const { error } = await supabase.from('conversation_pins').upsert({
    conversation_id, message_id: id(messageId), pinned_by: user.id
  }, { onConflict: 'conversation_id' });
  if (error) throw error;
}

export async function deleteMessage(supabase, messageId) {
  const { error } = await supabase.from('messages').update({ deleted_at: new Date().toISOString() }).eq('id', id(messageId));
  if (error) throw error;
}

export async function editEncryptedDirectMessage(supabase, { userId, chatId, peerUserId, oldMessage, newText }) {
  if (!newText.trim()) throw new Error('Сообщение не может быть пустым');
  const replacementId = crypto.randomUUID();
  const sent = await sendBrowserDirectMessage(supabase, {
    userId, conversationId: chatId, peerUserId, messageId: replacementId, plaintext: newText.trim()
  });
  const { error: updateError } = await supabase.from('messages')
    .update({ edited_at: new Date().toISOString(), created_at: oldMessage.created_at }).eq('id', replacementId);
  if (updateError) throw updateError;
  const { error: deleteError } = await supabase.from('messages')
    .update({ deleted_at: new Date().toISOString() }).eq('id', id(oldMessage.id));
  if (deleteError) throw deleteError;
  return sent;
}

export async function getMyProfile(supabase) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  const { data, error } = await supabase.from('profiles')
    .select('id,display_name,username,avatar_url,bio,age,read_receipts_enabled').eq('id', user.id).maybeSingle();
  if (error) throw error;
  return data;
}

export async function updateMyProfile(supabase, values) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  const age = values.age === '' || values.age == null ? null : Number(values.age);
  if (age != null && (!Number.isInteger(age) || age < 1 || age > 120)) throw new Error('Проверьте возраст');
  const payload = {
    display_name: String(values.display_name || '').trim().slice(0, 40),
    avatar_url: values.avatar_url || '🙂',
    bio: String(values.bio || '').slice(0, 160), age
  };
  if (!payload.display_name) throw new Error('Укажите имя');
  const { error } = await supabase.from('profiles').update(payload).eq('id', user.id);
  if (error) throw error;
}

export async function uploadProfileAvatar(supabase, file) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  if (!file || file.size > 8 * 1024 * 1024) throw new Error('Фото должно быть не больше 8 МБ');
  const ext = (file.name.split('.').pop() || 'jpg').replace(/[^a-z0-9]/gi, '').toLowerCase() || 'jpg';
  const path = user.id + '/' + crypto.randomUUID() + '.' + ext;
  const { error } = await supabase.storage.from('avatars').upload(path, file, { upsert: false, contentType: file.type || 'image/jpeg' });
  if (error) throw error;
  return supabase.storage.from('avatars').getPublicUrl(path).data.publicUrl;
}

export async function getPrivacySettings(supabase) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  const [profileResult, presenceResult] = await Promise.all([
    supabase.from('profiles').select('read_receipts_enabled').eq('id', user.id).maybeSingle(),
    supabase.from('user_presence').select('show_online_status').eq('user_id', user.id).maybeSingle()
  ]);
  if (profileResult.error) throw profileResult.error;
  if (presenceResult.error) throw presenceResult.error;
  return {
    readReceiptsEnabled: profileResult.data?.read_receipts_enabled ?? true,
    showOnlineStatus: presenceResult.data?.show_online_status ?? true
  };
}

export async function updatePrivacySettings(supabase, showOnlineStatus, readReceiptsEnabled) {
  const { error } = await supabase.rpc('update_my_privacy_settings', {
    p_show_online_status: !!showOnlineStatus, p_read_receipts_enabled: !!readReceiptsEnabled
  });
  if (error) throw error;
}

export async function getUserPresence(supabase, userId) {
  const { data, error } = await supabase.rpc('get_user_presence', { p_user_id: id(userId) });
  if (error) throw error;
  return (Array.isArray(data) ? data[0] : data) || { is_visible: false, is_online: false, last_seen_at: null };
}

export async function listBlockedUsers(supabase) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  const { data: blocks, error } = await supabase.from('user_blocks').select('blocked_id,created_at')
    .eq('blocker_id', user.id).order('created_at', { ascending: false });
  if (error) throw error;
  const ids = (blocks || []).map(row => row.blocked_id);
  if (!ids.length) return [];
  const { data, error: profileError } = await supabase.from('profiles')
    .select('id,display_name,username,avatar_url').in('id', ids);
  if (profileError) throw profileError;
  return ids.map(key => (data || []).find(row => row.id === key)).filter(Boolean);
}

export async function blockUser(supabase, userId) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  if (user.id === id(userId)) throw new Error('Нельзя заблокировать собственный аккаунт');
  const { error } = await supabase.from('user_blocks').upsert({
    blocker_id: user.id, blocked_id: id(userId)
  }, { onConflict: 'blocker_id,blocked_id', ignoreDuplicates: true });
  if (error) throw error;
}

export async function unblockUser(supabase, userId) {
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) throw new Error('Сессия истекла');
  const { error } = await supabase.from('user_blocks').delete()
    .eq('blocker_id', user.id).eq('blocked_id', id(userId));
  if (error) throw error;
}
