import { useEffect, useState } from 'react'
import { getManifest, groupCatalogsByTab, loadNewToYou } from '../api'

// FEATURE (user ask: "theme mode change bala icon hai uske pass main
// notification option vi add karo"): a lightweight notification center —
// no separate backend endpoint needed, it just reuses the exact same
// "New to You" latest-uploads pool (see loadNewToYou in api.js, which is
// itself sorted off each title's real addedAt timestamp from the backend)
// and layers a per-device "have I seen this yet" state on top, tracked in
// localStorage — same pattern as savedStore.js.
const LAST_SEEN_KEY = 'huka-tube:notifications-last-seen'
const EVENT = 'huka-tube-notifications-changed'
const NOTIFICATIONS_LIMIT = 20

function readLastSeen() {
  try {
    const raw = localStorage.getItem(LAST_SEEN_KEY)
    return raw ? Number(raw) : 0
  } catch {
    return 0
  }
}

function writeLastSeen(ts) {
  try {
    localStorage.setItem(LAST_SEEN_KEY, String(ts))
  } catch {
    /* ignore quota errors */
  }
  window.dispatchEvent(new CustomEvent(EVENT))
}

// Called when the notification sheet opens — clears the unread badge.
export function markNotificationsSeen() {
  writeLastSeen(Date.now())
}

// Latest uploads across the WHOLE app (every catalog, not just one tab),
// trimmed to a sane notification-list length.
export async function loadNotifications() {
  const manifest = await getManifest()
  const tabCatalogs = groupCatalogsByTab(manifest.catalogs)
  const groups = await loadNewToYou(tabCatalogs.new)
  return (groups[0]?.items || []).slice(0, NOTIFICATIONS_LIMIT)
}

// Reactive hook: loads the latest-uploads list once, and reports how many
// of them are newer than the last time the user opened the sheet (the
// bell's unread badge).
export function useNotifications() {
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
  const [lastSeen, setLastSeen] = useState(() => readLastSeen())

  useEffect(() => {
    let cancelled = false
    loadNotifications()
      .then((list) => {
        if (!cancelled) setItems(list)
      })
      .catch(() => {
        if (!cancelled) setItems([])
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    const onChange = () => setLastSeen(readLastSeen())
    window.addEventListener(EVENT, onChange)
    window.addEventListener('storage', onChange)
    return () => {
      window.removeEventListener(EVENT, onChange)
      window.removeEventListener('storage', onChange)
    }
  }, [])

  const unreadCount = items.filter(
    (it) => new Date(it.addedAt || 0).getTime() > lastSeen
  ).length

  return { items, loading, unreadCount, lastSeen }
}
