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
export const DEFAULT_LANGUAGE = 'en'

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

// ---------------------------------------------------------------------
// PERF FIX (user report: "All" tab par app open hote hi itna sara content
// load karta hai ki hang ho jata hai): loadTabByLanguage() pehle "All" tab
// ke liye EVERY catalog ka EVERY page (up to 40 pages/catalog, ~600
// items/catalog) ek saath fetch + render kar deta tha — "All" mein
// anime+movies+series+kdrama+shortdrama ke saare catalogs mile hue hote
// hain, to yeh easily hazaaro items ek saath fetch+DOM mein daal deta,
// jisse app open karte hi hang/freeze ho jata (especially "All" pe, jahan
// default tab hi "all" hai).
//
// Fix: ab sirf thodi si depth (INITIAL_PAGES_PER_CATALOG) fetch hoti hai
// pehle load pe — fast, halka open. Baaki content tab load hota hai jab
// user neeche scroll karega (infinite scroll — dekho Home.jsx ka
// IntersectionObserver sentinel), LOAD_MORE_PAGES_PER_CATALOG jitni depth
// har baar add karte hue. loadState ek catalog-by-catalog progress tracker
// hai (kitne pages ho chuke, kaunsa catalog khatam ho gaya) taaki "load
// more" sirf NAYE pages fetch kare, purane dobara nahi.
// ---------------------------------------------------------------------
export const INITIAL_PAGES_PER_CATALOG = 2 // ~30 items/catalog on first paint
export const LOAD_MORE_PAGES_PER_CATALOG = 2 // +~30 items/catalog per scroll-triggered load

async function fetchCatalogPageRange(cat, fromPage, toPage) {
  const pages = []
  for (let p = fromPage; p < toPage && p < MAX_CATALOG_PAGES; p++) pages.push(p)
  if (pages.length === 0) return { items: [], exhausted: true }

  const results = await Promise.all(
    pages.map((page) => {
      const skip = page * CATALOG_PAGE_SIZE
      return getCatalog(cat.type, cat.id, { skip: skip || undefined }).catch(() => null)
    })
  )

  const items = []
  let exhausted = toPage >= MAX_CATALOG_PAGES
  for (const metas of results) {
    if (!metas || metas.length === 0) {
      exhausted = true
      break
    }
    items.push(...metas)
    if (metas.length < CATALOG_PAGE_SIZE) {
      exhausted = true
      break // yehi is catalog ka aakhri page tha
    }
  }
  return { items, exhausted }
}

function groupItemsByLanguage(mergedMap) {
  const byLanguage = new Map()
  for (const item of mergedMap.values()) {
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

// Fresh progress-tracker for one tab's incremental load. Create one per
// "start loading this tab from scratch" (initial load, background
// refresh, or pull-to-refresh) and keep passing the SAME object into
// loadTabPage() as the user scrolls, so it knows what's already fetched.
export function createTabLoadState() {
  return { merged: new Map(), catalogState: new Map() }
}

// Fetches `pagesPerCatalog` MORE pages for every catalog in this tab
// (skipping catalogs already fully exhausted), merges + de-dupes into
// loadState, and returns the same {language, items} grouped shape
// loadTabByLanguage() used to return in one big shot — just built up
// gradually instead. `exhausted` tells the caller whether every catalog
// in this tab has now been fully fetched (no more "load more" needed).
export async function loadTabPage(catalogsForTab, loadState, pagesPerCatalog) {
  await Promise.all(
    (catalogsForTab || []).map(async (cat) => {
      const prev = loadState.catalogState.get(cat.id) || { pagesLoaded: 0, exhausted: false }
      if (prev.exhausted) return
      const toPage = prev.pagesLoaded + pagesPerCatalog
      try {
        const { items, exhausted } = await fetchCatalogPageRange(cat, prev.pagesLoaded, toPage)
        for (const item of items) {
          if (item && item.id && !loadState.merged.has(item.id)) loadState.merged.set(item.id, item)
        }
        loadState.catalogState.set(cat.id, { pagesLoaded: toPage, exhausted })
      } catch {
        // one catalog failing shouldn't block the rest — just mark it
        // exhausted so we stop retrying it every "load more"
        loadState.catalogState.set(cat.id, { ...prev, exhausted: true })
      }
    })
  )

  const exhausted = (catalogsForTab || []).every(
    (cat) => (loadState.catalogState.get(cat.id) || {}).exhausted
  )
  return { groups: groupItemsByLanguage(loadState.merged), exhausted }
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

// FEATURE (user ask: "language ko jyada ahmiyat do"): native player
// reads a file's *real* embedded audio tracks the first time someone
// plays it — see Player.jsx's audioTracks bridge. This ships that
// detection back to the backend so it's cached against the exact stream
// (see database.report_stream_languages), and every later viewer sees an
// accurate language list *before* pressing play instead of finding out
// only after the file loads. Fire-and-forget: never blocks playback, and
// duplicate/failed reports are harmless.
export async function reportStreamLanguages(type, id, streamId, languages, durationSec) {
  const { backendUrl, token } = base()
  try {
    await postJson(`${backendUrl}/stremio/${token}/stream-languages/${id}.json`, {
      stream_id: streamId,
      languages,
      duration_sec: durationSec,
    })
  } catch {
    // best-effort cache write — a failed report just means the picker
    // stays as accurate as it was before, nothing else depends on this
  }
}

async function postJson(url, body) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 20000)
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {}),
      signal: controller.signal,
    })
    if (!res.ok) throw new Error(`Request failed (${res.status})`)
    return await res.json()
  } finally {
    clearTimeout(timeout)
  }
}

async function patchJson(url, body) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 20000)
  try {
    const res = await fetch(url, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {}),
      signal: controller.signal,
    })
    if (!res.ok) throw new Error(`Request failed (${res.status})`)
    return await res.json()
  } finally {
    clearTimeout(timeout)
  }
}

// ---------------------------------------------------------------------
// FEATURE (user ask: "comments/likes/dislikes" — asli backend-stored,
// device-specific localStorage nahi): compact per-title storage backend
// mein (dekho stremio_routes.py + database.py). `id` yahan title/episode
// ka imdb-jaisa id hai — jo Detail/Player pages already use karte hain.
// ---------------------------------------------------------------------
export async function getReactions(type, id) {
  const { backendUrl, token } = base()
  return fetchJson(`${backendUrl}/stremio/${token}/reactions/${type}/${id}.json`)
}

export async function toggleReaction(type, id, kind) {
  const { backendUrl, token } = base()
  return postJson(`${backendUrl}/stremio/${token}/reactions/${type}/${id}.json`, { kind })
}

export async function getComments(type, id) {
  const { backendUrl, token } = base()
  const data = await fetchJson(`${backendUrl}/stremio/${token}/comments/${type}/${id}.json`)
  return data.comments || []
}

export async function postComment(type, id, text, title, poster) {
  const { backendUrl, token } = base()
  const profile = getProfile()
  const data = await postJson(`${backendUrl}/stremio/${token}/comments/${type}/${id}.json`, {
    text,
    name: profile?.name || 'Someone',
    title,
    poster,
  })
  return data.comment
}

// PROFILE FEATURE (user ask: "My Comments — edit/delete ka option")
export async function editComment(type, id, ts, text) {
  const { backendUrl, token } = base()
  return patchJson(`${backendUrl}/stremio/${token}/comments/${type}/${id}/${ts}`, { text })
}

export async function deleteComment(type, id, ts) {
  const { backendUrl, token } = base()
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 20000)
  try {
    await fetch(`${backendUrl}/stremio/${token}/comments/${type}/${id}/${ts}`, {
      method: 'DELETE',
      signal: controller.signal,
    })
  } finally {
    clearTimeout(timeout)
  }
}

// ---------------------------------------------------------------------
// FEATURE (user ask: "Watch history / Continue Watching"): resume-position
// backend mein save hota hai (per Telegram user, device-independent —
// phone se shuru kiya gaya video tablet pe bhi continue ho sakta hai).
// `episodeId` sirf series ke liye — us specific episode ka progress track
// karta hai; movies ke liye undefined chhod do.
// ---------------------------------------------------------------------
export async function saveWatchProgress({ type, id, position, duration, title, poster, episodeId }) {
  const { backendUrl, token } = base()
  return postJson(`${backendUrl}/stremio/${token}/progress.json`, {
    media_type: type,
    media_id: id,
    position,
    duration,
    title,
    poster,
    episode_id: episodeId || null,
  })
}

export async function getContinueWatching() {
  const { backendUrl, token } = base()
  const data = await fetchJson(`${backendUrl}/stremio/${token}/continue-watching.json`)
  return data.items || []
}

export async function removeWatchProgress(mediaId, episodeId) {
  const { backendUrl, token } = base()
  const qs = episodeId ? `?episode_id=${encodeURIComponent(episodeId)}` : ''
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 20000)
  try {
    await fetch(`${backendUrl}/stremio/${token}/progress/${encodeURIComponent(mediaId)}${qs}`, {
      method: 'DELETE',
      signal: controller.signal,
    })
  } finally {
    clearTimeout(timeout)
  }
}

// ---------------------------------------------------------------------
// FEATURE (user ask: "like/dislike/download/share/save ke alawa kuch add
// karo — Report ya Rating"): same shape as reactions above — GET fetches
// the current state (with `mine` reflecting this user), POST submits.
// ---------------------------------------------------------------------
export async function getRating(type, id) {
  const { backendUrl, token } = base()
  return fetchJson(`${backendUrl}/stremio/${token}/rating/${type}/${id}.json`)
}

export async function rateTitle(type, id, stars, title, poster) {
  const { backendUrl, token } = base()
  return postJson(`${backendUrl}/stremio/${token}/rating/${type}/${id}.json`, { stars, title, poster })
}

export async function getReportStatus(type, id) {
  const { backendUrl, token } = base()
  return fetchJson(`${backendUrl}/stremio/${token}/report/${type}/${id}.json`)
}

// `details` (title/poster/season/episode) rides along so the backend can
// put the video's details straight into the Telegram message it sends the
// owner — no extra metadata lookup needed on that side.
export async function submitReport(type, id, reason, note, details = {}) {
  const { backendUrl, token } = base()
  return postJson(`${backendUrl}/stremio/${token}/report/${type}/${id}.json`, { reason, note, ...details })
}

// ---------------------------------------------------------------------
// PROFILE FEATURE (user ask: "profile section professional dikhna
// chahiye — My Ratings, My Reports, My Comments, My Plan sab add karo"):
// four small "my stuff across every title" reads, one per Profile.jsx
// section. Each backed by a new db.get_user_* method — see
// stremio_routes.py's /my/* routes and database.py.
// ---------------------------------------------------------------------
export async function getMyRatings() {
  const { backendUrl, token } = base()
  const data = await fetchJson(`${backendUrl}/stremio/${token}/my/ratings.json`)
  return data.ratings || []
}

export async function getMyComments() {
  const { backendUrl, token } = base()
  const data = await fetchJson(`${backendUrl}/stremio/${token}/my/comments.json`)
  return data.comments || []
}

export async function getMyReports() {
  const { backendUrl, token } = base()
  const data = await fetchJson(`${backendUrl}/stremio/${token}/my/reports.json`)
  return data.reports || []
}

export async function getMySubscription() {
  const { backendUrl, token } = base()
  return fetchJson(`${backendUrl}/stremio/${token}/my/subscription.json`)
}

// FEATURE (user ask: "Related/Recommended videos"): koi naya backend
// endpoint nahi chahiye — catalog endpoint already `genre=` filter support
// karta hai (dekho stremio_routes.py get_catalog). Current title ke pehle
// genre se catalog maangte hain aur khud ko result se hata dete hain.
export async function getRelatedTitles(type, currentId, genre, limit = 12) {
  if (!genre) return []
  const catalogId = type === 'movie' ? 'top_movies' : 'top_series'
  const items = await getCatalog(type, catalogId, { genre })
  return items.filter((it) => it.id !== currentId).slice(0, limit)
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

// Home page content-type tabs, in display order.
// FEATURE (user ask: "All aur New to You catagory nahi hai, add karo — All
// mein sare mix hoga, New to You pe jo jo latest upload kiya hoga woh show
// hoga"): two extra tabs on top of the existing content-type ones. "All"
// mixes every catalog together (handled by groupCatalogsByTab below,
// rendered the normal grouped-by-language way). "New to You" needs its own
// loader — see loadNewToYou() — since it's sorted by upload recency, not
// grouped by language.
export const HOME_TABS = [
  { key: 'all', label: 'All' },
  { key: 'new', label: 'New to You' },
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
  // "All" = every catalog from every other tab, mixed together — same
  // list loadTabByLanguage() already merges+dedupes+groups-by-language for
  // any single tab, just fed everything at once instead of one type.
  const everything = [...tabs.anime, ...tabs.movie, ...tabs.kdrama, ...tabs.series, ...tabs.shortdrama]
  tabs.all = everything
  // "New to You" scans that same full set — loadNewToYou() (not
  // loadTabByLanguage) is what actually picks out the latest uploads.
  tabs.new = everything
  return tabs
}

// How many pages deep we scan per catalog for "New to You". Catalogs are
// already returned newest-first by the backend (sort_params default to
// updated_on desc — see stremio_routes.py), so a few pages per catalog is
// enough to find the true latest items across ALL catalogs mixed together
// without pulling in each catalog's entire back-catalog just to sort it.
const NEW_TO_YOU_PAGES_PER_CATALOG = 3
const NEW_TO_YOU_LIMIT = 60

// FEATURE (user ask: "New to You pe jo jo letest upload kiya hoga ohh show
// hoga"): merges the newest few pages of every catalog (movies, series,
// anime, k-drama, short drama — everything) into one pool, de-dupes, then
// sorts by each title's actual addedAt timestamp (see convert_to_stremio_meta
// in stremio_routes.py) so the freshest uploads across the WHOLE app show
// first — not just the freshest within one catalog.
export async function loadNewToYou(catalogsForTab) {
  const merged = new Map()
  await Promise.all(
    (catalogsForTab || []).map(async (cat) => {
      try {
        for (let page = 0; page < NEW_TO_YOU_PAGES_PER_CATALOG; page++) {
          const skip = page * CATALOG_PAGE_SIZE
          const metas = await getCatalog(cat.type, cat.id, { skip: skip || undefined })
          if (!metas.length) break
          for (const item of metas) {
            if (item && item.id && !merged.has(item.id)) merged.set(item.id, item)
          }
          if (metas.length < CATALOG_PAGE_SIZE) break // this catalog's already exhausted
        }
      } catch {
        // one catalog failing shouldn't blank out New to You
      }
    })
  )

  const items = [...merged.values()]
    .sort((a, b) => new Date(b.addedAt || 0) - new Date(a.addedAt || 0))
    .slice(0, NEW_TO_YOU_LIMIT)

  // Same {language, items} shape loadTabByLanguage() returns, so Home.jsx
  // can render it through the exact same LanguageRail path — just one
  // section instead of several.
  return items.length ? [{ language: 'New to You', items }] : []
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
