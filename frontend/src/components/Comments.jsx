import { useEffect, useState } from 'react'
import { getProfile, getComments, postComment, deleteComment, editComment } from '../api'
import { useLanguage } from '../i18n/LanguageContext'

function hueFor(name) {
  return [...name].reduce((a, c) => a + c.charCodeAt(0), 0) % 360
}

function relTime(ts, t) {
  const d = Date.now() - ts * 1000
  const s = Math.floor(d / 1000)
  const m = Math.floor(s / 60)
  const h = Math.floor(m / 60)
  const day = Math.floor(h / 24)
  if (s < 60) return t('comments_just_now')
  if (m < 60) return `${m}${t('comments_min_ago')}`
  if (h < 24) return `${h}${t('comments_hr_ago')}`
  return `${day}${t('comments_day_ago')}`
}

// FEATURE (user ask: "comments ye sab real backend pe honi chahiye, sirf
// device pe fake nahi"): pehle comments localStorage mein device-only
// rehte the — ab MongoDB-backed backend se aate/jaate hain (dekho api.js
// getComments/postComment/deleteComment), taaki sab logon ko wahi ek comment
// list dikhe. `type`/`id` woh hi hain jo Detail/Player already use karte
// hain (movie/series + imdb-jaisa id).
export default function Comments({ type, id, onCountChange, title, poster }) {
  const { t } = useLanguage()
  const profile = getProfile()
  const myUserId = profile?.userId
  const [comments, setComments] = useState(null) // null = loading
  const [text, setText] = useState('')
  const [posting, setPosting] = useState(false)
  const [editingTs, setEditingTs] = useState(null)
  const [editText, setEditText] = useState('')

  useEffect(() => {
    let cancelled = false
    setComments(null)
    getComments(type, id)
      .then((list) => {
        if (!cancelled) {
          setComments(list)
          onCountChange?.(list.length)
        }
      })
      .catch(() => {
        if (!cancelled) setComments([])
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type, id])

  async function post() {
    const trimmed = text.trim()
    if (!trimmed || posting) return
    setPosting(true)
    try {
      // title/poster ride along so this shows up nicely in the Profile
      // page's "My Comments" list without a second metadata fetch (dekho
      // database.py add_comment + get_user_comments).
      const entry = await postComment(type, id, trimmed, title, poster)
      setComments((prev) => {
        const next = [entry, ...(prev || [])]
        onCountChange?.(next.length)
        return next
      })
      setText('')
    } catch {
      /* backend hiccup — leave the draft so the user can retry */
    } finally {
      setPosting(false)
    }
  }

  async function remove(ts) {
    setComments((prev) => {
      const next = (prev || []).filter((c) => c.ts !== ts)
      onCountChange?.(next.length)
      return next
    })
    try {
      await deleteComment(type, id, ts)
    } catch {
      /* already optimistically removed — not worth re-showing on failure */
    }
  }

  // PROFILE FEATURE (user ask: "My Comments — edit/delete ka option"):
  // inline edit, same optimistic-then-confirm pattern as post()/remove().
  function startEdit(c) {
    setEditingTs(c.ts)
    setEditText(c.t)
  }

  async function saveEdit(ts) {
    const trimmed = editText.trim()
    if (!trimmed) return
    setComments((prev) => (prev || []).map((c) => (c.ts === ts ? { ...c, t: trimmed } : c)))
    setEditingTs(null)
    try {
      await editComment(type, id, ts, trimmed)
    } catch {
      /* already optimistically applied */
    }
  }

  const nick = profile?.name || 'Guest'

  return (
    <div>
      <div className="flex items-start gap-2 mb-4">
        <div
          className="w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold text-white shrink-0"
          style={{ background: `hsl(${hueFor(nick)}, 55%, 35%)` }}
        >
          {nick[0].toUpperCase()}
        </div>
        <div className="flex-1">
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={t('comments_add_placeholder')}
            rows={1}
            maxLength={500}
            className="w-full bg-reel-surface2 rounded-lg px-3 py-2 text-sm text-reel-ink placeholder:text-reel-muted focus:outline-none focus:ring-1 focus:ring-reel-gold/60 resize-none"
          />
          {text ? (
            <div className="flex gap-2 mt-2 justify-end">
              <button onClick={() => setText('')} className="text-xs px-3 py-1 rounded text-reel-muted hover:text-reel-ink">
                {t('cancel')}
              </button>
              <button onClick={post} disabled={posting} className="text-xs px-3 py-1 rounded bg-reel-gold text-reel-bg font-semibold disabled:opacity-60">
                {t('comments_post')}
              </button>
            </div>
          ) : null}
        </div>
      </div>

      {comments === null ? (
        <div className="space-y-3">
          {[0, 1].map((i) => (
            <div key={i} className="flex items-start gap-2">
              <div className="w-8 h-8 rounded-full bg-reel-surface2 animate-pulse shrink-0" />
              <div className="flex-1 space-y-1.5 pt-1">
                <div className="h-2.5 w-24 rounded bg-reel-surface2 animate-pulse" />
                <div className="h-2.5 w-40 rounded bg-reel-surface2 animate-pulse" />
              </div>
            </div>
          ))}
        </div>
      ) : comments.length === 0 ? null : (
        <div className="space-y-3">
          {comments.map((c) => (
            <div key={c.ts} className="flex items-start gap-2">
              <div
                className="w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold text-white shrink-0"
                style={{ background: `hsl(${hueFor(c.n)}, 55%, 35%)` }}
              >
                {c.n[0].toUpperCase()}
              </div>
              <div className="flex-1">
                <p className="text-xs font-semibold text-reel-ink">{c.n}</p>
                {editingTs === c.ts ? (
                  <div className="mt-1">
                    <textarea
                      value={editText}
                      onChange={(e) => setEditText(e.target.value)}
                      rows={1}
                      maxLength={500}
                      autoFocus
                      className="w-full bg-reel-surface2 rounded-lg px-3 py-2 text-sm text-reel-ink focus:outline-none focus:ring-1 focus:ring-reel-gold/60 resize-none"
                    />
                    <div className="flex gap-2 mt-1.5 justify-end">
                      <button onClick={() => setEditingTs(null)} className="text-[11px] px-2.5 py-1 rounded text-reel-muted hover:text-reel-ink">
                        {t('cancel')}
                      </button>
                      <button onClick={() => saveEdit(c.ts)} className="text-[11px] px-2.5 py-1 rounded bg-reel-gold text-reel-bg font-semibold">
                        {t('comments_post')}
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <p className="text-sm text-reel-ink/90 break-words">{c.t}</p>
                    <div className="flex items-center gap-3 mt-1">
                      <span className="text-[11px] text-reel-muted">{relTime(c.ts, t)}</span>
                      {myUserId && c.u === myUserId ? (
                        <>
                          <button onClick={() => startEdit(c)} className="text-[11px] text-reel-muted hover:text-reel-ink">
                            {t('edit') || 'Edit'}
                          </button>
                          <button onClick={() => remove(c.ts)} className="text-[11px] text-reel-muted hover:text-reel-rust">
                            {t('remove')}
                          </button>
                        </>
                      ) : null}
                    </div>
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
