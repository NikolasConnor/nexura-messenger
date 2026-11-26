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
  event.waitUntil(self.registration.showNotification(title, {
    body,
    icon: './icon.svg',
    badge: './icon.svg',
    tag: conversationId ? 'nexura-chat-' + conversationId : 'nexura-message',
    renotify: false,
    data: { conversationId, url: './' },
    timestamp: Date.now()
  }));
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const conversationId = event.notification.data?.conversationId || '';
  const targetUrl = new URL('./', self.location.href).href;
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of windows) {
      if ('focus' in client) {
        await client.focus();
        client.postMessage({ type: 'NEXURA_OPEN_CONVERSATION', conversationId });
        return;
      }
    }
    await self.clients.openWindow(targetUrl);
  })());
});
