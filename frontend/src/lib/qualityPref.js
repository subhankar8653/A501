import { qualityLabel } from '../api'

// Playback-quality preference (user ask: "720p dekh raha tha, agla episode khud 360p ho gaya").
//
//  mode 'auto'   -> use the quality the viewer LAST picked by hand (remembered across episodes
//                   and app restarts). Nothing picked yet -> lowest (data-friendly default).
//  mode 'lowest' -> always the lowest available quality.
//  mode 'fixed'  -> always `value` (e.g. '720p').
//
// Whatever the mode, if the wanted quality does not exist for this episode we fall back to the
// LOWEST available quality (that is the rule the user asked for).
const PREF_KEY = 'huka:quality-pref'
const LAST_KEY = 'huka:last-quality'

export function getQualityPref() {
  try {
    const p = JSON.parse(localStorage.getItem(PREF_KEY) || 'null')
    if (p && (p.mode === 'auto' || p.mode === 'lowest' || p.mode === 'fixed')) return p
  } catch { /* ignore */ }
  return { mode: 'auto' }
}

export function setQualityPref(pref) {
  try { localStorage.setItem(PREF_KEY, JSON.stringify(pref)) } catch { /* ignore */ }
}

export function getLastQuality() {
  try { return localStorage.getItem(LAST_KEY) || '' } catch { return '' }
}

// Call when the viewer picks a quality by hand (not for automatic language-driven switches).
export function rememberQuality(label) {
  if (!label) return
  try { localStorage.setItem(LAST_KEY, label) } catch { /* ignore */ }
}

const rank = (label) => {
  const m = String(label || '').match(/(\d+)/)
  if (m) return Number(m[1])
  return /4k/i.test(label || '') ? 2160 : Infinity
}

function lowest(list) {
  return [...list].sort((a, b) => rank(qualityLabel(a)) - rank(qualityLabel(b)))[0]
}

// Choose which stream to start with for the current preference.
export function pickStreamForPref(list) {
  if (!list || !list.length) return null
  const pref = getQualityPref()
  let want = ''
  if (pref.mode === 'fixed') want = pref.value || ''
  else if (pref.mode === 'auto') want = getLastQuality()
  if (want) {
    const hit = list.find((s) => qualityLabel(s).toLowerCase() === want.toLowerCase())
    if (hit) return hit
  }
  return lowest(list)
}
