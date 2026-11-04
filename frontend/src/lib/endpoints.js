// Backend address book + automatic failover.
//
// Why this exists: the app must never be tied to one server. It talks to a
// DOMAIN (put behind Cloudflare Tunnel), never to an IP / railway.app /
// VPS hostname. When you move the backend to a new VPS, you just point the
// same domain/tunnel at it and nothing in the app changes. If you ever need
// a NEW domain, add it from the Telegram bot (/endpoints add https://...);
// every app learns it on its next update-check, and this file silently
// switches over when the current domain stops answering.
//
// Build-time (GitHub secret -> env):
//   VITE_BACKEND_URL        primary domain, e.g. https://api.example.com
//   VITE_BACKEND_FALLBACKS  optional, comma separated extra domains
// Runtime: extra domains pushed by the bot are stored in localStorage.

const CONFIG_KEY = 'suhani-screen:config' // same key api.js uses
const REMOTE_KEY = 'huka:endpoints'
const ACTIVE_KEY = 'huka:active-endpoint'

const clean = (u) => String(u || '').trim().replace(/\/+$/, '')
const isHttps = (u) => /^https:\/\/[a-z0-9.-]+(:\d+)?$/i.test(u)

const BUILD_PRIMARY = clean(import.meta.env.VITE_BACKEND_URL || '')
const BUILD_FALLBACKS = String(import.meta.env.VITE_BACKEND_FALLBACKS || '')
  .split(',')
  .map(clean)
  .filter(Boolean)

function readJson(key) {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function writeJson(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* storage unavailable — failover still works for this session */
  }
}

export function getEndpoints() {
  const remote = readJson(REMOTE_KEY)
  const all = [BUILD_PRIMARY, ...BUILD_FALLBACKS, ...(Array.isArray(remote) ? remote : [])]
  return [...new Set(all.map(clean).filter(isHttps))]
}

// Called with the list the server sent along with an update-check.
export function setRemoteEndpoints(list) {
  if (!Array.isArray(list)) return
  writeJson(REMOTE_KEY, list.map(clean).filter(isHttps))
}

export function activeBackend() {
  const eps = getEndpoints()
  const active = clean(readJson(ACTIVE_KEY))
  if (active && eps.includes(active)) return active
  const cfg = readJson(CONFIG_KEY)
  const saved = clean(cfg && cfg.backendUrl)
  if (saved && eps.includes(saved)) return saved
  return eps[0] || ''
}

function setActive(url) {
  writeJson(ACTIVE_KEY, url)
  const cfg = readJson(CONFIG_KEY)
  if (cfg && cfg.token) writeJson(CONFIG_KEY, { ...cfg, backendUrl: url })
}

// Old installs saved whatever backend URL they signed up against (e.g. the
// previous railway.app address). If that address is not in this build's
// list any more, move the saved login onto the current domain. The token
// itself keeps working because it lives in the database, not on the server.
export function rebaseStoredConfig() {
  const cfg = readJson(CONFIG_KEY)
  if (!cfg || !cfg.token) return
  const eps = getEndpoints()
  if (!eps.length) return
  if (!eps.includes(clean(cfg.backendUrl))) {
    writeJson(CONFIG_KEY, { ...cfg, backendUrl: activeBackend() })
  }
}

// Cloudflare answers 52x/530 when the tunnel/origin behind a domain is down.
const isOriginDown = (status) => status >= 520 && status <= 530

export function installFetchFailover() {
  if (window.__hukaFailoverInstalled) return
  window.__hukaFailoverInstalled = true
  const origFetch = window.fetch.bind(window)

  window.fetch = async (input, init) => {
    const url = typeof input === 'string' ? input : null
    const eps = getEndpoints()
    const cur = url ? eps.find((e) => url.startsWith(e + '/')) : null
    if (!cur || eps.length < 2) return origFetch(input, init)

    let firstError = null
    let firstResponse = null
    try {
      const res = await origFetch(input, init)
      if (!isOriginDown(res.status)) return res
      firstResponse = res
    } catch (err) {
      if (err && err.name === 'AbortError') throw err
      firstError = err
    }

    for (const alt of eps) {
      if (alt === cur) continue
      try {
        const res = await origFetch(alt + url.slice(cur.length), init)
        if (isOriginDown(res.status)) continue
        setActive(alt)
        return res
      } catch (err) {
        if (err && err.name === 'AbortError') throw err
      }
    }
    if (firstResponse) return firstResponse
    throw firstError
  }
}
