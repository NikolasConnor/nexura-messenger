const APP_PREFIX = 'nexura-app-v1';
const APP_SHELL = ['./', './manifest.webmanifest', './icon.svg'];

self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const cache = await caches.open(APP_PREFIX);
    await cache.addAll(APP_SHELL);
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter(key => key.startsWith('nexura-app-') && key !== APP_PREFIX).map(key => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', event => {
  const request = event.request;
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;
  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(async () => (await caches.match('./')) || Response.error()));
  }
});

self.addEventListener('push', event => {
  let payload = {};
  try { payload = event.data ? event.data.json() : {}; }
  catch { payload = { body: event.data?.text() || 'Новое сообщение' }; }
  const title = payload.title || payload.notification?.title || 'Nexura';
  const body = payload.body || payload.notification?.body || 'Новое сообщение';
  const conversationId = payload.conversation_id || payload.data?.conversation_id || '';
  const type = payload.type || payload.data?.type || 'chat_message';
  const callId = payload.call_id || payload.data?.call_id || '';
  const callerId = payload.caller_id || payload.data?.caller_id || '';
  const video = payload.video === true || payload.video === 'true' || payload.data?.video === 'true';
  const sentAt = Number(payload.sent_at || payload.data?.sent_at || Date.now());
  event.waitUntil(self.registration.showNotification(title, {
    body,
    icon: './icon.svg',
    badge: './icon.svg',
    tag: type === 'incoming_call' ? 'nexura-call-' + callId : conversationId ? 'nexura-chat-' + conversationId : 'nexura-message',
    renotify: type === 'incoming_call',
    requireInteraction: type === 'incoming_call',
    data: { type, conversationId, callId, callerId, video, sentAt, url: './' },
    timestamp: Date.now()
  }));
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const data = event.notification.data || {};
  const conversationId = data.conversationId || '';
  const callMessage = {
    type: 'NEXURA_INCOMING_CALL', conversationId, callId: data.callId,
    callerId: data.callerId, video: !!data.video, sentAt: data.sentAt
  };
  const target = new URL('./', self.location.href);
  if (data.type === 'incoming_call') {
    target.searchParams.set('nexura_call', data.callId || '');
    target.searchParams.set('nexura_caller', data.callerId || '');
    target.searchParams.set('nexura_conversation', conversationId);
    target.searchParams.set('nexura_video', data.video ? '1' : '0');
    target.searchParams.set('nexura_sent_at', String(data.sentAt || Date.now()));
  }
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of windows) {
      if ('focus' in client) {
        await client.focus();
        client.postMessage(data.type === 'incoming_call' ? callMessage : { type: 'NEXURA_OPEN_CONVERSATION', conversationId });
        return;
      }
    }
    await self.clients.openWindow(target.href);
  })());
});
