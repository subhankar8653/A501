import { useEffect, useState } from 'react'

// FEATURE (user ask: "mere per ek notification bar bhi add karo jahan per
// agar kuchh download karoge ya sev karoge to udhar notification jaega"):
// a small, generic in-app notification/toast system — not tied to any one
// feature. Anything anywhere in the app can call pushToast(...) and a pill
// briefly appears above the bottom nav, then auto-dismisses. Kept as a
// plain in-memory module (no localStorage) since toasts are inherently
// transient and shouldn't survive a reload.
const EVENT = 'suhani-toast-changed'
let toasts = []
let nextId = 1

function emit() {
  window.dispatchEvent(new CustomEvent(EVENT))
}

// kind controls the accent color/icon in ToastHost: 'success' | 'info' | 'error'
export function pushToast(message, kind = 'success') {
  const id = nextId++
  toasts = [...toasts, { id, message, kind }]
  emit()
  window.setTimeout(() => {
    toasts = toasts.filter((t) => t.id !== id)
    emit()
  }, 2600)
  return id
}

export function useToasts() {
  const [list, setList] = useState(() => toasts)
  useEffect(() => {
    const onChange = () => setList(toasts)
    window.addEventListener(EVENT, onChange)
    return () => window.removeEventListener(EVENT, onChange)
  }, [])
  return list
}
