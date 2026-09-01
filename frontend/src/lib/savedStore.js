import { useEffect, useState } from 'react'
import { pushToast } from './toastStore'

// Single source of truth for "Saved" titles — a flat list in localStorage
// (not per-episode) so a show saved from any episode shows up once in the
// Saved tab and links straight to its Detail page.
const KEY = 'suhani-screen:saved-list'
const EVENT = 'suhani-saved-changed'

function readList() {
  try {
    const raw = localStorage.getItem(KEY)
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

function writeList(list) {
  try {
    localStorage.setItem(KEY, JSON.stringify(list))
  } catch {
    /* ignore quota errors */
  }
  window.dispatchEvent(new CustomEvent(EVENT))
}

export function getSavedList() {
  return readList()
}

export function isSaved(type, id) {
  return readList().some((it) => it.type === type && it.id === id)
}

// meta: { name, poster, releaseInfo, imdbRating }
export function toggleSaved(type, id, meta = {}) {
  const list = readList()
  const idx = list.findIndex((it) => it.type === type && it.id === id)
  if (idx >= 0) {
    list.splice(idx, 1)
    writeList(list)
    pushToast(`Removed from Saved${meta.name ? `: ${meta.name}` : ''}`)
    return false
  }
  list.unshift({ type, id, addedAt: Date.now(), ...meta })
  writeList(list)
  pushToast(`Saved${meta.name ? `: ${meta.name}` : ''}`)
  return true
}

export function removeSaved(type, id) {
  writeList(readList().filter((it) => !(it.type === type && it.id === id)))
}

// Reactive hook — re-reads whenever any component changes the list
// (including this same list toggled elsewhere, e.g. Player's Save button).
export function useSavedList() {
  const [list, setList] = useState(() => readList())

  useEffect(() => {
    const onChange = () => setList(readList())
    window.addEventListener(EVENT, onChange)
    window.addEventListener('storage', onChange)
    return () => {
      window.removeEventListener(EVENT, onChange)
      window.removeEventListener('storage', onChange)
    }
  }, [])

  return list
}

export function useIsSaved(type, id) {
  const list = useSavedList()
  return list.some((it) => it.type === type && it.id === id)
}
