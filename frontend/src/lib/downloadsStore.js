import { useEffect, useState } from 'react'

// In-app Download Manager.
//
// Metadata (filename, poster, status, progress %) lives in localStorage so
// the Downloads tab can list everything instantly on load. The actual video
// bytes are far too big for localStorage, so completed downloads are kept
// as Blobs in IndexedDB instead — that's what makes "offline, inside the
// app" playback possible without re-hitting the network.
const META_KEY = 'suhani-screen:downloads-list'
const EVENT = 'suhani-downloads-changed'
const DB_NAME = 'huka-tube-downloads'
const STORE_NAME = 'files'

let dbPromise = null
function openDB() {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1)
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(STORE_NAME)) {
        req.result.createObjectStore(STORE_NAME)
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
  return dbPromise
}

async function idbPut(id, blob) {
  const db = await openDB()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite')
    tx.objectStore(STORE_NAME).put(blob, id)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
}

async function idbGet(id) {
  const db = await openDB()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readonly')
    const req = tx.objectStore(STORE_NAME).get(id)
    req.onsuccess = () => resolve(req.result || null)
    req.onerror = () => reject(req.error)
  })
}

async function idbDelete(id) {
  const db = await openDB()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite')
    tx.objectStore(STORE_NAME).delete(id)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
}

function readMeta() {
  try {
    const raw = localStorage.getItem(META_KEY)
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

function writeMeta(list) {
  try {
    localStorage.setItem(META_KEY, JSON.stringify(list))
  } catch {
    /* ignore quota errors */
  }
  window.dispatchEvent(new CustomEvent(EVENT))
}

function upsert(entry) {
  const list = readMeta()
  const idx = list.findIndex((d) => d.id === entry.id)
  if (idx >= 0) list[idx] = { ...list[idx], ...entry }
  else list.unshift(entry)
  writeMeta(list)
}

// A page reload mid-download kills the in-flight fetch — nothing left to
// resume from. Anything still marked "downloading" from a previous session
// is stale, so flip it to "error" (with a Retry affordance) instead of
// showing a progress bar that will never move again.
export function cleanupStaleDownloads() {
  const list = readMeta()
  let changed = false
  for (const d of list) {
    if (d.status === 'downloading') {
      d.status = 'error'
      changed = true
    }
  }
  if (changed) writeMeta(list)
  // Ab koi active download nahi hai (upar clear kar diya) — agar queue mein
  // kuch pending hai to usse shuru karo, warna woh hamesha ke liye ruka reh
  // jaata.
  processQueue()
}

// ROOT CAUSE FIX (user ask: "offline video player simple hai, online jaisa
// poora-feature player chahiye"): jab app ke andar (native shell) chal rahe hain,
// download ab yahan JS mein fetch+IndexedDB Blob ki jagah native side
// (window.AndroidDownloader — asli file, disk par) se hota hai. Isse playback
// ke waqt ek real `content://` URI milta hai jo `window.AndroidPlayer` (rich
// native player — equalizer/cast/decoder-select/PiP) seedha mount kar sakta
// hai, bilkul online stream jaisa hi — `blob:` URL (jo native player kabhi
// mount nahi kar sakta) is path mein aata hi nahi. Plain browser (bina app ke)
// mein yeh bridge exist nahi karta, isliye wahan neeche wala fetch/IndexedDB
// path hi chalta hai — koi behavior change nahi.
function hasNativeDownloader() {
  return !!(window.AndroidDownloader && typeof window.AndroidDownloader.startDownload === 'function')
}

if (typeof window !== 'undefined') {
  // Native side (WebDownloadInterface/NativeDownloadManager) inhi teenon global
  // callbacks ke through progress/completion/error wapas bhejta hai.
  //
  // ROOT CAUSE FIX (user report: "download beech mein Cancel karo to bhi thodi
  // der baad wapas list mein 'X MB · Offline available' + dustbin ke saath
  // dikhne lagta hai, seedha delete hona chahiye"): cancelDownload() JS side
  // par turant meta list se entry hata deta hai, lekin native background
  // Thread ek hi waqt mein already ek chunk padh chuka ho sakta hai aur uska
  // onProgress callback mainHandler par POST ho chuka ho sakta hai — yeh
  // callback cancel ke THODI DER BAAD bhi JS tak pahunch sakta hai. Us waqt
  // `upsert()` ko entry na milne par (kyunki abhi-abhi cancel karke hataya
  // tha) woh use `status` field ke bina hi list mein wapas jod deta tha — aur
  // status na hone par UI usse "done" maan kar "Offline available" +
  // dustbin dikhati thi. Fix: agar entry pehle se list mein nahi hai (yaani
  // cancel/delete ho chuka hai), koi bhi late progress/done/error callback
  // use wapas resurrect nahi karega — bas ignore ho jaayega.
  window.__nativeDownloadProgress = (id, progressPct, sizeBytes) => {
    if (!getDownloadEntry(id)) return
    upsert({ id, progress: progressPct, sizeBytes })
  }
  window.__nativeDownloadDone = (id, contentUri) => {
    if (!getDownloadEntry(id)) {
      // Cancel ke baad bhi native side download poora kar chuka — us adhoori
      // (ab-anaathi) file ko disk se bhi hata do, list mein kabhi aayegi hi nahi.
      if (hasNativeDownloader()) window.AndroidDownloader.deleteDownload(id)
      processQueue()
      return
    }
    upsert({ id, status: 'done', progress: 100, nativeUri: contentUri })
    processQueue()
  }
  window.__nativeDownloadError = (id) => {
    if (!getDownloadEntry(id)) {
      processQueue()
      return
    }
    upsert({ id, status: 'error', progress: 0 })
    processQueue()
  }
}

const activeControllers = new Map() // id -> AbortController

// ROOT CAUSE FIX (user report: "ek saath bahut sare download deta hun to sab
// ek saath shuru ho jaate hain, load badh jaata hai — ek-ek karke hona
// chahiye"): pehle `startDownload()` har call par turant download shuru kar
// deta tha (native ho ya JS-fetch), chahe kitne bhi episodes ek saath queue
// ho rahe hon — sab ek hi waqt mein parallel chalte the. Fix: ab sirf EK
// download kabhi bhi 'downloading' status mein rehta hai; baaki sab 'queued'
// status mein list mein dikhte hain aur QUEUE_KEY ke andar pending order mein
// rakhe jaate hain. Jaise hi active wala done/error/cancel hota hai,
// processQueue() apne aap agla queued wala shuru kar deta hai — ek complete,
// phir dusra.
const QUEUE_KEY = 'suhani-screen:download-queue'

function readQueue() {
  try {
    const raw = localStorage.getItem(QUEUE_KEY)
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

function writeQueue(list) {
  try {
    localStorage.setItem(QUEUE_KEY, JSON.stringify(list))
  } catch {
    /* ignore quota errors */
  }
}

function isAnyDownloadActive() {
  return readMeta().some((d) => d.status === 'downloading')
}

// Jab bhi ek download khatam (done/error/cancelled) hota hai, yeh queue mein
// se agla item nikaal kar shuru karta hai — agar koi aur pehle se active na ho.
function processQueue() {
  if (isAnyDownloadActive()) return
  const queue = readQueue()
  const next = queue.shift()
  if (!next) return
  writeQueue(queue)
  upsert({ id: next.id, status: 'downloading' })
  runDownload(next.id, next.url, next.meta)
}

export function downloadId(type, titleId, qualityLabel) {
  return `${type}:${titleId}:${qualityLabel || 'default'}`
}

export function getDownloadEntry(id) {
  return readMeta().find((d) => d.id === id) || null
}

// Actually kicks a download off (native bridge or JS fetch fallback) —
// assumes it's already safe to run (no other active download right now) and
// the meta entry already exists with status 'downloading'.
async function runDownload(id, url, meta) {
  if (hasNativeDownloader()) {
    // Fire-and-forget: native side downloads to a real file and reports back
    // via window.__nativeDownload{Progress,Done,Error} (registered above),
    // which is also where the next queued download gets kicked off.
    // BUG FIX (user report: "background jaate hi download cancel ho jaata
    // hai, notification bar mein progress nahi dikhta"): native side ab
    // download ko ek foreground service (DownloadService.kt) ke andar chalata
    // hai, jisse ek ongoing notification bhi dikhti hai — usme title dikhane
    // ke liye episode/movie ka naam bhi bhej rahe hain.
    window.AndroidDownloader.startDownload(id, url, meta.filename || meta.episodeTitle || meta.showName || 'Download')
    return id
  }

  const controller = new AbortController()
  activeControllers.set(id, controller)

  try {
    const res = await fetch(url, { signal: controller.signal })
    if (!res.ok || !res.body) throw new Error('bad response')
    const total = Number(res.headers.get('content-length')) || 0
    const reader = res.body.getReader()
    const chunks = []
    let received = 0
    let lastWrite = 0
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      chunks.push(value)
      received += value.length
      const now = Date.now()
      if (now - lastWrite > 300) {
        lastWrite = now
        upsert({ id, progress: total ? Math.round((received / total) * 100) : 0, sizeBytes: received })
      }
    }
    // ROOT CAUSE FIX (user report: "beech mein cut kiya to bhi 'Offline
    // available' dikha raha tha"): pehle stream khatam hote hi (reader.read()
    // ka done=true) seedha "done" maan liya jaata tha, chahe Content-Length se
    // kam bytes hi kyun na mile hon (connection drop bhi isi tarah dikhta
    // hai). Ab agar expected size pata thi aur utni mili hi nahi, isko error
    // treat karo — adhoori entry list mein "Offline available" ban kar kabhi
    // reh hi nahi jaayegi.
    if (total > 0 && received < total) {
      throw new Error('incomplete download')
    }
    const blob = new Blob(chunks)
    await idbPut(id, blob)
    upsert({ id, status: 'done', progress: 100, sizeBytes: blob.size })
  } catch (err) {
    if (err?.name === 'AbortError') {
      // user-cancelled — entry already removed by cancelDownload()
    } else {
      upsert({ id, status: 'error', progress: 0 })
    }
  } finally {
    activeControllers.delete(id)
    processQueue()
  }
  return id
}

// meta: { type, titleId, filename, poster, qualityLabel }
export async function startDownload(url, meta) {
  const id = downloadId(meta.type, meta.titleId, meta.qualityLabel)
  const existing = getDownloadEntry(id)
  if (existing && (existing.status === 'downloading' || existing.status === 'done' || existing.status === 'queued')) {
    return id
  }

  const shouldQueue = isAnyDownloadActive()

  upsert({
    id,
    type: meta.type,
    titleId: meta.titleId,
    // Season-level grouping metadata (Downloads tab ke season-cover view ke
    // liye) — series ke liye set, movies ke liye undefined/null rehta hai.
    showId: meta.showId || null,
    showName: meta.showName || '',
    showPoster: meta.showPoster || meta.poster || null,
    season: meta.season ?? null,
    episode: meta.episode ?? null,
    episodeTitle: meta.episodeTitle || '',
    filename: meta.filename || 'download',
    poster: meta.poster || null,
    qualityLabel: meta.qualityLabel || '',
    status: shouldQueue ? 'queued' : 'downloading',
    progress: 0,
    sizeBytes: 0,
    addedAt: Date.now(),
  })

  if (shouldQueue) {
    const queue = readQueue()
    queue.push({ id, url, meta })
    writeQueue(queue)
    return id
  }

  return runDownload(id, url, meta)
}

export function cancelDownload(id) {
  // Abhi shuru hi nahi hua (queue mein baitha tha) — bas queue se hata do.
  const queue = readQueue()
  const queueIdx = queue.findIndex((q) => q.id === id)
  if (queueIdx >= 0) {
    queue.splice(queueIdx, 1)
    writeQueue(queue)
  }
  activeControllers.get(id)?.abort()
  activeControllers.delete(id)
  if (hasNativeDownloader()) window.AndroidDownloader.cancelDownload(id)
  writeMeta(readMeta().filter((d) => d.id !== id))
  idbDelete(id).catch(() => {})
  // Yeh hi active download tha to agla queued item shuru karo.
  processQueue()
}

export async function deleteDownload(id) {
  const queue = readQueue()
  const queueIdx = queue.findIndex((q) => q.id === id)
  if (queueIdx >= 0) {
    queue.splice(queueIdx, 1)
    writeQueue(queue)
  }
  activeControllers.get(id)?.abort()
  activeControllers.delete(id)
  if (hasNativeDownloader()) window.AndroidDownloader.deleteDownload(id)
  writeMeta(readMeta().filter((d) => d.id !== id))
  await idbDelete(id).catch(() => {})
  processQueue()
}

// Resolves a playable source for a completed download.
// - Native app: a real `content://` URI (see NativeDownloadManager) — this is
//   what lets VideoPlayer.jsx's native-bridge detection kick in, so offline
//   downloads get the exact same rich player (equalizer/cast/decoder-select/
//   PiP) as online streams, not the plain web <video> fallback.
// - Plain browser (no app): falls back to a `blob:` URL from IndexedDB, same
//   as before — caller owns revoking it (or just let it get GC'd on
//   navigation, these are small single-use object URLs).
export async function getDownloadPlaybackSrc(id) {
  const entry = getDownloadEntry(id)
  if (entry?.nativeUri) {
    // App restart ke baad bhi file abhi disk par hai ya nahi, confirm kar lo.
    if (hasNativeDownloader()) {
      const uri = window.AndroidDownloader.getDownloadUri(id)
      if (uri) return uri
    } else {
      return entry.nativeUri
    }
  }
  const blob = await idbGet(id)
  if (!blob) return null
  return URL.createObjectURL(blob)
}

export function useDownloadsList() {
  const [list, setList] = useState(() => readMeta())

  useEffect(() => {
    const onChange = () => setList(readMeta())
    window.addEventListener(EVENT, onChange)
    window.addEventListener('storage', onChange)
    return () => {
      window.removeEventListener(EVENT, onChange)
      window.removeEventListener('storage', onChange)
    }
  }, [])

  return list
}

export function useDownloadEntry(id) {
  const list = useDownloadsList()
  return list.find((d) => d.id === id) || null
}

// Groups the flat downloads list into per-title "cards" — movies stay as a
// single card each, series episodes get bucketed under one
// showId:season card (so the Downloads tab can render one cover per season,
// exactly like the show's own episode list, instead of a flat file list).
export function groupDownloads(list) {
  const groups = new Map()
  for (const d of list) {
    const key = d.type === 'series' && d.showId ? `series:${d.showId}:${d.season ?? 0}` : `movie:${d.id}`
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        type: d.type,
        showId: d.showId || d.titleId,
        showName: d.showName || d.filename,
        showPoster: d.showPoster || d.poster,
        season: d.season,
        entries: [],
        latestAddedAt: d.addedAt,
      })
    }
    const g = groups.get(key)
    g.entries.push(d)
    if (d.addedAt > g.latestAddedAt) g.latestAddedAt = d.addedAt
  }
  return [...groups.values()].sort((a, b) => b.latestAddedAt - a.latestAddedAt)
}
