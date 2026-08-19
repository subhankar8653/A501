import { useEffect, useState } from 'react'

// Like/dislike + saved-for-later state, kept per title in localStorage.
// No backend for this — it's a personal, on-device toggle like the
// reference player's reactions.
export function useLocalReactions(storageKey) {
  const [reactions, setReactions] = useState({ likes: 0, dislikes: 0, mine: null })

  useEffect(() => {
    try {
      const raw = localStorage.getItem(storageKey)
      setReactions(raw ? JSON.parse(raw) : { likes: 0, dislikes: 0, mine: null })
    } catch {
      setReactions({ likes: 0, dislikes: 0, mine: null })
    }
  }, [storageKey])

  function persist(next) {
    setReactions(next)
    try {
      localStorage.setItem(storageKey, JSON.stringify(next))
    } catch {
      /* ignore */
    }
  }

  function react(type) {
    const next = { ...reactions }
    const wasActive = next.mine === type
    if (wasActive) {
      next[`${type}s`] = Math.max(0, next[`${type}s`] - 1)
      next.mine = null
    } else {
      if (next.mine) next[`${next.mine}s`] = Math.max(0, next[`${next.mine}s`] - 1)
      next[`${type}s`] = (next[`${type}s`] || 0) + 1
      next.mine = type
    }
    persist(next)
  }

  return { reactions, react }
}

