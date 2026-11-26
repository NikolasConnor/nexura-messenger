let pipelinePromise = null;
let activeTranscriber = null;

export function resamplePcm16k(channels, sourceRate, targetRate = 16000) {
  if (!Array.isArray(channels) || channels.length === 0 || !Number.isFinite(sourceRate) || sourceRate <= 0) {
    throw new Error('Некорректный аудиопоток');
  }
  const length = Math.max(...channels.map(channel => channel?.length || 0));
  if (!length) throw new Error('Аудиофайл пуст');
  const mono = new Float32Array(length);
  for (const channel of channels) {
    if (!channel) continue;
    for (let i = 0; i < Math.min(length, channel.length); i++) mono[i] += channel[i] / channels.length;
  }
  if (sourceRate === targetRate) return mono;
  const outputLength = Math.max(1, Math.round(length * targetRate / sourceRate));
  const output = new Float32Array(outputLength);
  const ratio = sourceRate / targetRate;
  for (let i = 0; i < outputLength; i++) {
    const position = i * ratio;
    const left = Math.min(length - 1, Math.floor(position));
    const right = Math.min(length - 1, left + 1);
    const fraction = position - left;
    output[i] = mono[left] * (1 - fraction) + mono[right] * fraction;
  }
  return output;
}

async function loadWhisper(onProgress) {
  if (activeTranscriber) return activeTranscriber;
  if (!pipelinePromise) {
    pipelinePromise = (async () => {
      const { pipeline, env } = await import('@huggingface/transformers');
      env.allowRemoteModels = true;
      env.allowLocalModels = false;
      env.useBrowserCache = true;
      const progress = info => {
        if (typeof onProgress === 'function') onProgress(info);
      };
      if (typeof navigator !== 'undefined' && navigator.gpu) {
        try {
          activeTranscriber = await pipeline(
            'automatic-speech-recognition',
            'onnx-community/whisper-base',
            { device: 'webgpu', dtype: 'fp16', progress_callback: progress }
          );
          return activeTranscriber;
        } catch (error) {
          progress({ status: 'fallback', message: 'WebGPU недоступен, переключаемся на WASM' });
        }
      }
      activeTranscriber = await pipeline(
        'automatic-speech-recognition',
        'onnx-community/whisper-base',
        { dtype: 'q8', progress_callback: progress }
      );
      return activeTranscriber;
    })().catch(error => {
      pipelinePromise = null;
      activeTranscriber = null;
      throw error;
    });
  }
  return pipelinePromise;
}

export async function transcribeVoiceBlob(blob, onProgress) {
  if (!(blob instanceof Blob) || blob.size <= 0) throw new Error('Голосовое сообщение недоступно');
  if (blob.size > 25 * 1024 * 1024) throw new Error('Голосовое сообщение превышает лимит 25 МБ');
  const AudioContextClass = globalThis.AudioContext || globalThis.webkitAudioContext;
  if (!AudioContextClass) throw new Error('Этот браузер не поддерживает локальную обработку аудио');
  const context = new AudioContextClass();
  let decoded;
  try {
    decoded = await context.decodeAudioData(await blob.arrayBuffer());
  } finally {
    await context.close().catch(() => {});
  }
  if (!decoded || decoded.duration <= 0 || decoded.duration > 15 * 60) {
    throw new Error('Длительность аудио должна быть не более 15 минут');
  }
  const channels = Array.from({ length: decoded.numberOfChannels }, (_, index) => decoded.getChannelData(index));
  const samples = resamplePcm16k(channels, decoded.sampleRate);
  const transcriber = await loadWhisper(onProgress);
  const result = await transcriber(samples, {
    sampling_rate: 16000,
    chunk_length_s: 30,
    stride_length_s: 5,
    return_timestamps: false
  });
  const text = typeof result?.text === 'string' ? result.text.trim() : '';
  if (!text) throw new Error('Не удалось распознать речь в этом сообщении');
  return text;
}
