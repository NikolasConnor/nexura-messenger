const TRANSCRIPTION_URL = String(import.meta.env.VITE_NEXURA_TRANSCRIPTION_URL || '').replace(/\/+$/, '');

export async function transcribeVoiceMessage(supabase, blob, filename = 'voice-message.webm') {
  if (!TRANSCRIPTION_URL) {
    throw new Error('Локальный сервис распознавания ещё не подключён к веб-сборке.');
  }
  const { data, error } = await supabase.auth.getSession();
  if (error) throw error;
  const token = data?.session?.access_token;
  if (!token) throw new Error('Сессия Nexura истекла. Войди снова.');
  const form = new FormData();
  form.append('audio', blob, filename || 'voice-message.webm');
  const response = await fetch(TRANSCRIPTION_URL + '/v1/transcribe', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token },
    body: form
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.detail || 'Не удалось распознать голосовое сообщение');
  if (!result.text) throw new Error('Речь не распознана');
  return result;
}
