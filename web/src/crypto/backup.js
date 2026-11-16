// Recovery-code encrypted backup for Nexura browser E2EE state.
const FORMAT = 'nexura-web-backup-v1';
const PBKDF2_ITERATIONS = 310000;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

function toBase64(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}
function fromBase64(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, char => char.charCodeAt(0));
}
function normalizeRecoveryCode(value) {
  return String(value || '').replace(/[^A-Za-z0-9]/g, '').toUpperCase();
}
export function createRecoveryCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let bits = 0, buffer = 0, output = '';
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte;
    bits += 8;
    while (bits >= 5) { bits -= 5; output += alphabet[(buffer >>> bits) & 31]; }
  }
  if (bits > 0) output += alphabet[(buffer << (5 - bits)) & 31];
  return output.match(/.{1,4}/g).join('-');
}
async function deriveKey(userId, recoveryCode, salt) {
  const code = normalizeRecoveryCode(recoveryCode);
  if (code.length < 20) throw new Error('Неверный код восстановления');
  const material = await crypto.subtle.importKey('raw', encoder.encode(code), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations: PBKDF2_ITERATIONS, hash: 'SHA-256' },
    material, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']
  );
}
function aad(userId) { return encoder.encode(FORMAT + '|' + String(userId)); }

export async function encryptBackup(userId, snapshot, recoveryCode) {
  if (!userId || !Array.isArray(snapshot)) throw new Error('Некорректные данные резервной копии');
  if (!globalThis.crypto?.subtle) throw new Error('WebCrypto недоступен. Откройте Nexura по HTTPS.');
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(userId, recoveryCode, salt);
  const plaintext = encoder.encode(JSON.stringify(snapshot));
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: aad(userId), tagLength: 128 }, key, plaintext
  );
  return { format: FORMAT, kdf: 'PBKDF2-SHA256', iterations: PBKDF2_ITERATIONS,
    cipher: 'AES-256-GCM', salt: toBase64(salt), iv: toBase64(iv),
    ciphertext: toBase64(new Uint8Array(ciphertext)) };
}
export async function decryptBackup(userId, payload, recoveryCode) {
  if (!userId || !payload || typeof payload !== 'object') throw new Error('Резервная копия имеет неизвестный формат');
  if (payload.format !== FORMAT || payload.kdf !== 'PBKDF2-SHA256' ||
      payload.cipher !== 'AES-256-GCM' || payload.iterations !== PBKDF2_ITERATIONS) {
    throw new Error('Формат резервной копии не поддерживается');
  }
  try {
    const salt = fromBase64(payload.salt), iv = fromBase64(payload.iv), ciphertext = fromBase64(payload.ciphertext);
    if (salt.length !== 16 || iv.length !== 12 || ciphertext.length < 16) throw new Error('Повреждённая резервная копия');
    const key = await deriveKey(userId, recoveryCode, salt);
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv, additionalData: aad(userId), tagLength: 128 }, key, ciphertext
    );
    const snapshot = JSON.parse(decoder.decode(plaintext));
    if (!Array.isArray(snapshot)) throw new Error('Содержимое резервной копии повреждено');
    return snapshot;
  } catch (error) {
    if (error?.name === 'OperationError') throw new Error('Неверный код восстановления или резервная копия повреждена');
    throw error;
  }
}
