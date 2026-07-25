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
    backendUrl: backendUrl.replace(/\/+$/, ''),
    token: token.trim(),
  }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(clean))
  return clean
}

export function clearConfig() {
  localStorage.removeItem(STORAGE_KEY)
}

function base() {
  const cfg = getConfig()
  if (!cfg) throw new Error('NOT_CONFIGURED')
  return cfg
}

async function fetchJson(url) {
  const res = await fetch(url)
  if (!res.ok) {
    throw new Error(`Request failed (${res.status})`)
  }
  return res.json()
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

// Groups manifest catalogs into rails, separating out any custom
// catalogs whose name looks like Anime / K-Drama so the home page
// can give them their own section instead of a generic "Custom" pile.
export function groupCatalogs(manifestCatalogs) {
  const rails = { movie: [], series: [], anime: [], kdrama: [], custom: [] }
  for (const cat of manifestCatalogs || []) {
    const name = (cat.name || '').toLowerCase()
    if (name.includes('anime')) rails.anime.push(cat)
    else if (name.includes('k-drama') || name.includes('kdrama') || name.includes('korean')) rails.kdrama.push(cat)
    else if (cat.id.startsWith('custom_')) rails.custom.push(cat)
    else if (cat.type === 'movie') rails.movie.push(cat)
    else if (cat.type === 'series') rails.series.push(cat)
  }
  return rails
}
