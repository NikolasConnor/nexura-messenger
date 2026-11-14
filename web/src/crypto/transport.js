import {
  initializeOlm, createBrowserDevice, replenishBrowserOneTimeKeys,
  acknowledgeBrowserOneTimeKeysPublished, verifyBrowserOneTimeKey,
  rotateBrowserDeviceIdentity
} from './olm.js';
import {
  loadEncryptedRecord, saveEncryptedRecords
} from './secure-store.js';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_TEXT_BYTES = 12000;
const PROTOCOL = 'nexura-olm-group-v1';
const decryptLocks = new Map();

function uuid(value, label) {
  const result = String(value || '').toLowerCase();
  if (!UUID.test(result)) throw new Error('Некорректный идентификатор: ' + label);
  return result;
}
function sessionId(chatId, userId, deviceId) {
  return 'session:' + chatId + ':' + userId + ':' + deviceId;
}
function trustId(userId, deviceId) {
  return 'trusted:' + userId + ':' + deviceId;
}
async function pinDevice(userId, device) {
  const id = trustId(userId, device.device_id);
  const identity = { curve25519: device.curve25519_key, ed25519: device.ed25519_key };
  const saved = await loadEncryptedRecord(userId, id);
  if (saved && (saved.curve25519 !== identity.curve25519 || saved.ed25519 !== identity.ed25519)) {
    throw new Error('Ключ устройства изменился. Сверьте отпечаток с собеседником; отправка остановлена.');
  }
  if (!saved) await saveEncryptedRecords(userId, [[id, identity]]);
}
async function publishKeys(supabase, userId, deviceId, keys) {
  if (!keys.length) return;
  const rows = keys.map(key => ({
    user_id: userId, device_id: deviceId, key_id: key.key_id,
    curve25519_key: key.key, signature: key.signature
  }));
  const { error } = await supabase.from('e2ee_one_time_keys')
    .upsert(rows, { onConflict: 'user_id,device_id,key_id', ignoreDuplicates: true });
  if (error) throw error;
  const ids = keys.map(key => key.key_id).join(',');
  const { data, error: readError } = await supabase.from('e2ee_one_time_keys')
    .select('key_id,curve25519_key,signature')
    .eq('user_id', userId).eq('device_id', deviceId).in('key_id', keys.map(k => k.key_id));
  if (readError) throw readError;
  const stored = new Map((data || []).map(row => [row.key_id, row]));
  for (const key of keys) {
    const row = stored.get(key.key_id);
    if (!row || row.curve25519_key !== key.key || row.signature !== key.signature) {
      throw new Error('Supabase не подтвердил сохранение одноразового ключа');
    }
  }
}
export async function ensureBrowserE2eeDevice(supabase, userId) {
  await initializeOlm();
  let bundle = await loadEncryptedRecord(userId, 'public-device');
  let account = await loadEncryptedRecord(userId, 'account');
  if (!bundle || !account) {
    bundle = await createBrowserDevice(userId);
    account = await loadEncryptedRecord(userId, 'account');
  }

  const register = async () => {
    const payload = {
      user_id: userId, device_id: bundle.device_id,
      curve25519_key: bundle.curve25519, ed25519_key: bundle.ed25519,
      protocol: 'olm-v1', bundle_version: 1, is_active: true
    };
    const { error } = await supabase.from('e2ee_devices')
      .upsert(payload, { onConflict: 'user_id,device_id', ignoreDuplicates: true });
    if (error) throw error;
    const { data, error: readError } = await supabase.from('e2ee_devices')
      .select('curve25519_key,ed25519_key,protocol,bundle_version,is_active')
      .eq('user_id', userId).eq('device_id', bundle.device_id).maybeSingle();
    if (readError) throw readError;
    return data;
  };

  let saved;
  try {
    saved = await register();
  } catch (error) {
    // A revoked identity is permanently tombstoned on the server. Never try to
    // resurrect it: create a new Olm account, new public keys and a new device UUID.
    if (!/revoked/i.test(error?.message || '')) throw error;
    bundle = await rotateBrowserDeviceIdentity(userId);
    account = await loadEncryptedRecord(userId, 'account');
    saved = await register();
  }
  if (saved && !saved.is_active) {
    bundle = await rotateBrowserDeviceIdentity(userId);
    account = await loadEncryptedRecord(userId, 'account');
    saved = await register();
  }
  if (!saved || saved.curve25519_key !== bundle.curve25519 || saved.ed25519_key !== bundle.ed25519 ||
      saved.protocol !== 'olm-v1' || saved.bundle_version !== 1 || !saved.is_active) {
    throw new Error('Ключи нового браузерного устройства не совпадают с регистрацией сервера');
  }

  let pending = await loadEncryptedRecord(userId, 'pending-public-keys');
  if (!pending?.length && bundle.one_time_keys?.length) pending = bundle.one_time_keys;
  if (!pending?.length) {
    await replenishBrowserOneTimeKeys(userId, 50);
    pending = await loadEncryptedRecord(userId, 'pending-public-keys');
  }
  await publishKeys(supabase, userId, bundle.device_id, pending || []);
  if (pending?.length) await acknowledgeBrowserOneTimeKeysPublished(userId);
  await saveEncryptedRecords(userId, [['public-device', { ...bundle, one_time_keys: [] }]]);
  const { count, error: countError } = await supabase.from('e2ee_one_time_keys')
    .select('key_id', { count: 'exact', head: true })
    .eq('user_id', userId).eq('device_id', bundle.device_id).is('consumed_at', null);
  if (countError) throw countError;
  if ((count || 0) < 25) {
    await replenishBrowserOneTimeKeys(userId, 50);
    const more = await loadEncryptedRecord(userId, 'pending-public-keys');
    await publishKeys(supabase, userId, bundle.device_id, more || []);
    if (more?.length) await acknowledgeBrowserOneTimeKeysPublished(userId);
  }
  const { count: finalCount, error: finalCountError } = await supabase.from('e2ee_one_time_keys')
    .select('key_id', { count: 'exact', head: true })
    .eq('user_id', userId).eq('device_id', bundle.device_id).is('consumed_at', null);
  if (finalCountError) throw finalCountError;
  return { deviceId: bundle.device_id, availableKeys: finalCount || 0 };
}

async function consumePreKey(supabase, chatId, localUserId, recipientUserId, device) {
  const ownDevice = recipientUserId === localUserId;
  const { data, error } = await supabase.rpc(
    ownDevice ? 'consume_own_e2ee_one_time_key' : 'consume_e2ee_one_time_key',
    ownDevice
      ? { p_conversation_id: chatId, p_device_id: device.device_id }
      : { p_conversation_id: chatId, p_recipient_user_id: recipientUserId, p_device_id: device.device_id }
  );
  if (error) throw error;
  const row = Array.isArray(data) ? data[0] : data;
  if (!row) {
    if (recipientUserId === localUserId) throw new Error('На одном из ваших устройств закончились ключи. Оно обновит запас при следующем подключении.');
    throw new Error('У собеседника закончились одноразовые ключи шифрования. Попросите его открыть Nexura и подождать обновления защиты, затем отправьте сообщение ещё раз.');
  }
  if (row.ed25519_key !== device.ed25519_key || row.curve25519_key !== device.curve25519_key) {
    throw new Error('Ключ устройства изменился. Отправка отменена.');
  }
  if (!(await verifyBrowserOneTimeKey({
    ed25519: device.ed25519_key, curve25519: device.curve25519_key,
    oneTimeKey: row.one_time_key, signature: row.one_time_key_signature
  }))) throw new Error('Подпись одноразового ключа устройства недействительна');
  return row;
}

export async function sendBrowserDirectMessage(supabase, {
  userId, conversationId, peerUserId, messageId, plaintext
}) {
  await initializeOlm();
  const uid = uuid(userId, 'user');
  const chatId = uuid(conversationId, 'conversation');
  const peerId = uuid(peerUserId, 'peer');
  const msgId = uuid(messageId, 'message');
  if (uid === peerId) throw new Error('Нельзя отправить сообщение самому себе');
  if (!plaintext?.trim()) throw new Error('Сообщение пустое');
  if (new TextEncoder().encode(plaintext).length > MAX_TEXT_BYTES) throw new Error('Сообщение слишком большое');

  const ownBundle = await loadEncryptedRecord(uid, 'public-device');
  const account = await loadEncryptedRecord(uid, 'account');
  if (!ownBundle || !account) throw new Error('Сначала зарегистрируйте защищённое устройство');
  const { data: ownDevices, error: ownError } = await supabase.from('e2ee_devices')
    .select('user_id,device_id,curve25519_key,ed25519_key,protocol,bundle_version')
    .eq('user_id', uid).eq('is_active', true);
  if (ownError) throw ownError;
  const { data: peerDevices, error: peerError } = await supabase.from('e2ee_devices')
    .select('user_id,device_id,curve25519_key,ed25519_key,protocol,bundle_version')
    .eq('user_id', peerId).eq('is_active', true);
  if (peerError) throw peerError;
  const own = (ownDevices || []).find(d => d.device_id === ownBundle.device_id);
  if (!own || own.curve25519_key !== ownBundle.curve25519 || own.ed25519_key !== ownBundle.ed25519) {
    throw new Error('Ваше E2EE-устройство не зарегистрировано или его ключи изменились');
  }
  if (!peerDevices?.length) throw new Error('У собеседника пока нет активного E2EE-устройства');
  const targets = [...peerDevices, ...(ownDevices || []).filter(d => d.device_id !== ownBundle.device_id)];
  if (targets.length > 64) throw new Error('Слишком много активных устройств для защищённой отправки');

  const recipients = [];
  const stateWrites = [];
  let skippedPeerDevicesWithoutKeys = 0;
  let skippedOwnDevicesWithoutKeys = 0;
  const bytes = new TextEncoder().encode(plaintext);
  for (const device of targets) {
    if (device.protocol !== 'olm-v1' || device.bundle_version !== 1) throw new Error('Версия E2EE-устройства не поддерживается');
    await pinDevice(uid, device);
    const key = sessionId(chatId, device.user_id, device.device_id);
    const session = await loadEncryptedRecord(uid, key);
    let kind, ciphertext, nextSession;
    if (session) {
      const { encrypt_message } = await import('./pkg/nexura_crypto_wasm.js');
      const result = encrypt_message(session, bytes);
      kind = 'normal'; ciphertext = result.olm_envelope; nextSession = result.session_pickle;
    } else {
      let preKey;
      try {
        preKey = await consumePreKey(supabase, chatId, uid, device.user_id, device);
      } catch (error) {
        // Accounts can have several historical active devices. One device with an
        // exhausted pre-key pool must not block delivery to another device that
        // still has a valid key. Never fall back to plaintext or reuse a key.
        if (/no available one-time key|одноразов.*ключ/i.test(error?.message || '')) {
          if (device.user_id === peerId) skippedPeerDevicesWithoutKeys++;
          else skippedOwnDevicesWithoutKeys++;
          continue;
        }
        throw error;
      }
      const { start_outbound_session } = await import('./pkg/nexura_crypto_wasm.js');
      const result = start_outbound_session(account, device.curve25519_key, preKey.one_time_key, bytes);
      kind = 'prekey'; ciphertext = result.olm_envelope; nextSession = result.session_pickle;
    }
    recipients.push({ user_id: device.user_id, device_id: device.device_id, kind, ciphertext });
    stateWrites.push([key, nextSession]);
  }
  if (!recipients.some(r => r.user_id === peerId)) {
    if (skippedPeerDevicesWithoutKeys > 0) {
      throw new Error('На устройствах собеседника закончились одноразовые ключи. Ни одно его устройство сейчас не может начать защищённую сессию. Собеседнику нужно открыть Nexura в браузере и дождаться обновления защиты.');
    }
    throw new Error('Не удалось подготовить устройство собеседника');
  }
  const envelope = JSON.stringify({
    protocol: PROTOCOL, conversation_id: chatId, sender_user_id: uid,
    sender_device_id: ownBundle.device_id, sender_curve25519: ownBundle.curve25519,
    recipients
  });
  // Ratchets and ciphertext outbox are committed in one IndexedDB transaction before network I/O.
  await saveEncryptedRecords(uid, [
    ...stateWrites,
    ['outbox:' + msgId, { conversationId: chatId, messageId: msgId, envelope, state: 'pending' }],
    ['sent:' + msgId, plaintext]
  ]);
  const payload = {
    id: msgId, conversation_id: chatId, sender_id: uid,
    message_type: 'text', content: envelope
  };
  const { data: inserted, error: insertError } = await supabase.from('messages')
    .upsert(payload, { onConflict: 'id', ignoreDuplicates: true }).select('id,sender_id,content');
  if (insertError) throw insertError;
  let saved = inserted?.[0];
  if (!saved) {
    const { data, error } = await supabase.from('messages').select('id,sender_id,content')
      .eq('id', msgId).eq('conversation_id', chatId).maybeSingle();
    if (error) throw error;
    saved = data;
  }
  if (!saved || saved.sender_id !== uid || saved.content !== envelope) {
    throw new Error('Сервер не подтвердил сохранение зашифрованного сообщения');
  }
  await saveEncryptedRecords(uid, [['outbox:' + msgId, { conversationId: chatId, messageId: msgId, envelope, state: 'sent' }]]);
  return { id: msgId, content: plaintext, created_at: new Date().toISOString(), sender_id: uid, message_type: 'text' };
}

export async function decryptBrowserGroupMessage(supabase, userId, chatId, message) {
  const lockKey = String(userId) + ':' + String(message.id);
  if (decryptLocks.has(lockKey)) return decryptLocks.get(lockKey);
  const task = decryptBrowserGroupMessageOnce(supabase, userId, chatId, message);
  decryptLocks.set(lockKey, task);
  try { return await task; } finally { decryptLocks.delete(lockKey); }
}

async function decryptBrowserGroupMessageOnce(supabase, userId, chatId, message) {
  const uid = uuid(userId, 'user');
  let envelope;
  try { envelope = JSON.parse(message.content); } catch { throw new Error('Повреждённый E2EE-конверт'); }
  if (envelope.protocol !== PROTOCOL || uuid(envelope.conversation_id, 'conversation') !== uuid(chatId, 'conversation') ||
      uuid(envelope.sender_user_id, 'sender') !== uuid(message.sender_id, 'message sender')) {
    throw new Error('E2EE-конверт не соответствует переписке');
  }
  const alreadyReceived = await loadEncryptedRecord(uid, 'received:' + message.id);
  if (typeof alreadyReceived === 'string') return alreadyReceived;
  const bundle = await loadEncryptedRecord(uid, 'public-device');
  if (message.sender_id === uid && envelope.sender_device_id === bundle?.device_id) {
    const own = await loadEncryptedRecord(uid, 'sent:' + message.id);
    if (typeof own !== 'string') throw new Error('Локальная копия отправленного сообщения недоступна');
    return own;
  }
  if (!bundle) throw new Error('Браузерное E2EE-устройство не зарегистрировано');
  const recipient = (envelope.recipients || []).find(r => r.user_id === uid && r.device_id === bundle.device_id);
  if (!recipient) throw new Error('Сообщение адресовано другому устройству');
  const { data: devices, error } = await supabase.from('e2ee_devices')
    .select('user_id,device_id,curve25519_key,ed25519_key,protocol,bundle_version')
    .eq('user_id', message.sender_id).eq('device_id', envelope.sender_device_id).eq('is_active', true).limit(1);
  if (error) throw error;
  const sender = devices?.[0];
  if (!sender || sender.curve25519_key !== envelope.sender_curve25519 ||
      sender.protocol !== 'olm-v1' || sender.bundle_version !== 1) {
    throw new Error('Устройство отправителя не зарегистрировано или его ключ изменился');
  }
  await pinDevice(uid, { ...sender, device_id: envelope.sender_device_id });
  const key = sessionId(chatId, message.sender_id, envelope.sender_device_id);
  const session = await loadEncryptedRecord(uid, key);
  if (recipient.kind === 'prekey') {
    if (session) throw new Error('Повторный или конфликтующий pre-key пакет');
    const { accept_inbound_session } = await import('./pkg/nexura_crypto_wasm.js');
    const account = await loadEncryptedRecord(uid, 'account');
    if (!account) throw new Error('Не найден локальный E2EE-аккаунт');
    const accepted = accept_inbound_session(account, sender.curve25519_key, recipient.ciphertext);
    const text = new TextDecoder().decode(new Uint8Array(accepted.plaintext));
    await saveEncryptedRecords(uid, [
      ['account', accepted.account_pickle], [key, accepted.session_pickle],
      ['received:' + message.id, text]
    ]);
    return text;
  }
  if (!session) throw new Error('Защищённая сессия ещё не установлена');
  const { decrypt_message } = await import('./pkg/nexura_crypto_wasm.js');
  const result = decrypt_message(session, recipient.ciphertext);
  const text = new TextDecoder().decode(new Uint8Array(result.plaintext));
  await saveEncryptedRecords(uid, [[key, result.session_pickle], ['received:' + message.id, text]]);
  return text;
}
