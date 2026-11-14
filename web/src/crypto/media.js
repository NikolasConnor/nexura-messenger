const MAX_MEDIA_BYTES = 24 * 1024 * 1024;
const ALLOWED_MIME = new Set([
  'audio/mp4','audio/aac','audio/mpeg','audio/ogg','audio/wav','audio/x-wav','audio/3gpp','audio/webm',
  'image/jpeg','image/png','image/webp','video/mp4','video/quicktime','video/webm',
  'application/pdf','text/plain','application/rtf','application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-excel','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.ms-powerpoint','application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'application/zip','application/json','text/csv'
]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const toBase64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)));
const fromBase64 = value => Uint8Array.from(atob(value), char => char.charCodeAt(0));
const sha256 = async bytes => [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(x=>x.toString(16).padStart(2,'0')).join('');

export async function encryptAndUploadMedia(supabase, { file, userId, conversationId, messageId }) {
  if (!file || !file.size) throw new Error('Файл пустой');
  if (file.size > MAX_MEDIA_BYTES) throw new Error('Максимальный размер файла — 24 МБ');
  const mime = (file.type || 'application/octet-stream').toLowerCase().split(';')[0];
  if (!ALLOWED_MIME.has(mime)) throw new Error('Этот формат пока не поддерживается. Можно отправлять аудио, PDF, Office, TXT, CSV и ZIP.');
  for (const value of [userId, conversationId, messageId]) if (!UUID.test(String(value))) throw new Error('Некорректный идентификатор медиа');
  const plain = await file.arrayBuffer();
  const key = crypto.getRandomValues(new Uint8Array(32));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const cryptoKey = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt']);
  const encrypted = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, cryptoKey, plain);
  const path = conversationId + '/' + userId + '/' + messageId + '.enc';
  const { error } = await supabase.storage.from('nexura-chat-media').upload(path, new Blob([encrypted], {type:'application/octet-stream'}), {
    contentType:'application/octet-stream', upsert:false
  });
  if (error) throw error;
  const mediaType = mime.startsWith('audio/') ? 'audio' : mime.startsWith('image/') ? 'image' : mime.startsWith('video/') ? 'video' : 'file';
  const safeName = String(file.name || (mediaType==='audio'?'voice-note':'attachment')).replaceAll('/', '_').replaceAll(String.fromCharCode(92), '_').slice(0,100);
  return {
    schema:'nexura-media-v1', path, mime, media_type:mediaType,
    name:safeName, size:file.size, key:toBase64(key), iv:toBase64(iv), cipher_sha256:await sha256(encrypted)
  };
}

export async function downloadAndDecryptMedia(supabase, payload) {
  const parts = String(payload?.path || '').split('/');
  if (!payload || payload.schema !== 'nexura-media-v1' || !UUID.test(parts[0] || '') ||
      !UUID.test(parts[1] || '') || !UUID.test((parts[2] || '').replace('.enc',''))) {
    throw new Error('Некорректное описание вложения');
  }
  if (!ALLOWED_MIME.has(payload.mime) || !['audio','image','video','file'].includes(payload.media_type)) throw new Error('Формат вложения не поддерживается');
  const { data, error } = await supabase.storage.from('nexura-chat-media').download(payload.path);
  if (error) throw error;
  const encrypted = await data.arrayBuffer();
  if (await sha256(encrypted) !== payload.cipher_sha256) throw new Error('Контрольная сумма файла не совпадает');
  const key = fromBase64(payload.key), iv = fromBase64(payload.iv);
  if (key.length !== 32 || iv.length !== 12 || payload.size < 1 || payload.size > MAX_MEDIA_BYTES) throw new Error('Некорректные параметры расшифровки');
  const cryptoKey = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['decrypt']);
  const plain = await crypto.subtle.decrypt({ name:'AES-GCM', iv }, cryptoKey, encrypted);
  return new Blob([plain], { type:payload.mime });
}
