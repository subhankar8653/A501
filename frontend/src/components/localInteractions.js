import { useEffect, useState } from 'react'
import { getReactions, toggleReaction } from '../api'

// FEATURE (user ask: "likes/dislikes real backend pe honi chahiye"): pehle
// yeh purely localStorage-based tha (isi device ka apna private counter,
// kisi aur ko dikhta hi nahi tha). Ab backend se real, sab logon ke beech
// shared like/dislike counts aate hain (dekho api.js getReactions/
// toggleReaction, aur backend database.py toggle_reaction — ek hi title
// document mein sirf user-id ki chhoti list rehti hai, storage bahut
// compact rehta hai).
export function useLocalReactions(type, id) {
  const [reactions, setReactions] = useState({ likes: 0, dislikes: 0, mine: null })

  useEffect(() => {
    let cancelled = false
    getReactions(type, id)
      .then((r) => {
        if (!cancelled) setReactions(r)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [type, id])

  async function react(kind) {
    // Optimistic update — feels instant, backend confirms/corrects right after.
    setReactions((prev) => {
      const next = { ...prev }
      const wasMine = prev.mine === kind
      if (wasMine) {
        next[`${kind}s`] = Math.max(0, (prev[`${kind}s`] || 0) - 1)
        next.mine = null
      } else {
        if (prev.mine) next[`${prev.mine}s`] = Math.max(0, (prev[`${prev.mine}s`] || 0) - 1)
        next[`${kind}s`] = (prev[`${kind}s`] || 0) + 1
        next.mine = kind
      }
      return next
    })
    try {
      const confirmed = await toggleReaction(type, id, kind)
      setReactions(confirmed)
    } catch {
      /* leave the optimistic value — a stale count is fine, a stuck
         "connecting…" state would be worse */
    }
  }

  return { reactions, react }
}
