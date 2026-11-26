import test from 'node:test';
import assert from 'node:assert/strict';
import { resamplePcm16k } from './transcription.js';

test('downmixes stereo audio to mono at 16 kHz', () => {
  const result = resamplePcm16k([
    new Float32Array([1, 0]),
    new Float32Array([0, 1])
  ], 16000);
  assert.deepEqual(Array.from(result), [0.5, 0.5]);
});

test('resamples mono audio to the 16 kHz Whisper input rate', () => {
  const result = resamplePcm16k([new Float32Array([0, 1, 0, -1])], 8000);
  assert.equal(result.length, 8);
  assert.equal(result[0], 0);
  assert.equal(result[2], 1);
  assert.equal(result[4], 0);
  assert.equal(result[6], -1);
});

test('rejects invalid or empty audio buffers', () => {
  assert.throws(() => resamplePcm16k([], 16000), /Некорректный аудиопоток/);
  assert.throws(() => resamplePcm16k([new Float32Array(0)], 16000), /Аудиофайл пуст/);
});
