// Browser-side encrypted persistence for Olm protocol state.
// CryptoKeys are non-exportable and structured-cloned by IndexedDB. Pickles never
// enter localStorage, cookies, Supabase, or the browser URL.
const DB_NAME = 'nexura-crypto-v1';
const DB_VERSION = 1;
const KEY_STORE = 'wrapping-keys';
const RECORD_STORE = 'encrypted-records';

function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(KEY_STORE)) db.createObjectStore(KEY_STORE, { keyPath: 'userId' });
      if (!db.objectStoreNames.contains(RECORD_STORE)) db.createObjectStore(RECORD_STORE, { keyPath: 'id' });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error('Не удалось открыть защищённое хранилище'));
    request.onblocked = () => reject(new Error('Обновление защищённого хранилища заблокировано другой вкладкой'));
  });
}

function requestValue(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error('Ошибка IndexedDB'));
  });
}

async function getWrappingKey(userId) {
  if (!globalThis.crypto?.subtle || !globalThis.indexedDB) {
    throw new Error('Для защищённого хранения нужен современный браузер и HTTPS');
  }
  const db = await openDatabase();
  try {
    const existing = await requestValue(db.transaction(KEY_STORE, 'readonly').objectStore(KEY_STORE).get(userId));
    if (existing?.key) return existing.key;

    const candidate = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    // The read/write transaction serializes competing tabs. Keep the first key
    // committed; never overwrite a key that may already protect stored records.
    const tx = db.transaction(KEY_STORE, 'readwrite');
    const store = tx.objectStore(KEY_STORE);
    const current = await requestValue(store.get(userId));
    if (current?.key) {
      await new Promise((resolve, reject) => {
        tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
      });
      return current.key;
    }
    store.put({ userId, key: candidate, createdAt: new Date().toISOString() });
    await new Promise((resolve, reject) => {
      tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
    });
    return candidate;
  } finally { db.close(); }
}

function aad(userId, recordId) {
  return new TextEncoder().encode('nexura-web-crypto-v1|' + userId + '|' + recordId);
}

async function encryptRecord(key, userId, recordId, value) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plaintext = new TextEncoder().encode(JSON.stringify(value));
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: aad(userId, recordId), tagLength: 128 }, key, plaintext
  );
  return { id: userId + ':' + recordId, userId, recordId, iv: Array.from(iv), ciphertext: Array.from(new Uint8Array(ciphertext)), updatedAt: new Date().toISOString() };
}

async function decryptRecord(key, userId, record) {
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: new Uint8Array(record.iv), additionalData: aad(userId, record.recordId), tagLength: 128 },
    key, new Uint8Array(record.ciphertext)
  );
  return JSON.parse(new TextDecoder().decode(plaintext));
}

export async function saveEncryptedRecords(userId, entries) {
  if (!userId || !Array.isArray(entries) || entries.length === 0) throw new Error('Некорректная запись защищённого состояния');
  const db = await openDatabase();
  try {
    const key = await getWrappingKey(userId);
    const rows = await Promise.all(entries.map(([recordId, value]) => encryptRecord(key, userId, recordId, value)));
    await new Promise((resolve, reject) => {
      const tx = db.transaction(RECORD_STORE, 'readwrite');
      for (const row of rows) tx.objectStore(RECORD_STORE).put(row);
      tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || new Error('Не удалось сохранить криптографическое состояние'));
    });
  } finally { db.close(); }
}

export async function loadEncryptedRecord(userId, recordId) {
  const db = await openDatabase();
  try {
    const row = await requestValue(db.transaction(RECORD_STORE, 'readonly').objectStore(RECORD_STORE).get(userId + ':' + recordId));
    if (!row) return null;
    const key = await getWrappingKey(userId);
    return await decryptRecord(key, userId, row);
  } catch (error) {
    throw new Error('Не удалось проверить локальное криптографическое состояние. Отправка заблокирована: ' + (error?.message || 'ошибка расшифрования'));
  } finally { db.close(); }
}

export async function saveRatchetAndOutbox(userId, conversationId, sessionPickle, messageId, envelope) {
  // The next ratchet state and its ciphertext outbox entry are committed together.
  // A failed transaction means the caller must not send the ciphertext.
  await saveEncryptedRecords(userId, [
    ['session:' + conversationId, sessionPickle],
    ['outbox:' + messageId, { conversationId, messageId, envelope, createdAt: new Date().toISOString(), state: 'pending' }]
  ]);
}

export async function loadPendingOutbox(userId) {
  const db = await openDatabase();
  try {
    const tx = db.transaction(RECORD_STORE, 'readonly');
    const rows = await requestValue(tx.objectStore(RECORD_STORE).getAll());
    const key = await getWrappingKey(userId);
    const result = [];
    for (const row of rows.filter(x => x.userId === userId && x.recordId.startsWith('outbox:'))) {
      result.push(await decryptRecord(key, userId, row));
    }
    return result.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  } finally { db.close(); }
}

export async function removeOutboxEntry(userId, messageId) {
  const db = await openDatabase();
  try {
    await new Promise((resolve, reject) => {
      const tx = db.transaction(RECORD_STORE, 'readwrite');
      tx.objectStore(RECORD_STORE).delete(userId + ':outbox:' + messageId);
      tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}

export async function clearLocalCryptoState(userId) {
  if (!userId) return;
  const db = await openDatabase();
  try {
    await new Promise((resolve, reject) => {
      const tx = db.transaction([KEY_STORE, RECORD_STORE], 'readwrite');
      tx.objectStore(KEY_STORE).delete(userId);
      const store = tx.objectStore(RECORD_STORE);
      const cursor = store.openCursor();
      cursor.onsuccess = () => {
        const item = cursor.result;
        if (!item) return;
        if (item.value.userId === userId) item.delete();
        item.continue();
      };
      tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}
