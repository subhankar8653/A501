import { useEffect, useState } from 'react'

const NICK_KEY = 'suhani-screen:nick'
const NICKNAMES = ['CinemaFan', 'MovieBuff', 'StreamKing', 'FilmLover', 'NightWatch', 'CoolViewer', 'HindiFilms', 'StarWatcher']

function getNick() {
  try {
    let n = localStorage.getItem(NICK_KEY)
    if (!n) {
      n = NICKNAMES[Math.floor(Math.random() * NICKNAMES.length)] + Math.floor(Math.random() * 99 + 1)
      localStorage.setItem(NICK_KEY, n)
    }
    return n
  } catch {
    return 'Guest'
  }
}

function hueFor(name) {
  return [...name].reduce((a, c) => a + c.charCodeAt(0), 0) % 360
}

function relTime(ts) {
  const d = Date.now() - ts
  const s = Math.floor(d / 1000)
  const m = Math.floor(s / 60)
  const h = Math.floor(m / 60)
  const day = Math.floor(h / 24)
  if (s < 60) return 'abhi'
  if (m < 60) return `${m}m pehle`
  if (h < 24) return `${h}h pehle`
  return `${day}d pehle`
}

// Comments are stored per-title in localStorage, on this device only —
// there's no shared backend for them (matches the reference player's note).
export default function Comments({ storageKey }) {
  const [nick] = useState(getNick)
  const [comments, setComments] = useState([])
  const [text, setText] = useState('')

  useEffect(() => {
    try {
      const raw = localStorage.getItem(storageKey)
      setComments(raw ? JSON.parse(raw) : [])
    } catch {
      setComments([])
    }
  }, [storageKey])

  function persist(next) {
    setComments(next)
    try {
      localStorage.setItem(storageKey, JSON.stringify(next))
    } catch {
      /* storage full or unavailable — comment still shows for this session */
    }
  }

  function post() {
    const trimmed = text.trim()
    if (!trimmed) return
    const next = [...comments, { name: nick, text: trimmed, ts: Date.now(), likes: 0, likedByMe: false }]
    persist(next)
    setText('')
  }

  function likeToggle(idx) {
    const next = comments.map((c, i) =>
      i === idx
        ? { ...c, likes: Math.max(0, (c.likes || 0) + (c.likedByMe ? -1 : 1)), likedByMe: !c.likedByMe }
        : c
    )
    persist(next)
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-3">
        <p className="text-sm font-display font-semibold text-reel-ink">💬 Comments</p>
        <span className="text-xs text-reel-muted">{comments.length} comment{comments.length === 1 ? '' : 's'}</span>
      </div>

      <p className="text-[11px] text-reel-muted bg-reel-surface2 rounded-lg px-3 py-2 mb-3">
        ⚠️ Comments sirf is device pe save hote hain — doosre users ko nahi dikhenge.
      </p>

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
            placeholder="Add a comment…"
            rows={1}
            className="w-full bg-reel-surface2 rounded-lg px-3 py-2 text-sm text-reel-ink placeholder:text-reel-muted focus:outline-none focus:ring-1 focus:ring-reel-gold/60 resize-none"
          />
          {text ? (
            <div className="flex gap-2 mt-2 justify-end">
              <button onClick={() => setText('')} className="text-xs px-3 py-1 rounded text-reel-muted hover:text-reel-ink">
                Cancel
              </button>
              <button onClick={post} className="text-xs px-3 py-1 rounded bg-reel-gold text-reel-bg font-semibold">
                Comment
              </button>
            </div>
          ) : null}
        </div>
      </div>

      {comments.length === 0 ? (
        <p className="text-xs text-reel-muted py-2">Sabse pehle comment karo!</p>
      ) : (
        <div className="space-y-3">
          {comments.slice().reverse().map((c, revIdx) => {
            const idx = comments.length - 1 - revIdx
            return (
              <div key={idx} className="flex items-start gap-2">
                <div
                  className="w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold text-white shrink-0"
                  style={{ background: `hsl(${hueFor(c.name)}, 55%, 35%)` }}
                >
                  {c.name[0].toUpperCase()}
                </div>
                <div>
                  <p className="text-xs font-semibold text-reel-ink">{c.name}</p>
                  <p className="text-sm text-reel-ink/90 break-words">{c.text}</p>
                  <div className="flex items-center gap-3 mt-1">
                    <span className="text-[11px] text-reel-muted">{relTime(c.ts)}</span>
                    <button
                      onClick={() => likeToggle(idx)}
                      className={`text-[11px] flex items-center gap-1 ${c.likedByMe ? 'text-reel-gold' : 'text-reel-muted'}`}
                    >
                      <svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor"><path d="M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"/></svg>
                      {c.likes || 0}
                    </button>
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
