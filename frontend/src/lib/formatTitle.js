// FEATURE (user ask: "video ka caption mein promotion (@HindiNewMovies)
// dikhna galat hai — clean 'Name Year [Language] Source [Quality]' format
// chahiye"): raw filenames aate hain seedha Telegram file-caption se, jisme
// uploader apna channel-handle bhi thoons deta hai (jaise ki koi paise leta
// ho isliye), aur format bhi inconsistent hota hai (dots/underscores/
// dashes sab mix). Yeh function usse ek clean, consistent title banata hai
// aur channel-handle jaisi cheezein poori tarah hata deta hai.
//
// Quality yahan filename se nahi liya jaata — active stream selection
// (jo already live update hoti hai jab user quality switch karta hai) use
// hoti hai, jo user ne bola tha: "jo quality abhi play ho rahi hai vahi
// dikhni chahiye".

const LANGUAGE_MAP = {
  hin: 'Hindi', hindi: 'Hindi',
  eng: 'English', english: 'English', en: 'English',
  tam: 'Tamil', tamil: 'Tamil',
  tel: 'Telugu', telugu: 'Telugu',
  kan: 'Kannada', kannada: 'Kannada',
  mal: 'Malayalam', malayalam: 'Malayalam',
  ben: 'Bengali', bengali: 'Bengali', bangla: 'Bengali', bng: 'Bengali',
  mar: 'Marathi', marathi: 'Marathi',
  guj: 'Gujarati', gujarati: 'Gujarati',
  pun: 'Punjabi', punjabi: 'Punjabi', pnb: 'Punjabi',
  urd: 'Urdu', urdu: 'Urdu',
  multi: 'Multi Audio', dual: 'Dual Audio',
}

const SOURCE_RE = /\b(BluRay|Blu-Ray|WEB-?DL|WEBRip|HDRip|BRRip|DVDRip|DVDScr|HDTV|HDCAM|CAMRip|HDTS|PreDVD|Pre-DVD)\b/i

export function formatDisplayTitle(rawFilename, activeQualityLabel) {
  if (!rawFilename) return ''

  // Strip extension, then strip any @handle (and Telegram sometimes glues a
  // trailing ".dot.separated.handle" onto it too) — this is the actual
  // promotion/channel-credit tag and must never show to viewers.
  let s = rawFilename.replace(/\.(mkv|mp4|avi|webm|mov|m4v|ts)$/i, '')
  s = s.replace(/@[\w.]+/g, ' ')

  const tokens = s.split(/[._\-\s]+/).filter(Boolean)

  let year = null
  let yearIdx = -1
  tokens.forEach((tok, i) => {
    if (year == null && /^(19|20)\d{2}$/.test(tok)) {
      year = tok
      yearIdx = i
    }
  })

  let language = null
  for (const tok of tokens) {
    const mapped = LANGUAGE_MAP[tok.toLowerCase()]
    if (mapped) {
      language = mapped
      break
    }
  }

  const sourceMatch = s.match(SOURCE_RE)
  const source = sourceMatch ? sourceMatch[0] : null

  const isNoiseToken = (tok) => {
    const low = tok.toLowerCase()
    return (
      !!LANGUAGE_MAP[low] ||
      /^(19|20)\d{2}$/.test(tok) ||
      /^\d{3,4}p$/i.test(tok) ||
      SOURCE_RE.test(tok)
    )
  }

  const nameTokens = yearIdx >= 0 ? tokens.slice(0, yearIdx) : tokens.filter((t) => !isNoiseToken(t))
  let name = nameTokens.join(' ').trim()
  if (!name) name = tokens.find((t) => !isNoiseToken(t)) || tokens[0] || rawFilename

  const parts = [name]
  if (year) parts.push(year)
  if (language) parts.push(`[${language}]`)
  if (source) parts.push(source)
  if (activeQualityLabel) parts.push(`[${activeQualityLabel}]`)
  return parts.join(' ')
}
