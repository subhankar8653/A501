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
}

const activeControllers = new Map() // id -> AbortController

export function downloadId(type, titleId, qualityLabel) {
  return `${type}:${titleId}:${qualityLabel || 'default'}`
}

export function getDownloadEntry(id) {
  return readMeta().find((d) => d.id === id) || null
}

// meta: { type, titleId, filename, poster, qualityLabel }
export async function startDownload(url, meta) {
  const id = downloadId(meta.type, meta.titleId, meta.qualityLabel)
  const existing = getDownloadEntry(id)
  if (existing && (existing.status === 'downloading' || existing.status === 'done')) return id

  const controller = new AbortController()
  activeControllers.set(id, controller)

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
    status: 'downloading',
    progress: 0,
    sizeBytes: 0,
    addedAt: Date.now(),
  })

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
  }
  return id
}

export function cancelDownload(id) {
  activeControllers.get(id)?.abort()
  activeControllers.delete(id)
  writeMeta(readMeta().filter((d) => d.id !== id))
  idbDelete(id).catch(() => {})
}

export async function deleteDownload(id) {
  activeControllers.get(id)?.abort()
  activeControllers.delete(id)
  writeMeta(readMeta().filter((d) => d.id !== id))
  await idbDelete(id).catch(() => {})
}

// Resolves a playable blob: URL for a completed download. Caller owns
// revoking it (or just let it get GC'd on navigation — these are small
// single-use object URLs).
export async function getDownloadBlobUrl(id) {
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
