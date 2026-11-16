const SOCIAL_BUCKET = 'nexura-social-media';
const AVATAR_BUCKET = 'nexura-avatars';

const fileExtension = file => {
  const ext = (file?.name?.split('.').pop() || '').toLowerCase().replace(/[^a-z0-9]/g, '');
  if (ext) return ext;
  return file?.type === 'video/mp4' ? 'mp4' : file?.type === 'image/png' ? 'png' : 'jpg';
};

export async function loadProfile(supabase, userId) {
  const { data, error } = await supabase.from('profiles')
    .select('id,display_name,username,avatar_url,bio,age,banner_url,read_receipts_enabled')
    .eq('id', userId).maybeSingle();
  if (error) throw error;
  if (data?.banner_url?.startsWith('social:')) {
    const { data: signed, error: signError } = await supabase.storage.from(SOCIAL_BUCKET)
      .createSignedUrl(data.banner_url.slice(7), 7 * 24 * 60 * 60);
    if (!signError) data.banner_display_url = signed.signedUrl;
  }
  return data;
}

export async function saveProfile(supabase, userId, values) {
  const age = values.age === '' || values.age == null ? null : Number(values.age);
  if (age != null && (!Number.isInteger(age) || age < 13 || age > 120)) throw new Error('Возраст должен быть от 13 до 120 лет');
  const username = String(values.username || '').trim().replace(/^@/, '').toLowerCase();
  if (!/^[a-z][a-z0-9_]{2,23}$/.test(username)) throw new Error('Username: 3–24 символа, латиница, цифры и _, первая буква — латинская');
  const payload = {
    display_name: String(values.display_name || '').trim().slice(0, 40),
    username,
    avatar_url: values.avatar_url || '🙂',
    bio: String(values.bio || '').trim().slice(0, 160),
    age
  };
  if (!payload.display_name) throw new Error('Укажите имя');
  const { error } = await supabase.from('profiles').update(payload).eq('id', userId);
  if (error) {
    if (String(error.code) === '23505') throw new Error('Этот username уже занят');
    throw error;
  }
  return payload;
}

export async function uploadProfileBanner(supabase, userId, file) {
  if (!file || file.size > 50 * 1024 * 1024) throw new Error('Обложка должна быть не больше 50 МБ');
  const isVideo = file.type.startsWith('video/');
  if (!isVideo && !file.type.startsWith('image/')) throw new Error('Выберите фото или видео');
  const bucket = isVideo ? SOCIAL_BUCKET : AVATAR_BUCKET;
  const folder = isVideo ? 'banners' : 'banner';
  const path = userId + '/' + folder + '-' + crypto.randomUUID() + '.' + fileExtension(file);
  const { error } = await supabase.storage.from(bucket).upload(path, file, { upsert: false, contentType: file.type });
  if (error) throw error;
  if (isVideo) return 'social:' + path;
  return supabase.storage.from(bucket).getPublicUrl(path).data.publicUrl;
}

export async function socialSignedUrl(supabase, path, ttl = 7 * 24 * 60 * 60) {
  const { data, error } = await supabase.storage.from(SOCIAL_BUCKET).createSignedUrl(path, ttl);
  if (error) throw error;
  return data.signedUrl;
}

export async function loadProfilePhotos(supabase, userId) {
  const { data, error } = await supabase.from('profile_photos')
    .select('id,user_id,storage_path,caption,created_at').eq('user_id', userId)
    .order('created_at', { ascending: false }).limit(100);
  if (error) throw error;
  return Promise.all((data || []).map(async row => ({ ...row, media_type: 'image', url: await socialSignedUrl(supabase, row.storage_path) })));
}

export async function loadStories(supabase, { userId = null, activeOnly = true } = {}) {
  let query = supabase.from('user_stories').select('id,user_id,storage_path,media_type,caption,created_at,expires_at');
  if (userId) query = query.eq('user_id', userId);
  if (activeOnly) query = query.gt('expires_at', new Date().toISOString());
  else if (userId) query = query.lte('expires_at', new Date().toISOString());
  const { data, error } = await query.order('created_at', { ascending: false }).limit(200);
  if (error) throw error;
  // A stale Storage object should not make the whole stories rail disappear.
  const resolved = await Promise.all((data || []).map(async row => {
    try {
      const url = await socialSignedUrl(supabase, row.storage_path, 24 * 60 * 60);
      return { ...row, url };
    } catch (mediaError) {
      console.warn('Nexura: skipping unavailable story media', row.id, mediaError?.message);
      return null;
    }
  }));
  return resolved.filter(Boolean);
}

export async function publishSocialMedia(supabase, userId, file, caption, kind) {
  if (!file || file.size > 50 * 1024 * 1024) throw new Error('Файл должен быть не больше 50 МБ');
  const isVideo = file.type.startsWith('video/');
  if (kind === 'story' && !isVideo && !file.type.startsWith('image/')) throw new Error('История должна быть фото или видео');
  if (kind === 'photo' && !file.type.startsWith('image/')) throw new Error('Для публикации выберите изображение');
  const folder = kind === 'story' ? 'stories' : 'photos';
  const path = userId + '/' + folder + '/' + crypto.randomUUID() + '.' + fileExtension(file);
  const { error: uploadError } = await supabase.storage.from(SOCIAL_BUCKET).upload(path, file, { upsert: false, contentType: file.type });
  if (uploadError) throw uploadError;
  const created = new Date();
  const row = kind === 'story'
    ? { user_id: userId, storage_path: path, media_type: isVideo ? 'video' : 'image', caption: String(caption || '').trim().slice(0, 500), created_at: created.toISOString(), expires_at: new Date(created.getTime() + 86400000).toISOString() }
    : { user_id: userId, storage_path: path, caption: String(caption || '').trim().slice(0, 500) };
  const table = kind === 'story' ? 'user_stories' : 'profile_photos';
  const { data, error } = await supabase.from(table).insert(row).select('id,user_id,storage_path,media_type,caption,created_at,expires_at').single();
  if (error) {
    await supabase.storage.from(SOCIAL_BUCKET).remove([path]).catch(() => {});
    throw error;
  }
  return { ...data, url: await socialSignedUrl(supabase, path, kind === 'story' ? 86400 : 7 * 86400) };
}

export async function deleteSocialMedia(supabase, item, kind) {
  const table = kind === 'story' ? 'user_stories' : 'profile_photos';
  const { error } = await supabase.from(table).delete().eq('id', item.id);
  if (error) throw error;
  const { error: storageError } = await supabase.storage.from(SOCIAL_BUCKET).remove([item.storage_path]);
  if (storageError) throw storageError;
}

export async function toggleSocialLike(supabase, userId, itemId, kind) {
  const table = kind === 'story' ? 'user_story_likes' : 'profile_photo_likes';
  const key = kind === 'story' ? 'story_id' : 'photo_id';
  const { data, error } = await supabase.from(table).select('id').eq(key, itemId).eq('user_id', userId).maybeSingle();
  if (error) throw error;
  if (data?.id) {
    const { error: removeError } = await supabase.from(table).delete().eq('id', data.id);
    if (removeError) throw removeError;
    return false;
  }
  const { error: addError } = await supabase.from(table).insert({ [key]: itemId, user_id: userId });
  if (addError) throw addError;
  return true;
}

export async function loadSocialComments(supabase, photoId) {
  const { data, error } = await supabase.from('profile_photo_comments')
    .select('id,user_id,content,created_at').eq('photo_id', photoId)
    .order('created_at', { ascending: true }).limit(200);
  if (error) throw error;
  return data || [];
}

export async function addSocialComment(supabase, photoId, userId, content) {
  const clean = String(content || '').trim();
  if (!clean || clean.length > 1000) throw new Error('Комментарий должен содержать от 1 до 1000 символов');
  const { data, error } = await supabase.from('profile_photo_comments')
    .insert({ photo_id: photoId, user_id: userId, content: clean })
    .select('id,user_id,content,created_at').single();
  if (error) throw error;
  return data;
}

export async function loadPeopleDirectory(supabase, userId, query = '', offset = 0, limit = 40) {
  const clean = String(query || '').trim();
  if (clean.length >= 2) {
    const safe = clean.replace(/[,%()]/g, ' ').trim();
    const [names, usernames] = await Promise.all([
      supabase.from('profiles').select('id,display_name,username,avatar_url,bio').neq('id', userId).ilike('display_name', '%' + safe + '%').order('display_name').range(offset, offset + limit - 1),
      supabase.from('profiles').select('id,display_name,username,avatar_url,bio').neq('id', userId).ilike('username', '%' + safe + '%').order('username').range(offset, offset + limit - 1)
    ]);
    if (names.error) throw names.error;
    if (usernames.error) throw usernames.error;
    return [...new Map([...(names.data || []), ...(usernames.data || [])].map(row => [row.id, row])).values()];
  }
  const { data, error } = await supabase.from('profiles')
    .select('id,display_name,username,avatar_url,bio').neq('id', userId)
    .order('display_name', { ascending: true }).range(offset, offset + limit - 1);
  if (error) throw error;
  return data || [];
}
