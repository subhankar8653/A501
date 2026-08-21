// Suhani Screen — app shell service worker.
//
// WHY THIS EXISTS: without a service worker, re-opening the app while
// offline is a bare browser request for the HTML/JS — the OS browser
// shows its own native "Webpage not available / ERR_INTERNET_DISCONNECTED"
// page (ref: user report, screenshot of a501.vercel.app). That happens
// BEFORE any of our React code ever runs, so the in-app "no internet" /
// "server crashed" screens (see src/lib/connectivity.js) never get a
// chance to render.
//
// Fix: cache the app shell (index.html + hashed JS/CSS/font/image assets)
// as they're fetched, so a later offline visit is served from cache
// instead of hitting the network — the SPA boots normally, and it's
// React (not the SW) that then decides whether to show the offline /
// server-crashed screen. Only the static "shell" is cached here — API
// calls to the Telegram backend are deliberately left untouched so the
// app always knows the true, live connectivity state.
//
// Bump this on every deploy that changes shell caching behaviour so old
// clients drop stale caches instead of piling them up forever.
const CACHE_VERSION = 'v1'
const SHELL_CACHE = `suhani-shell-${CACHE_VERSION}`

// Cheap same-origin check for "is this a static asset worth shell-caching".
function isStaticAsset(url) {
  return /\.(?:js|mjs|css|png|jpg|jpeg|svg|webp|gif|ico|woff2?|ttf)$/i.test(url.pathname)
}

self.addEventListener('install', (event) => {
  // Don't wait for old tabs to close before this SW becomes active —
  // we want the very first offline-capable version live ASAP.
  self.skipWaiting()
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) => cache.addAll(['/', '/index.html', '/favicon.png']).catch(() => {
      // Best-effort — some of these may 404 in dev; runtime caching below
      // still fills the cache in as the user actually navigates.
    }))
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL_CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  )
})

self.addEventListener('fetch', (event) => {
  const { request } = event
  if (request.method !== 'GET') return

  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return // never touch the backend / fonts CDN / etc.

  // Page navigations (app opened / reloaded / deep-linked): try the
  // network first so users online always get the latest shell, and only
  // fall back to the cached shell when the network is unreachable. Any
  // path works here — it's a single-page app, so the cached index.html
  // is the right response for every route once client-side JS boots.
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((res) => {
          caches.open(SHELL_CACHE).then((cache) => cache.put('/index.html', res.clone()))
          return res
        })
        .catch(() => caches.match('/index.html').then((cached) => cached || caches.match('/')))
    )
    return
  }

  // Hashed build assets (JS/CSS/images/fonts) are immutable per deploy,
  // so cache-first is safe and fast; fall back to network + cache-fill.
  if (isStaticAsset(url)) {
    event.respondWith(
      caches.match(request).then(
        (cached) =>
          cached ||
          fetch(request).then((res) => {
            if (res && res.ok) {
              caches.open(SHELL_CACHE).then((cache) => cache.put(request, res.clone()))
            }
            return res
          })
      )
    )
  }
})
