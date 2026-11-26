import test from 'node:test';
import assert from 'node:assert/strict';
import { transcribeVoiceMessage } from './transcription.js';

test('voice transcription rejects empty and non-Blob input', async () => {
  await assert.rejects(() => transcribeVoiceMessage(null, null), /Аудиофайл пуст/);
  await assert.rejects(() => transcribeVoiceMessage(null, new Blob([])), /Аудиофайл пуст/);
});

test('voice transcription reports unsupported browser audio decoding', async () => {
  const audio = new Blob([new Uint8Array([1, 2, 3])], { type: 'audio/webm' });
  await assert.rejects(() => transcribeVoiceMessage(null, audio), /Браузер не поддерживает обработку аудио/);
});
