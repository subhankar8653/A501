import { useEffect, useState } from 'react'
import { getConfig } from '../api'

// ---------------------------------------------------------------------
// 1) Plain browser connectivity — is the device online at all.
// ---------------------------------------------------------------------
export function useOnlineStatus() {
  const [isOnline, setIsOnline] = useState(navigator.onLine)

  useEffect(() => {
    const goOnline = () => setIsOnline(true)
    const goOffline = () => setIsOnline(false)
    window.addEventListener('online', goOnline)
    window.addEventListener('offline', goOffline)
    return () => {
      window.removeEventListener('online', goOnline)
      window.removeEventListener('offline', goOffline)
    }
  }, [])

  return isOnline
}

// ---------------------------------------------------------------------
// 2) Backend health — device has internet, but is OUR server (Railway
//    etc.) actually reachable/up. Distinct from #1 so the UI can tell
//    "no internet" and "server crashed" apart, per user's spec.
// ---------------------------------------------------------------------
const HEALTH_CHECK_INTERVAL_MS = 15000
const HEALTH_CHECK_TIMEOUT_MS = 6000
// Require this many consecutive failures before declaring the server
// down — avoids flashing the "server crashed" screen on one slow blip.
const FAILURES_BEFORE_DOWN = 2

async function pingBackend(backendUrl, token) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), HEALTH_CHECK_TIMEOUT_MS)
  try {
    const res = await fetch(`${backendUrl}/stremio/${token}/manifest.json`, {
      signal: controller.signal,
      cache: 'no-store',
    })
    return res.ok
  } catch {
    return false
  } finally {
    clearTimeout(timeout)
  }
}

// Only meaningful once the app is configured (post-/setup) and the
// device itself is online — no point pinging the backend while the
// device has no internet at all, `isOnline` already covers that case.
export function useBackendHealth(isOnline) {
  const [isServerUp, setIsServerUp] = useState(true)

  useEffect(() => {
    if (!isOnline) return undefined

    const cfg = getConfig()
    if (!cfg) return undefined

    let cancelled = false
    let consecutiveFailures = 0

    async function check() {
      const ok = await pingBackend(cfg.backendUrl, cfg.token)
      if (cancelled) return
      if (ok) {
        consecutiveFailures = 0
        setIsServerUp(true)
      } else {
        consecutiveFailures += 1
        if (consecutiveFailures >= FAILURES_BEFORE_DOWN) setIsServerUp(false)
      }
    }

    check()
    const id = setInterval(check, HEALTH_CHECK_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(id)
    }
  }, [isOnline])

  // Device offline is not a server problem — don't report a false "down".
  return isOnline ? isServerUp : true
}
