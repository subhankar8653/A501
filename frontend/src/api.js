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
  const res = await fetch(url, {
    headers: { 'Accept': 'application/json' }
  })
  if (!res.ok) {
    const err = await res.text().catch(() => '')
    throw new Error(`Request failed (${res.status}): ${err}`)
  }
  return res.json()
}

export async function getManifest() {
  const { backendUrl, token } = base()
  return fetchJson(`${backendUrl}/stremio/${token}/manifest.json`)
}

export async function getCatalog(type, id, extra = {}) {
  const { backendUrl, token } = base()
  const params = new URLSearchParams()
  Object.entries(extra).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') params.append(k, v)
  })
  const suffix = params.toString() ? `?${params.toString()}` : ''
  const data = await fetchJson(`${backendUrl}/stremio/${token}/catalog/${type}/${id}.json${suffix}`)
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

export const HOME_TABS = [
  { key: 'anime', label: 'Anime', icon: '✦' },
  { key: 'movie', label: 'Movies', icon: '🎬' },
  { key: 'kdrama', label: 'K-Drama', icon: '💫' },
  { key: 'series', label: 'Web Series', icon: '📺' },
  { key: 'shortdrama', label: 'Short Drama', icon: '⚡' },
]

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

export async function loadTabByLanguage(catalogsForTab) {
  const merged = new Map()
  await Promise.all(
    (catalogsForTab || []).map(async (cat) => {
      try {
        const metas = await getCatalog(cat.type, cat.id)
        for (const item of metas) {
          if (item && item.id && !merged.has(item.id)) merged.set(item.id, item)
        }
      } catch (err) {
        console.warn(`Catalog ${cat.id} failed:`, err.message)
      }
    })
  )

  const byLanguage = new Map()
  for (const item of merged.values()) {
    const languages = item.languages?.length ? item.languages : ['Other']
    for (const lang of languages) {
      if (!byLanguage.has(lang)) byLanguage.set(lang, [])
      byLanguage.get(lang).push(item)
    }
  }

  return [...byLanguage.entries()]
    .sort((a, b) => b[1].length - a[1].length)
    .map(([language, items]) => ({ language, items }))
}
