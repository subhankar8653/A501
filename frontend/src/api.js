// Thin client around the existing Telegram-Stremio FastAPI backend.
// The backend already speaks the Stremio addon protocol
// (manifest / catalog / meta / stream), so we just consume it as JSON.

const STORAGE_KEY = 'suhani-screen:config'

export function getConfig() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function saveConfig({ backendUrl, token }) {
  const clean = {
    backendUrl: backendUrl.trim().replace(/\/+$/, ''),
    token: token.trim(),
  }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(clean))
  return clean
}

export function clearConfig() {
  localStorage.removeItem(STORAGE_KEY)
}

// The one backend this build of the app talks to. Set this before building
// the app (or override at build time with VITE_BACKEND_URL in a .env file)
// so the in-app "Sign up with Telegram" button knows where to reach the
// bot/backend — the user is never asked to type a backend URL anymore.
export const DEFAULT_BACKEND_URL = (
  import.meta.env.VITE_BACKEND_URL || 'https://a501-production.up.railway.app'
).replace(/\/+$/, '')

// ---------------------------------------------------------------------
// Signed-in profile (name/username/photo pulled from Telegram during the
// "Sign up with Telegram" flow). Separate from `config` (backendUrl+token)
// so Profile.jsx has something nicer to show than "Guest".
// ---------------------------------------------------------------------
const PROFILE_KEY = 'huka-tube:profile'

export function getProfile() {
  try {
    const raw = localStorage.getItem(PROFILE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function saveProfile(profile) {
  localStorage.setItem(PROFILE_KEY, JSON.stringify(profile))
  return profile
}

export function clearProfile() {
  localStorage.removeItem(PROFILE_KEY)
}

// FEATURE (user ask: "app bina login ke khul jaaye, bas Home/Detail/Player
// jaise backend-wale hisse verify hone tak locked rahein — Saved/Downloads
// hamesha local hi dikhte rahein"): app ab kabhi force-signup par nahi
// bhejta (dekho App.jsx — RequireConfig hata diya gaya). "Verified" ka
// matlab bas itna hai ki humare paas ek valid backend config (token) hai —
// wahi cheez saare backend-wale (catalog/meta/stream) API calls ke liye
// zaroori hai. Har jagah jahan pehle sirf getConfig() check hota tha, ab
// yahi isVerified() istemal hoga taaki "verified" ka matlab poore app mein
// ek hi jagah define ho.
export function isVerified() {
  return !!getConfig()
}

// ---------------------------------------------------------------------
// App language preference (user ask: Profile page se app ki language
// badalne ka option — Hinglish/Hindi/English/Bangla/Urdu/Tamil/Telugu).
// Sirf ek localStorage preference hai; components isko lib/i18n ke
// useT() hook se padhte hain.
// ---------------------------------------------------------------------
const LANGUAGE_KEY = 'a501_language'
export const DEFAULT_LANGUAGE = 'hinglish'

export function getLanguage() {
  return localStorage.getItem(LANGUAGE_KEY) || DEFAULT_LANGUAGE
}

export function saveLanguage(code) {
  localStorage.setItem(LANGUAGE_KEY, code)
}

// ---------------------------------------------------------------------
// "Sign up with Telegram" flow — see backend routes under /api/app/*.
// ---------------------------------------------------------------------
export async function getBotUsername() {
  const data = await fetchJson(`${DEFAULT_BACKEND_URL}/api/app/bot-username`)
  if (data.status !== 'success') throw new Error(data.message || 'Bot not reachable')
  return data.data.username
}

export async function createSignupCode() {
  const res = await fetch(`${DEFAULT_BACKEND_URL}/api/app/signup/create`, { method: 'POST' })
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  const data = await res.json()
  if (data.status !== 'success') throw new Error(data.message || 'Could not start sign-up')
  return data.data.code
}

// Returns { state: 'pending' | 'verified' | 'expired' | 'invalid', ... }
export async function getSignupStatus(code) {
  const data = await fetchJson(`${DEFAULT_BACKEND_URL}/api/app/signup/${code}`)
  if (data.status !== 'success') throw new Error(data.message || 'Could not check sign-up status')
  return data.data
}

export function avatarUrl(userId) {
  if (!userId) return null
  return `${DEFAULT_BACKEND_URL}/api/app/avatar/${userId}`
}

// Search history for the full-screen search overlay (YouTube-style "recent
// searches" list shown before the user types anything).
const RECENT_SEARCHES_KEY = 'huka-tube:recent-searches'
const MAX_RECENT_SEARCHES = 12

export function getRecentSearches() {
  try {
    const raw = localStorage.getItem(RECENT_SEARCHES_KEY)
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

export function addRecentSearch(term) {
  const clean = (term || '').trim()
  if (!clean) return getRecentSearches()
  const existing = getRecentSearches().filter((t) => t.toLowerCase() !== clean.toLowerCase())
  const next = [clean, ...existing].slice(0, MAX_RECENT_SEARCHES)
  localStorage.setItem(RECENT_SEARCHES_KEY, JSON.stringify(next))
  return next
}

export function removeRecentSearch(term) {
  const next = getRecentSearches().filter((t) => t !== term)
  localStorage.setItem(RECENT_SEARCHES_KEY, JSON.stringify(next))
  return next
}

export function clearRecentSearches() {
  localStorage.removeItem(RECENT_SEARCHES_KEY)
}

function base() {
  const cfg = getConfig()
  if (!cfg) throw new Error('NOT_CONFIGURED')
  return cfg
}

async function fetchJson(url) {
  // Bug fix (user report: "Movies/Web Series load hi nahi ho raha, hamesha
  // Loading... hi dikhta rehta hai" jabki Anime/K-Drama theek chalte hain):
  // pehle fetch() ka koi timeout nahi tha — agar backend kisi specific
  // catalog (jisme normally kaafi zyada items hote hain, jaise Movies/Web
  // Series) par slow pad jaaye ya atak jaaye, promise hamesha ke liye pending
  // reh jaata, aur "Loading..." kabhi hatta hi nahi. Ab 20s ke baad request
  // khud abort ho jaati hai taaki UI kabhi hamesha ke liye atki na rahe.
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 20000)
  try {
    const res = await fetch(url, { signal: controller.signal })
    if (!res.ok) {
      throw new Error(`Request failed (${res.status})`)
    }
    return await res.json()
  } finally {
    clearTimeout(timeout)
  }
}

export async function getManifest() {
  const { backendUrl, token } = base()
  return fetchJson(`${backendUrl}/stremio/${token}/manifest.json`)
}

export async function getCatalog(type, id, extra = {}) {
  const { backendUrl, token } = base()
  const parts = Object.entries(extra)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
    .join('&')
  const suffix = parts ? `/${parts}.json` : '.json'
  const data = await fetchJson(`${backendUrl}/stremio/${token}/catalog/${type}/${id}${suffix}`)
  return data.metas || []
}

// Backend paginates every catalog (Stremio "skip" protocol, PAGE_SIZE=15 —
// dekho stremio_routes.py). Ek single getCatalog() call sirf pehla page
// (15 items) deta hai. Bug fix (user report: "Hindi mein bahut sare anime
// hain lekin sirf 3 dikh rahe"): loadTabByLanguage() pehle sirf yehi pehla
// page fetch karke language ke hisaab se split karta tha — poori catalog
// (jisme sainkdon items ho sakte hain) ka sirf pehla 15-item slice dekha
// jaata tha, isliye kisi bhi language jiske items us pehle page mein kam
// the (chahe poori catalog mein bahut zyada ho), woh khaali-jaisa dikhta.
// Fix: skip=0,15,30... karte hue tab tak page-by-page fetch karo jab tak
// ek chhota/khaali page na mile (ya safety cap lag jaaye) — taaki poori
// catalog cover ho, na ki sirf uska pehla hissa.
const CATALOG_PAGE_SIZE = 15
const MAX_CATALOG_PAGES = 40 // safety cap: ~600 items/catalog, kabhi infinite loop na bane
const PAGE_BATCH_SIZE = 5 // ek saath kitne pages parallel fetch karein

// Bug fix (user report): pages pehle ek-ek karke sequentially (await ke
// andar await) fetch hote the — Movies/Web Series jaise tabs mein Anime se
// kaafi zyada catalogs/items hote hain, to unke saare pages ek-ek karke
// fetch karne mein bahut zyada time lag jaata tha, aur user ko lagta "kabhi
// load hi nahi hoga". Ab pages chhote batches (5 parallel) mein fetch hote
// hain — kaafi tez, aur ek slow/hanging page se poori catalog nahi atakti
// (fetchJson ka apna 20s timeout bhi hai).
export async function getCatalogAllPages(type, id, extra = {}) {
  const all = []
  for (let batchStart = 0; batchStart < MAX_CATALOG_PAGES; batchStart += PAGE_BATCH_SIZE) {
    const batchPages = Array.from(
      { length: Math.min(PAGE_BATCH_SIZE, MAX_CATALOG_PAGES - batchStart) },
      (_, i) => batchStart + i
    )
    const results = await Promise.all(
      batchPages.map((page) => {
        const skip = page * CATALOG_PAGE_SIZE
        return getCatalog(type, id, { ...extra, skip: skip || undefined }).catch(() => null)
      })
    )

    let hitEnd = false
    for (const metas of results) {
      if (!metas || metas.length === 0) {
        hitEnd = true
        break
      }
      all.push(...metas)
      if (metas.length < CATALOG_PAGE_SIZE) {
        hitEnd = true
        break // yehi aakhri page tha
      }
    }
    if (hitEnd) break
  }
  return all
}

export async function getMeta(type, id) {
  const { backendUrl, token } = base()
  const data = await fetchJson(`${backendUrl}/stremio/${token}/meta/${type}/${id}.json`)
  return data.meta || null
}

export async function getStreams(type, id) {
  const { backendUrl, token } = base()
  const data = await fetchJson(`${backendUrl}/stremio/${token}/stream/${type}/${id}.json`)
  return data.streams || []
}

// Pulls a short "360p / 480p / 720p / 1080p" style label out of a stream's
// name or title so quality menus show something compact instead of the full
// filename. Shared by the player's quality menu and the Downloads
// season/episode quality-picker sheet.
export function qualityLabel(stream) {
  const hay = `${stream?.name || ''} ${stream?.title || ''}`
  const res = hay.match(/\b(2160p|4k|1440p|1080p|720p|480p|360p|240p)\b/i)
  if (res) return res[1].toLowerCase() === '4k' ? '4K' : res[1].toLowerCase()
  return (stream?.name || 'Auto').split('\n')[0].trim()
}

// Home page content-type tabs, in display order (Anime always first).
export const HOME_TABS = [
  { key: 'anime', label: 'Anime' },
  { key: 'movie', label: 'Movies' },
  { key: 'kdrama', label: 'K-Drama' },
  { key: 'series', label: 'Web Series' },
  { key: 'shortdrama', label: 'Short Drama' },
]

// Sorts every manifest catalog into one of the home-page tabs, by name
// first (Anime / K-Drama / Short Drama get their own tab regardless of
// type) and falls back to the Stremio type (movie/series) otherwise.
export function groupCatalogsByTab(manifestCatalogs) {
  const tabs = { anime: [], movie: [], kdrama: [], series: [], shortdrama: [] }
  for (const cat of manifestCatalogs || []) {
    const name = (cat.name || '').toLowerCase()
    if (name.includes('anime')) tabs.anime.push(cat)
    else if (name.includes('k-drama') || name.includes('kdrama')) tabs.kdrama.push(cat)
    else if (name.includes('short drama') || name.includes('short_drama') || name.includes('shortdrama')) {
      tabs.shortdrama.push(cat)
    } else if (cat.type === 'movie') tabs.movie.push(cat)
    else if (cat.type === 'series') tabs.series.push(cat)
  }
  return tabs
}

// Fetches every catalog for one tab, merges + de-dupes items by id, then
// groups them by language (an item can land in more than one language
// group if it's multi-audio). Groups are ordered by item count, so
// whichever language has the most content shows first.
export async function loadTabByLanguage(catalogsForTab) {
  const merged = new Map()
  await Promise.all(
    (catalogsForTab || []).map(async (cat) => {
      try {
        const metas = await getCatalogAllPages(cat.type, cat.id)
        for (const item of metas) {
          if (item && item.id && !merged.has(item.id)) merged.set(item.id, item)
        }
      } catch {
        // one catalog failing shouldn't blank out the whole tab
      }
    })
  )

  const byLanguage = new Map()
  for (const item of merged.values()) {
    const languages = item.languages && item.languages.length ? item.languages : ['Other']
    for (const lang of languages) {
      if (!byLanguage.has(lang)) byLanguage.set(lang, [])
      byLanguage.get(lang).push(item)
    }
  }

  return [...byLanguage.entries()]
    .sort((a, b) => b[1].length - a[1].length)
    .map(([language, items]) => ({ language, items }))
}

// ---------------------------------------------------------------------
// Home page cache — persisted to localStorage so switching tabs (Saved /
// Downloads / Profile) and back, or fully closing and reopening the app,
// shows the last-loaded home content INSTANTLY instead of a blank
// "Loading…" every single time. Home.jsx still does a silent background
// refresh on top of this (stale-while-revalidate) so new content added on
// the server eventually shows up on its own, and a manual pull-to-refresh
// forces an immediate one — either way the old cache entry is simply
// overwritten by the new one, never left stacking up.
// ---------------------------------------------------------------------
const HOME_CACHE_KEY = 'huka-tube:home-cache-v1'

export function loadHomeCache() {
  try {
    const raw = localStorage.getItem(HOME_CACHE_KEY)
    const parsed = raw ? JSON.parse(raw) : null
    if (!parsed || typeof parsed !== 'object') return null
    return parsed
  } catch {
    return null
  }
}

export function saveHomeCache(cache) {
  try {
    localStorage.setItem(HOME_CACHE_KEY, JSON.stringify(cache))
  } catch {
    // storage full/unavailable — cache is a nice-to-have, fine to skip
  }
}

export function clearHomeCache() {
  try {
    localStorage.removeItem(HOME_CACHE_KEY)
  } catch {
    // ignore
  }
}
