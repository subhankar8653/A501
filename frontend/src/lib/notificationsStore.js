import { useEffect, useState } from 'react'
import { getNotifications } from '../api'

// Notification center backed by the server (/notifications.json): replies to
// my comments, subscription activated / deactivated / expiring, admin
// announcements and a one-time welcome. Per-device state (what I've seen, what
// I've hidden) lives in localStorage.
const LAST_SEEN_KEY = 'huka-tube:notifications-last-seen'
const HIDDEN_KEY = 'huka-tube:notifications-hidden'
const EVENT = 'huka-tube-notifications-changed'

function readLastSeen() {
  try {
    const raw = localStorage.getItem(LAST_SEEN_KEY)
    return raw ? Number(raw) : 0
  } catch {
    return 0
  }
}

export function notificationKey(n) {
  return `${n.kind || ''}:${n.ref || ''}:${n.ts || 0}`
}

function readHidden() {
  try {
    const raw = JSON.parse(localStorage.getItem(HIDDEN_KEY) || '[]')
    return Array.isArray(raw) ? raw : []
  } catch {
    return []
  }
}

function emit() {
  window.dispatchEvent(new CustomEvent(EVENT))
}

export function hideNotification(n) {
  try {
    const next = Array.from(new Set([...readHidden(), notificationKey(n)])).slice(-200)
    localStorage.setItem(HIDDEN_KEY, JSON.stringify(next))
  } catch {
    /* ignore storage failures */
  }
  emit()
}

// Called when the notification sheet opens — clears the bell's unread dot.
export function markNotificationsSeen() {
  try {
    localStorage.setItem(LAST_SEEN_KEY, String(Date.now()))
  } catch {
    /* ignore quota errors */
  }
  emit()
}

export function useNotifications() {
  const [list, setList] = useState([])
  const [loading, setLoading] = useState(true)
  const [lastSeen, setLastSeen] = useState(() => readLastSeen())
  const [hidden, setHidden] = useState(() => readHidden())

  useEffect(() => {
    let cancelled = false
    const load = () =>
      getNotifications()
        .then((l) => {
          if (!cancelled) setList(l)
        })
        .catch(() => {})
        .finally(() => {
          if (!cancelled) setLoading(false)
        })
    load()
    const id = window.setInterval(load, 5 * 60 * 1000)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [])

  useEffect(() => {
    const onChange = () => {
      setLastSeen(readLastSeen())
      setHidden(readHidden())
    }
    window.addEventListener(EVENT, onChange)
    window.addEventListener('storage', onChange)
    return () => {
      window.removeEventListener(EVENT, onChange)
      window.removeEventListener('storage', onChange)
    }
  }, [])

  const personal = list.filter((n) => !hidden.includes(notificationKey(n)))
  const unreadCount = personal.filter((n) => (n.ts || 0) * 1000 > lastSeen).length
  return { personal, loading, unreadCount, lastSeen }
}
