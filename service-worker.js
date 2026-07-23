// Bump this version string any time you update the app's own files
// (the HTML/CSS/JS) — it forces the browser to fetch fresh copies
// instead of serving the old cached version forever.
const CACHE_NAME = 'kc-player-v3';

const APP_SHELL = [
  './index.html',
  './',
  './manifest.json'
];

// On install: download and cache every file the app needs to run,
// so it still works with no network on a later visit.
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL))
  );
  self.skipWaiting();
});

// On activate: delete any older cache versions left over from a
// previous deploy, so storage doesn't grow forever.
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((names) =>
      Promise.all(names.filter((n) => n !== CACHE_NAME).map((n) => caches.delete(n)))
    )
  );
  self.clients.claim();
});

// Cache-first for the app's own files: instant load, works offline.
// Anything not in the cache (e.g. a Google Fonts request) falls back
// to the network as normal, and simply won't be available offline.
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;

  event.respondWith(
    caches.match(event.request).then((cached) => {
      if (cached) return cached;
      return fetch(event.request).catch(() => cached);
    })
  );
});
