// High-level browser adapter. The caller must complete authenticated device
// verification and key-directory checks before using session operations.
import init, {
  create_device,
  generate_one_time_keys,
  acknowledge_published_keys,
  verify_one_time_key,
  start_outbound_session,
  accept_inbound_session,
  encrypt_message,
  decrypt_message
} from './pkg/nexura_crypto_wasm.js';
import {
  loadEncryptedRecord,
  saveEncryptedRecords,
  saveRatchetAndOutbox,
  loadPendingOutbox,
  removeOutboxEntry,
  clearLocalCryptoState
} from './secure-store.js';

let initialized;
export function initializeOlm() {
  initialized ||= init();
  return initialized;
}

function requireVerified(value) {
  if (value !== true) throw new Error('Сначала необходимо явно проверить отпечаток устройства собеседника');
}
function envelopeFields(raw, expected) {
  let envelope;
  try { envelope = JSON.parse(raw); } catch { throw new Error('Повреждённый E2EE-конверт'); }
  if (envelope.protocol !== 'nexura-olm-v1') throw new Error('Неподдерживаемая версия E2EE');
  for (const [key, value] of Object.entries(expected)) {
    if (envelope[key] !== value) throw new Error('E2EE-конверт не соответствует текущему чату или устройству');
  }
  return envelope;
}
function encodeEnvelope({ kind, conversationId, senderDeviceId, recipientDeviceId, senderCurve25519, ciphertext }) {
  return JSON.stringify({
    protocol: 'nexura-olm-v1',
    kind,
    conversation_id: conversationId,
    sender_device_id: senderDeviceId,
    recipient_device_id: recipientDeviceId,
    sender_curve25519: senderCurve25519,
    ciphertext
  });
}

export async function createBrowserDevice(userId) {
  await initializeOlm();
  if (await loadEncryptedRecord(userId, 'account')) throw new Error('На этом браузере уже есть E2EE-устройство');
  const created = create_device(50);
  const publicKeys = {
    ...created.public_keys,
    protocol: 'olm-v1',
    bundle_version: 1,
    device_id: crypto.randomUUID(),
    one_time_keys: created.public_keys.one_time_keys.map(key => ({ ...key, key_id: crypto.randomUUID() }))
  };
  await saveEncryptedRecords(userId, [
    ['account', created.account_pickle],
    ['public-device', publicKeys]
  ]);
  return publicKeys;
}

export async function replenishBrowserOneTimeKeys(userId, count = 30) {
  await initializeOlm();
  const account = await loadEncryptedRecord(userId, 'account');
  if (!account) throw new Error('E2EE-устройство не создано');
  const generated = generate_one_time_keys(account, count);
  const keys = generated.one_time_keys.map(key => ({ ...key, key_id: crypto.randomUUID() }));
  await saveEncryptedRecords(userId, [
    ['account', generated.account_pickle],
    ['pending-public-keys', keys]
  ]);
  return keys;
}

export async function acknowledgeBrowserOneTimeKeysPublished(userId) {
  await initializeOlm();
  const account = await loadEncryptedRecord(userId, 'account');
  if (!account) throw new Error('E2EE-устройство не создано');
  const updated = acknowledge_published_keys(account);
  await saveEncryptedRecords(userId, [['account', updated], ['pending-public-keys', []]]);
}

export async function verifyBrowserOneTimeKey(bundle) {
  await initializeOlm();
  return verify_one_time_key(bundle.ed25519, bundle.curve25519, bundle.oneTimeKey, bundle.signature);
}

export async function startVerifiedDirectSession({
  userId, conversationId, localDeviceId, localCurve25519,
  peerDeviceId, peerCurve25519, peerEd25519, peerOneTimeKey, peerOneTimeKeySignature,
  peerFingerprintVerified, messageId, plaintext
}) {
  requireVerified(peerFingerprintVerified);
  await initializeOlm();
  const valid = verify_one_time_key(peerEd25519, peerCurve25519, peerOneTimeKey, peerOneTimeKeySignature);
  if (!valid) throw new Error('Подпись одноразового ключа собеседника недействительна');
  const account = await loadEncryptedRecord(userId, 'account');
  if (!account) throw new Error('E2EE-устройство не создано');
  const started = start_outbound_session(account, peerCurve25519, peerOneTimeKey, new TextEncoder().encode(plaintext));
  const envelope = encodeEnvelope({
    kind: 'prekey', conversationId, senderDeviceId: localDeviceId,
    recipientDeviceId: peerDeviceId, senderCurve25519: localCurve25519,
    ciphertext: started.olm_envelope
  });
  await saveRatchetAndOutbox(userId, conversationId, started.session_pickle, messageId, envelope);
  return { messageId, envelope };
}

export async function acceptVerifiedDirectSession({
  userId, conversationId, localDeviceId, peerDeviceId, peerCurve25519,
  peerFingerprintVerified, envelope: rawEnvelope
}) {
  requireVerified(peerFingerprintVerified);
  await initializeOlm();
  const envelope = envelopeFields(rawEnvelope, {
    kind: 'prekey', conversation_id: conversationId,
    recipient_device_id: localDeviceId, sender_device_id: peerDeviceId,
    sender_curve25519: peerCurve25519
  });
  const account = await loadEncryptedRecord(userId, 'account');
  if (!account) throw new Error('E2EE-устройство не создано');
  const accepted = accept_inbound_session(account, peerCurve25519, envelope.ciphertext);
  const plaintext = new TextDecoder().decode(new Uint8Array(accepted.plaintext));
  await saveEncryptedRecords(userId, [
    ['account', accepted.account_pickle],
    ['session:' + conversationId, accepted.session_pickle],
    ['received:' + conversationId + ':' + crypto.randomUUID(), { plaintext, receivedAt: new Date().toISOString() }]
  ]);
  return plaintext;
}

export async function encryptDirectMessage({
  userId, conversationId, localDeviceId, localCurve25519,
  peerDeviceId, messageId, plaintext
}) {
  await initializeOlm();
  const session = await loadEncryptedRecord(userId, 'session:' + conversationId);
  if (!session) throw new Error('Защищённая сессия не установлена');
  const encrypted = encrypt_message(session, new TextEncoder().encode(plaintext));
  const envelope = encodeEnvelope({
    kind: 'normal', conversationId, senderDeviceId: localDeviceId,
    recipientDeviceId: peerDeviceId, senderCurve25519: localCurve25519,
    ciphertext: encrypted.olm_envelope
  });
  await saveRatchetAndOutbox(userId, conversationId, encrypted.session_pickle, messageId, envelope);
  return { messageId, envelope };
}

export async function decryptDirectMessage({
  userId, conversationId, localDeviceId, peerDeviceId, peerCurve25519,
  peerFingerprintVerified, envelope: rawEnvelope
}) {
  requireVerified(peerFingerprintVerified);
  await initializeOlm();
  const envelope = envelopeFields(rawEnvelope, {
    kind: 'normal', conversation_id: conversationId,
    recipient_device_id: localDeviceId, sender_device_id: peerDeviceId,
    sender_curve25519: peerCurve25519
  });
  const session = await loadEncryptedRecord(userId, 'session:' + conversationId);
  if (!session) throw new Error('Защищённая сессия не установлена');
  const decrypted = decrypt_message(session, envelope.ciphertext);
  const plaintext = new TextDecoder().decode(new Uint8Array(decrypted.plaintext));
  await saveEncryptedRecords(userId, [
    ['session:' + conversationId, decrypted.session_pickle],
    ['received:' + conversationId + ':' + crypto.randomUUID(), { plaintext, receivedAt: new Date().toISOString() }]
  ]);
  return plaintext;
}

export { loadPendingOutbox, removeOutboxEntry, clearLocalCryptoState };
