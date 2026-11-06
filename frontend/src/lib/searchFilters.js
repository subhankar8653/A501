// Search filters (YouTube-style "Search filters" dialog). Stored per device,
// applied client-side on the results list in pages/Search.jsx.
const KEY = 'huka:search-filters'
const EVENT = 'huka-search-filters-changed'

export const DEFAULT_FILTERS = { type: 'all', lang: '', year: '', sort: 'relevance', chips: [] }

export const TYPE_OPTIONS = [
  ['all', 'All'],
  ['movie', 'Movies'],
  ['series', 'Series'],
]
export const LANG_OPTIONS = [
  ['', 'Any'],
  ['hindi', 'Hindi'],
  ['english', 'English'],
  ['japanese', 'Japanese'],
  ['korean', 'Korean'],
  ['tamil', 'Tamil'],
  ['telugu', 'Telugu'],
]
export const YEAR_OPTIONS = [
  ['', 'Anytime'],
  ['recent', 'Last 2 years'],
  ['2020s', '2020s'],
  ['2010s', '2010s'],
  ['older', 'Before 2010'],
]
export const SORT_OPTIONS = [
  ['relevance', 'Relevance'],
  ['newest', 'Recently added'],
  ['rating', 'Rating'],
  ['year', 'Release year'],
]
export const CHIP_OPTIONS = [
  ['rated7', 'Rated 7+'],
  ['month', 'Added this month'],
  ['dual', 'Multi audio'],
  ['hasposter', 'With poster'],
]

export function getFilters() {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || 'null')
    return { ...DEFAULT_FILTERS, ...(raw || {}), chips: Array.isArray(raw?.chips) ? raw.chips : [] }
  } catch {
    return { ...DEFAULT_FILTERS }
  }
}

export function saveFilters(f) {
  try {
    localStorage.setItem(KEY, JSON.stringify(f))
  } catch {
    /* ignore */
  }
  window.dispatchEvent(new CustomEvent(EVENT))
}

export function onFiltersChanged(cb) {
  window.addEventListener(EVENT, cb)
  return () => window.removeEventListener(EVENT, cb)
}

export function filtersActive(f) {
  return f.type !== 'all' || !!f.lang || !!f.year || f.sort !== 'relevance' || f.chips.length > 0
}

const num = (v) => {
  const n = parseFloat(v)
  return Number.isFinite(n) ? n : 0
}

export function applyFilters(items, f) {
  const now = Date.now()
  const thisYear = new Date().getFullYear()
  let out = items.filter((it) => {
    if (f.type !== 'all' && it.type !== f.type) return false
    if (f.lang) {
      const langs = (it.languages || []).map((l) => String(l).toLowerCase())
      if (!langs.some((l) => l.includes(f.lang))) return false
    }
    const y = num(it.year || it.releaseInfo)
    if (f.year === 'recent' && !(y >= thisYear - 1)) return false
    if (f.year === '2020s' && !(y >= 2020 && y <= 2029)) return false
    if (f.year === '2010s' && !(y >= 2010 && y <= 2019)) return false
    if (f.year === 'older' && !(y > 0 && y < 2010)) return false
    if (f.chips.includes('rated7') && num(it.imdbRating) < 7) return false
    if (f.chips.includes('month') && !(now - new Date(it.addedAt || 0).getTime() < 30 * 86400000)) return false
    if (f.chips.includes('dual') && !((it.languages || []).length > 1)) return false
    if (f.chips.includes('hasposter') && !it.poster) return false
    return true
  })
  if (f.sort === 'newest') out = [...out].sort((a, b) => new Date(b.addedAt || 0) - new Date(a.addedAt || 0))
  else if (f.sort === 'rating') out = [...out].sort((a, b) => num(b.imdbRating) - num(a.imdbRating))
  else if (f.sort === 'year') out = [...out].sort((a, b) => num(b.year || b.releaseInfo) - num(a.year || a.releaseInfo))
  return out
}
