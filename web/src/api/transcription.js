import { pipeline, env } from '@huggingface/transformers';

// The model is downloaded from the public model repository once and cached by
// the browser. Audio is decoded and recognized locally; no audio is uploaded.
env.allowLocalModels = false;
env.allowRemoteModels = true;

const MODEL = 'onnx-community/whisper-tiny';
let recognizerPromise;

function getRecognizer(onProgress) {
  if (!recognizerPromise) {
    recognizerPromise = pipeline('automatic-speech-recognition', MODEL, {
      device: 'wasm',
      dtype: 'q8',
      progress_callback: (event) => {
        if (event?.status === 'progress' && Number.isFinite(event.progress)) {
          onProgress?.(Math.round(event.progress));
        } else if (event?.status === 'initiate') {
          onProgress?.(0);
        }
      }
    }).catch((error) => {
      recognizerPromise = undefined;
      throw error;
    });
  }
  return recognizerPromise;
}

async function decodeToMono16k(blob) {
  const AudioContextClass = globalThis.AudioContext || globalThis.webkitAudioContext;
  if (!AudioContextClass) throw new Error('Браузер не поддерживает обработку аудио.');
  const context = new AudioContextClass();
  try {
    const decoded = await context.decodeAudioData(await blob.arrayBuffer());
    const channels = Array.from({ length: decoded.numberOfChannels }, (_, i) => decoded.getChannelData(i));
    const mono = new Float32Array(decoded.length);
    for (let i = 0; i < mono.length; i++) {
      let sample = 0;
      for (const channel of channels) sample += channel[i] || 0;
      mono[i] = sample / channels.length;
    }
    const targetLength = Math.max(1, Math.round(mono.length * 16000 / decoded.sampleRate));
    const resampled = new Float32Array(targetLength);
    const ratio = decoded.sampleRate / 16000;
    for (let i = 0; i < targetLength; i++) {
      const position = i * ratio;
      const left = Math.min(Math.floor(position), mono.length - 1);
      const right = Math.min(left + 1, mono.length - 1);
      const fraction = position - left;
      resampled[i] = mono[left] * (1 - fraction) + mono[right] * fraction;
    }
    return resampled;
  } finally {
    await context.close().catch(() => {});
  }
}

export async function transcribeVoiceMessage(_supabase, blob, _filename = 'voice-message.webm', onProgress) {
  if (!(blob instanceof Blob) || blob.size === 0) throw new Error('Аудиофайл пуст.');
  const audio = await decodeToMono16k(blob);
  onProgress?.(-1);
  const recognizer = await getRecognizer(onProgress);
  onProgress?.(100);
  const result = await recognizer(audio, {
    sampling_rate: 16000,
    task: 'transcribe',
    chunk_length_s: 30,
    stride_length_s: 5
  });
  const text = String(result?.text || '').trim();
  if (!text) throw new Error('Речь не распознана.');
  return { text };
}
