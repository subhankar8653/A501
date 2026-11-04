// In-app update logic (pure module — the React hook lives in useUpdate.js).
//
// Two kinds of updates, both pushed from the owner's Telegram bot:
//   * bundle (OTA)  – a new UI (index.html). Downloaded silently in the
//                     background by the native shell, applied on next start
//                     or when the user taps "Restart now".
//   * apk           – a new native build. User taps "Update now", the APK is
//                     downloaded by the native shell and Android's installer
//                     opens.
//
// Native contract (see android .../AppUpdater.kt, JS name "AndroidUpdater"):
//   getAppInfo() -> JSON {versionCode, versionName, bundleVersion}
//   downloadBundle(url, sha256, version) / downloadApk(url, sha256, code)
//   applyBundle()     reload the WebView with the downloaded bundle
//   markBootOk()      tell native this UI booted fine (auto-rollback guard)
// and native calls back: window.__nativeUpdate(kind, state, pct, message)
//   kind: 'apk' | 'bundle'
//   state: 'progress' | 'ready' | 'installing' | 'needs_permission' | 'error'

import { activeBackend, setRemoteEndpoints } from './endpoints'

const EVENT = 'huka-update-changed'
const DISMISSED_KEY = 'huka:update-dismissed' // version the user said "later" to
const SEEN_KEY = 'huka:update-seen' // version whose notification card was opened
const CHECK_EVERY_MS = 6 * 60 * 60 * 1000
const MIN_GAP_MS = 20 * 60 * 1000

let state = {
  apk: null, // {version_code, version_name, changelog, force, url, sha256, size}
  bundle: null, // {version, changelog, force, url, sha256, size}
  apkStatus: 'idle', // idle | downloading | installing | needs_permission | error
  apkPct: 0,
  bundleStatus: 'idle', // idle | downloading | ready | error
  lastCheck: 0,
}

export function getUpdateState() {
  return state
}

function set(patch) {
  state = { ...state, ...patch }
  window.dispatchEvent(new CustomEvent(EVENT))
}

export const UPDATE_EVENT = EVENT

function native() {
  const u = window.AndroidUpdater
  return u && typeof u.getAppInfo === 'function' ? u : null
}

export function hasNativeUpdater() {
  return !!native()
}

function appInfo() {
  try {
    const n = native()
    return n ? JSON.parse(n.getAppInfo()) : null
  } catch {
    return null
  }
}

const absolute = (rel) => (/^https?:\/\//i.test(rel) ? rel : `${activeBackend()}${rel}`)

function lsGet(k) {
  try {
    return localStorage.getItem(k)
  } catch {
    return null
  }
}
function lsSet(k, v) {
  try {
    localStorage.setItem(k, v)
  } catch {
    /* ignore */
  }
}

// ---------------------------------------------------------------- native -> JS
window.__nativeUpdate = (kind, status, pct, message) => {
  if (kind === 'bundle') {
    if (status === 'ready') {
      set({ bundleStatus: 'ready' })
      // A "force" UI update applies itself right away.
      if (state.bundle && state.bundle.force) applyBundle()
    } else if (status === 'progress') set({ bundleStatus: 'downloading' })
    else if (status === 'error') set({ bundleStatus: 'error' })
  } else if (kind === 'apk') {
    if (status === 'progress') set({ apkStatus: 'downloading', apkPct: Math.max(0, Math.min(100, pct | 0)) })
    else if (status === 'installing') set({ apkStatus: 'installing', apkPct: 100 })
    else if (status === 'needs_permission') set({ apkStatus: 'needs_permission' })
    else if (status === 'error') set({ apkStatus: 'error' })
  }
  void message
}

// ---------------------------------------------------------------- actions
export async function checkForUpdate(force = false) {
  const n = native()
  const info = appInfo()
  if (!n || !info) return
  if (!force && Date.now() - state.lastCheck < MIN_GAP_MS) return
  const base = activeBackend()
  if (!base) return
  set({ lastCheck: Date.now() })
  try {
    const res = await fetch(`${base}/api/app/update?vc=${info.versionCode}&bv=${info.bundleVersion}`, {
      cache: 'no-store',
    })
    if (!res.ok) return
    const body = await res.json()
    const d = (body && body.data) || {}
    if (Array.isArray(d.endpoints)) setRemoteEndpoints(d.endpoints)
    set({ apk: d.apk || null, bundle: d.bundle || null })
    if (d.bundle && state.bundleStatus !== 'downloading' && state.bundleStatus !== 'ready') {
      startBundleDownload()
    }
  } catch {
    /* offline / server busy — try again next time */
  }
}

export function startBundleDownload() {
  const n = native()
  const b = state.bundle
  if (!n || !b) return
  set({ bundleStatus: 'downloading' })
  try {
    n.downloadBundle(absolute(b.url), b.sha256 || '', b.version | 0)
  } catch {
    set({ bundleStatus: 'error' })
  }
}

export function startApkDownload() {
  const n = native()
  const a = state.apk
  if (!n || !a) return
  set({ apkStatus: 'downloading', apkPct: 0 })
  try {
    n.downloadApk(absolute(a.url), a.sha256 || '', a.version_code | 0)
  } catch {
    set({ apkStatus: 'error' })
  }
}

export function applyBundle() {
  try {
    native()?.applyBundle()
  } catch {
    /* ignore */
  }
}

// Called once the new UI has mounted — lets native cancel its rollback guard.
export function markBootOk() {
  try {
    native()?.markBootOk()
  } catch {
    /* ignore */
  }
}

// ---------------------------------------------------------------- UI helpers
export function currentNoticeKey() {
  if (state.apk) return `apk:${state.apk.version_code}`
  if (state.bundleStatus === 'ready' && state.bundle) return `bundle:${state.bundle.version}`
  return ''
}

export function dismissNotice() {
  const k = currentNoticeKey()
  if (k) lsSet(DISMISSED_KEY, k)
  window.dispatchEvent(new CustomEvent(EVENT))
}

export function isDismissed() {
  const k = currentNoticeKey()
  return !!k && lsGet(DISMISSED_KEY) === k
}

export function markNoticeSeen() {
  const k = currentNoticeKey()
  if (k) lsSet(SEEN_KEY, k)
  window.dispatchEvent(new CustomEvent(EVENT))
}

export function hasUnseenNotice() {
  const k = currentNoticeKey()
  return !!k && lsGet(SEEN_KEY) !== k
}

// ---------------------------------------------------------------- scheduler
let started = false
export function startUpdateChecks() {
  if (started || !native()) return
  started = true
  checkForUpdate(true)
  window.setInterval(() => checkForUpdate(true), CHECK_EVERY_MS)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') checkForUpdate(false)
  })
}
