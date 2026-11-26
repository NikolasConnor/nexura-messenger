import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const authSource = readFileSync(new URL('./main.jsx', import.meta.url), 'utf8');

test('web authentication stays aligned with Android: email and password only', () => {
  assert.match(authSource, /supabase\.auth\.signUp\(/, 'email registration must remain available');
  assert.match(authSource, /supabase\.auth\.signInWithPassword\(/, 'email/password login must remain available');
  assert.doesNotMatch(authSource, /signInWithOAuth|signInWithOtp|verifyOtp/, 'social OAuth and phone OTP must not be added');
  assert.doesNotMatch(authSource, /Продолжить с Google|Войти по номеру телефона|Отправить SMS/, 'unsupported auth options must not appear in the UI');
});
