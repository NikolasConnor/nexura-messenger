import test from 'node:test';
import assert from 'node:assert/strict';
import { createRecoveryCode, encryptBackup, decryptBackup } from './backup.js';

test('browser E2EE backup round-trips with a recovery code', async () => {
  const userId = '3b4f7a3c-9c0a-4b1a-8f21-6f23d9f6c4aa';
  const recoveryCode = createRecoveryCode();
  assert.match(recoveryCode, /^[A-Za-z0-9_-]{43}$/);
  const payload = JSON.stringify({
    version: 1,
    records: [
      { recordId: 'public-device', value: { device_id: 'browser-device' } },
      { recordId: 'account', value: 'olm-account-pickle' },
      { recordId: 'session:chat-1', value: 'ratchet-pickle' }
    ]
  });
  const envelope = await encryptBackup(userId, payload, recoveryCode);
  assert.notEqual(envelope, payload);
  assert.equal(await decryptBackup(userId, envelope, recoveryCode), payload);
});

test('browser E2EE backup rejects a wrong recovery code', async () => {
  const userId = '3b4f7a3c-9c0a-4b1a-8f21-6f23d9f6c4aa';
  const envelope = await encryptBackup(userId, 'secret snapshot', createRecoveryCode());
  await assert.rejects(() => decryptBackup(userId, envelope, createRecoveryCode()), /Код восстановления неверен/);
});

test('browser E2EE backup is bound to the account id', async () => {
  const recoveryCode = createRecoveryCode();
  const envelope = await encryptBackup('user-a', 'secret snapshot', recoveryCode);
  await assert.rejects(() => decryptBackup('user-b', envelope, recoveryCode), /Код восстановления неверен/);
});
