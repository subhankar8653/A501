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

  // FEATURE (user ask: "stream hote waqt download queue mein chala jaega,
  // watch band karte hi apne aap shuru ho jaega — single download ya watch
  // hoga, dono ek saath kabhi nahi, jo bhi chal raha ho use full power
  // mile"): native side (NativeDownloadManager) ne is id ka download
  // genuinely rok diya hai kyunki watching shuru ho gayi thi. Isse ek error
  // ki tarah treat NAHI karna — wapas 'queued' bana kar QUEUE ke sabse
  // AAGE rakho (taaki watching band hote hi sabse pehle yahi resume ho,
  // naye downloads se pehle), progress/partial-bytes jitne ho chuke the
  // wahi rehne do (Range-resume/TDLib cache se aage jaari rahega).
  window.__nativeDownloadPaused = (id) => {
    const entry = getDownloadEntry(id)
    if (!entry) return
    upsert({ id, status: 'queued' })
    const queue = readQueue()
    const idx = queue.findIndex((q) => q.id === id)
    const item = idx >= 0 ? queue.splice(idx, 1)[0] : { id, url: entry.url, meta: entry }
    queue.unshift(item)
    writeQueue(queue)
  }

  // FEATURE: native rich player (inline overlay ya fullscreen — dono same
  // ExoPlayer instance share karte hain, dekho MainActivity.kt) se aata hai
  // jab bhi actually play/pause hota hai. Web (browser-fallback) side se
  // yahi kaam VideoPlayer.jsx ke `onPlayStateChange` prop se hota hai —
  // dono ek hi `setWatching()` ko feed karte hain.
  window.__nativeWatchingChanged = (isPlaying) => setWatching(isPlaying)
}

const activeControllers = new Map() // id -> AbortController

// FEATURE (user ask: "download pause ho kar watch band hote hi wahin se
// aage badhega, poore se dobara nahi"): plain-browser (no native bridge)
// fallback ke liye — pause par abhi tak fetch kiye gaye chunks yahin
// in-memory rakhte hain (page reload se yeh kho jaata hai, tab restart se
// download hoga — acceptable edge case). id -> { chunks, received }.
const pausedJsDownloads = new Map()
// AbortError do wajah se aa sakta hai: genuine cancel (cancelDownload — us
// case mein meta entry already hata di gayi hai, kuch bhi resume nahi karna)
// ya watching-pause (is set mein id maujood hoga) — isi se differentiate
// karte hain.
const pauseJsRequested = new Set()

function pauseJsDownload(id) {
  pauseJsRequested.add(id)
  activeControllers.get(id)?.abort()
}

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

// FEATURE (user ask: "stream hote waqt download queue mein chala jaega,
// watch band karte hi apne aap shuru ho jaega — single download ya watch
// hoga, dono ek saath kabhi nahi, jo bhi chal raha ho use full power
// mile"): jab tak koi video actively chal (play ho) raha hai, koi bhi
// download shuru/resume nahi hoga — chahe queue mein kitna hi wait kyun na
// kar raha ho. `watching` ke false hone ka signal thoda debounce kiya jaata
// hai (short pause/buffering blips par download baar-baar start/stop na
// ho — sirf genuine "band kar diya" par hi resume ho).
let watching = false
let watchStopTimer = null
const WATCH_STOP_DEBOUNCE_MS = 1500

export function isWatchingNow() {
  return watching
}

export function setWatching(isPlaying) {
  if (isPlaying) {
    if (watchStopTimer) {
      clearTimeout(watchStopTimer)
      watchStopTimer = null
    }
    if (watching) return
    watching = true
    pauseActiveDownloadForWatch()
    return
  }
  // Already waiting to flip to "not watching" — let that timer run, don't
  // reset it (avoids indefinitely postponing resume on rapid pause/resume).
  if (watchStopTimer || !watching) return
  watchStopTimer = setTimeout(() => {
    watchStopTimer = null
    watching = false
    processQueue()
  }, WATCH_STOP_DEBOUNCE_MS)
}

// Whatever download is currently 'downloading' gets stopped RIGHT NOW
// (native bridge pause, or in-memory pause for the plain-browser JS-fetch
// fallback) and put back at the FRONT of the queue — so it's the very next
// thing that resumes once watching stops, ahead of anything freshly queued
// meanwhile.
function pauseActiveDownloadForWatch() {
  const list = readMeta()
  const activeEntry = list.find((d) => d.status === 'downloading')
  if (!activeEntry) return

  if (hasNativeDownloader() && typeof window.AndroidDownloader.pauseDownload === 'function') {
    // Native side reports back via window.__nativeDownloadPaused once it's
    // genuinely stopped — that's what actually re-queues the entry (keeps
    // a single source of truth for "is this id really stopped yet").
    window.AndroidDownloader.pauseDownload(activeEntry.id)
    return
  }

  pauseJsDownload(activeEntry.id)
  upsert({ id: activeEntry.id, status: 'queued' })
  const queue = readQueue()
  const idx = queue.findIndex((q) => q.id === activeEntry.id)
  const item = idx >= 0 ? queue.splice(idx, 1)[0] : { id: activeEntry.id, url: activeEntry.url, meta: activeEntry }
  queue.unshift(item)
  writeQueue(queue)
}

// Jab bhi ek download khatam (done/error/cancelled) hota hai, yeh queue mein
// se agla item nikaal kar shuru karta hai — agar koi aur pehle se active na ho.
function processQueue() {
  if (isAnyDownloadActive()) return
  // FEATURE: watching chal rahi ho to koi naya/queued download shuru mat
  // karo — watching band hote hi (setWatching(false) ke debounce ke baad)
  // yeh khud phir se call hoga.
  if (watching) return
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

  // Agar ismein pehle kabhi pause hui thi (watching ki wajah se), wahi
  // bache hue chunks/received-bytes se aage jodo — Range header se sirf
  // bacha hua hissa maango.
  const resumeState = pausedJsDownloads.get(id)
  pausedJsDownloads.delete(id)
  const priorReceived = resumeState?.received || 0
  let chunks = resumeState?.chunks || []
  let received = priorReceived

  try {
    const fetchOpts = { signal: controller.signal }
    if (priorReceived > 0) fetchOpts.headers = { Range: `bytes=${priorReceived}-` }
    const res = await fetch(url, fetchOpts)
    if (!res.ok || !res.body) throw new Error('bad response')
    // Range maanga tha lekin server ne 206 ki jagah 200 diya — Range support
    // nahi karta, ab poori file naye sirse aa rahi hai — purane chunks se
    // jodna corrupt file banayega, poore se hi shuru karo.
    const serverHonoredRange = priorReceived > 0 && res.status === 206
    if (priorReceived > 0 && !serverHonoredRange) {
      chunks = []
      received = 0
    }
    const remaining = Number(res.headers.get('content-length')) || 0
    const total = remaining > 0 ? received + remaining : 0
    const reader = res.body.getReader()
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
      if (pauseJsRequested.has(id)) {
        // Watching ki wajah se pause hui — chunks/received bacha kar rakho,
        // agla runDownload() (watching band hote hi processQueue() se)
        // yahin se aage badhega.
        pauseJsRequested.delete(id)
        pausedJsDownloads.set(id, { chunks, received })
      }
      // warna genuine user-cancelled — entry already removed by cancelDownload()
    } else {
      pausedJsDownloads.delete(id)
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

  const shouldQueue = isAnyDownloadActive() || watching

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
    // FEATURE: pause/resume (native ya JS fallback) ke liye baad mein isi
    // url ki zaroorat padti hai, isliye ab meta ke saath persist karte hain.
    url,
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
  pauseJsRequested.delete(id)
  pausedJsDownloads.delete(id)
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
  pauseJsRequested.delete(id)
  pausedJsDownloads.delete(id)
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
