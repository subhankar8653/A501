import { useEffect, useMemo, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getStreams } from '../api'
import VideoPlayer from '../components/VideoPlayer'
import Comments from '../components/Comments'
import { useLocalReactions, useLocalSaved } from '../components/localInteractions'

const APP_PLAYERS = [
  { key: 'mx', label: 'MX Player', pkg: 'com.mxtech.videoplayer.ad', className: 'bg-[#1a56db] hover:brightness-110' },
  { key: 'vlc', label: 'VLC Player', pkg: 'org.videolan.vlc', className: 'bg-[#e6720f] hover:brightness-110' },
  { key: 'playit', label: 'PlayIt Player', pkg: 'com.playit.videoplayer', className: 'bg-[#7c2ee6] hover:brightness-110' },
]

// Splits the backend's stream.title (e.g. "📁 file.mkv\n💾 3.34GB\n🎥 x265 ...")
// into a clean filename + list of badge lines.
function parseStreamMeta(stream) {
  const lines = (stream?.title || '').split('\n').map((l) => l.trim()).filter(Boolean)
  let filename = stream?.name || ''
  const badges = []
  for (const line of lines) {
    if (line.startsWith('📁')) filename = line.replace('📁', '').trim()
    else badges.push(line)
  }
  return { filename, badges }
}

function PlayIcon() {
  return <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
}

export default function Player() {
  const { type, id } = useParams()
  const navigate = useNavigate()
  const [streams, setStreams] = useState(null)
  const [active, setActive] = useState(null)
  const [error, setError] = useState('')

  const storageKey = `${type}:${id}`
  const { reactions, react } = useLocalReactions(`suhani-screen:reactions:${storageKey}`)
  const { saved, toggle: toggleSaved } = useLocalSaved(`suhani-screen:saved:${storageKey}`)

  useEffect(() => {
    setStreams(null)
    setActive(null)
    setError('')
    getStreams(type, id)
      .then((list) => {
        if (!list.length) {
          setError('Is title ke liye koi stream nahi mili.')
          return
        }
        setStreams(list)
        setActive(list[0])
      })
      .catch(() => setError('Stream load nahi hui.'))
  }, [type, id])

  const meta = useMemo(() => parseStreamMeta(active), [active])

  function downloadFile() {
    if (!active?.url) return
    const a = document.createElement('a')
    a.href = active.url
    a.download = meta.filename || 'download'
    a.target = '_blank'
    a.rel = 'noopener noreferrer'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
  }

  function shareIt() {
    if (navigator.share) {
      navigator.share({ title: meta.filename, url: window.location.href }).catch(() => {})
    } else {
      navigator.clipboard?.writeText(window.location.href).catch(() => {})
    }
  }

  function openInApp(playerKey) {
    if (!active?.url) return
    const pkg = APP_PLAYERS.find((p) => p.key === playerKey)?.pkg
    const bare = active.url.replace(/^https?:\/\//, '')
    window.location.href = `intent://${bare}#Intent;package=${pkg};type=video/*;scheme=https;end`
  }

  return (
    <div className="max-w-3xl mx-auto py-6 px-4 sm:px-6">
      <button onClick={() => navigate(-1)} className="text-sm text-reel-muted hover:text-reel-ink mb-4">
        ← Back
      </button>

      {error ? (
        <p className="text-reel-rust">{error}</p>
      ) : !active ? (
        <div className="aspect-video bg-reel-surface2 rounded-xl animate-pulse" />
      ) : (
        <>
          <div className="aspect-video bg-black rounded-xl overflow-hidden ring-1 ring-white/10">
            <VideoPlayer key={active.url} src={active.url} />
          </div>

          {/* Title + badges */}
          <div className="mt-4">
            <h1 className="font-display text-lg text-reel-ink break-words">{meta.filename}</h1>
            <div className="flex flex-wrap gap-2 mt-2">
              {meta.badges.map((b, i) => (
                <span key={i} className="text-xs px-2.5 py-1 rounded-full bg-reel-surface2 text-reel-muted whitespace-pre">
                  {b}
                </span>
              ))}
            </div>
          </div>

          {/* Reactions / download / share / save row */}
          <div className="flex items-center gap-2 mt-4 overflow-x-auto no-scrollbar">
            <button
              onClick={() => react('like')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 ${
                reactions.mine === 'like' ? 'bg-reel-gold text-reel-bg font-semibold' : 'bg-reel-surface2 text-reel-muted'
              }`}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"/></svg>
              {reactions.likes}
            </button>
            <button
              onClick={() => react('dislike')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 ${
                reactions.mine === 'dislike' ? 'bg-reel-rust text-reel-ink font-semibold' : 'bg-reel-surface2 text-reel-muted'
              }`}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm4 0v12h4V3h-4z"/></svg>
              {reactions.dislikes}
            </button>
            <button onClick={downloadFile} className="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 bg-reel-surface2 text-reel-muted hover:text-reel-ink" title="Download">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
              Download
            </button>
            <button onClick={shareIt} className="p-2 rounded-full text-xs shrink-0 bg-reel-surface2 text-reel-muted hover:text-reel-ink" title="Share">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
            </button>
            <button
              onClick={toggleSaved}
              className={`p-2 rounded-full text-xs shrink-0 ${saved ? 'bg-reel-gold text-reel-bg' : 'bg-reel-surface2 text-reel-muted hover:text-reel-ink'}`}
              title="Save"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill={saved ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
            </button>
          </div>

          {/* Qualities */}
          {streams.length > 1 ? (
            <div className="mt-5">
              <p className="text-xs text-reel-muted mb-2">Available qualities</p>
              <div className="flex gap-2 flex-wrap">
                {streams.map((s, i) => (
                  <button
                    key={i}
                    onClick={() => setActive(s)}
                    title={s.title}
                    className={`text-sm px-3 py-1.5 rounded-lg transition whitespace-pre-line ${
                      s === active
                        ? 'bg-reel-gold text-reel-bg font-semibold'
                        : 'bg-reel-surface2 text-reel-muted hover:text-reel-ink'
                    }`}
                  >
                    {s.name}
                  </button>
                ))}
              </div>
            </div>
          ) : null}

          {/* Open in app */}
          <div className="mt-6">
            <p className="text-xs text-reel-muted mb-2 tracking-wide">OPEN IN APP</p>
            <div className="grid grid-cols-2 gap-2.5">
              {APP_PLAYERS.slice(0, 2).map((p) => (
                <button
                  key={p.key}
                  onClick={() => openInApp(p.key)}
                  className={`flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-semibold text-white transition ${p.className}`}
                >
                  <PlayIcon /> {p.label}
                </button>
              ))}
              <button
                onClick={() => openInApp('playit')}
                className={`col-span-2 flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-semibold text-white transition ${APP_PLAYERS[2].className}`}
              >
                <PlayIcon /> {APP_PLAYERS[2].label}
              </button>
            </div>
          </div>

          {/* Comments */}
          <div className="mt-8 pt-6 border-t border-white/5">
            <Comments storageKey={`suhani-screen:comments:${storageKey}`} />
          </div>
        </>
      )}
    </div>
  )
}
