import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getStreams, getMeta } from '../api'
import VideoPlayer from '../components/VideoPlayer'
import Comments from '../components/Comments'
import { useLocalReactions, useLocalSaved } from '../components/localInteractions'

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

// Pulls a short "360p / 480p / 720p / 1080p" style label out of a stream's
// name or title so the in-player quality menu shows something compact
// instead of the full filename.
function qualityLabel(stream) {
  const hay = `${stream?.name || ''} ${stream?.title || ''}`
  const res = hay.match(/\b(2160p|4k|1440p|1080p|720p|480p|360p|240p)\b/i)
  if (res) return res[1].toLowerCase() === '4k' ? '4K' : res[1].toLowerCase()
  return (stream?.name || 'Auto').split('\n')[0].trim()
}

export default function Player() {
  const { type, id } = useParams()
  const navigate = useNavigate()
  const [streams, setStreams] = useState(null)
  const [active, setActive] = useState(null)
  const [error, setError] = useState('')

  const isSeries = type === 'series'
  const [imdbId, seasonStr, episodeStr] = isSeries ? id.split(':') : [id, null, null]
  const currentSeason = seasonStr !== undefined ? Number(seasonStr) : null
  const currentEpisode = episodeStr !== undefined ? Number(episodeStr) : null

  const [seriesMeta, setSeriesMeta] = useState(null)
  const [autoplay, setAutoplay] = useState(() => {
    try {
      return localStorage.getItem('suhani-screen:autoplay') !== 'off'
    } catch {
      return true
    }
  })

  function toggleAutoplay() {
    setAutoplay((a) => {
      const next = !a
      try {
        localStorage.setItem('suhani-screen:autoplay', next ? 'on' : 'off')
      } catch {
        // ignore storage failures
      }
      return next
    })
  }

  const storageKey = `${type}:${id}`
  const { reactions, react } = useLocalReactions(`suhani-screen:reactions:${storageKey}`)
  const { saved, toggle: toggleSaved } = useLocalSaved(`suhani-screen:saved:${storageKey}`)

  const [downloading, setDownloading] = useState(false)
  const [dlProgress, setDlProgress] = useState(0)
  // Continuously tracks current playback position so that switching quality
  // mid-video can resume from the same spot instead of restarting.
  const resumeAt = useRef(0)

  function switchQuality(stream) {
    setActive(stream)
  }

  useEffect(() => {
    setStreams(null)
    setActive(null)
    setError('')
    resumeAt.current = 0
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

  useEffect(() => {
    if (!isSeries) {
      setSeriesMeta(null)
      return
    }
    let cancelled = false
    getMeta('series', imdbId)
      .then((m) => {
        if (!cancelled) setSeriesMeta(m)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [isSeries, imdbId])

  const meta = useMemo(() => parseStreamMeta(active), [active])

  const qualities = useMemo(
    () => (streams || []).map((s) => ({ ...s, label: qualityLabel(s) })),
    [streams]
  )
  const activeQualityObj = useMemo(
    () => qualities.find((q) => q.url === active?.url) || null,
    [qualities, active]
  )

  // Every episode across every season, in watch order.
  const allEpisodes = useMemo(() => {
    if (!seriesMeta?.videos) return []
    return [...seriesMeta.videos].sort((a, b) => a.season - b.season || a.episode - b.episode)
  }, [seriesMeta])

  // Rest of the current season after this episode — or, once you're on the
  // season's last episode, the *entire* next season's episode list.
  const upNext = useMemo(() => {
    if (!isSeries || !allEpisodes.length || currentSeason == null) {
      return { label: '', episodes: [] }
    }
    const sameSeason = allEpisodes.filter((e) => e.season === currentSeason)
    const idx = sameSeason.findIndex((e) => e.episode === currentEpisode)
    let episodes = idx >= 0 ? sameSeason.slice(idx + 1) : []
    let label = `Season ${currentSeason}`
    if (!episodes.length) {
      const nextSeasonNum = [...new Set(allEpisodes.map((e) => e.season))]
        .filter((s) => s > currentSeason)
        .sort((a, b) => a - b)[0]
      if (nextSeasonNum !== undefined) {
        episodes = allEpisodes.filter((e) => e.season === nextSeasonNum)
        label = `Season ${nextSeasonNum}`
      }
    }
    return { label, episodes }
  }, [isSeries, allEpisodes, currentSeason, currentEpisode])

  function handleEnded() {
    if (autoplay && isSeries && upNext.episodes[0]) {
      navigate(`/watch/series/${encodeURIComponent(upNext.episodes[0].id)}`)
    }
  }

  // Fetches the file as a blob first, then downloads from that in-memory
  // blob — so no new tab opens and the backend's real URL never shows up
  // in the address bar. Falls back to opening the direct link only if
  // the fetch itself fails (e.g. network/CORS issue).
  async function downloadFile() {
    if (!active?.url || downloading) return
    setDownloading(true)
    setDlProgress(0)
    try {
      const res = await fetch(active.url)
      if (!res.ok || !res.body) throw new Error('bad response')
      const total = Number(res.headers.get('content-length')) || 0
      const reader = res.body.getReader()
      const chunks = []
      let received = 0
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        chunks.push(value)
        received += value.length
        if (total) setDlProgress(Math.round((received / total) * 100))
      }
      const blob = new Blob(chunks)
      const blobUrl = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = blobUrl
      a.download = meta.filename || 'download'
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      setTimeout(() => URL.revokeObjectURL(blobUrl), 30000)
    } catch {
      window.open(active.url, '_blank', 'noopener,noreferrer')
    } finally {
      setDownloading(false)
      setDlProgress(0)
    }
  }

  function shareIt() {
    if (navigator.share) {
      navigator.share({ title: meta.filename, url: window.location.href }).catch(() => {})
    } else {
      navigator.clipboard?.writeText(window.location.href).catch(() => {})
    }
  }

  return (
    <div className="max-w-3xl mx-auto py-6 px-4 sm:px-6">
      {error ? (
        <p className="text-reel-rust">{error}</p>
      ) : !active ? (
        <div className="aspect-video bg-reel-surface2 rounded-xl animate-pulse" />
      ) : (
        <>
          <div className="aspect-video bg-black rounded-xl overflow-hidden ring-1 ring-white/10">
            <VideoPlayer
              key={active.url}
              src={active.url}
              title={meta.filename}
              qualities={qualities}
              activeQuality={activeQualityObj}
              onQualityChange={(q) => switchQuality(q)}
              startAt={resumeAt.current}
              onProgressTick={(t) => { resumeAt.current = t }}
              onEnded={handleEnded}
            />
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
            <button
              onClick={downloadFile}
              disabled={downloading}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 bg-reel-surface2 text-reel-muted hover:text-reel-ink disabled:opacity-70"
              title="Download"
            >
              {downloading ? (
                <>
                  <span className="w-3 h-3 border-2 border-reel-muted/30 border-t-reel-gold rounded-full animate-spin" />
                  {dlProgress ? `${dlProgress}%` : 'Downloading…'}
                </>
              ) : (
                <>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                  Download
                </>
              )}
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

          {/* Comments */}
          <div className="mt-6">
            <Comments storageKey={`suhani-screen:comments:${storageKey}`} />
          </div>

          {/* Up next — rest of this season, or the next season once you hit its last episode */}
          {isSeries && upNext.episodes.length > 0 ? (
            <div className="mt-8 pt-6 border-t border-white/5">
              <div className="flex items-center justify-between mb-3 gap-3">
                <h2 className="font-display text-lg text-reel-ink">Up Next · {upNext.label}</h2>
                <button
                  onClick={toggleAutoplay}
                  className={`flex items-center gap-2 text-xs px-3 py-1.5 rounded-full shrink-0 transition ${
                    autoplay ? 'bg-reel-gold text-reel-bg font-semibold' : 'bg-reel-surface2 text-reel-muted'
                  }`}
                >
                  <span className={`w-2 h-2 rounded-full ${autoplay ? 'bg-reel-bg' : 'bg-reel-muted'}`} />
                  Autoplay {autoplay ? 'On' : 'Off'}
                </button>
              </div>
              <div className="space-y-3">
                {upNext.episodes.map((ep) => (
                  <button
                    key={ep.id}
                    onClick={() => navigate(`/watch/series/${encodeURIComponent(ep.id)}`)}
                    className="w-full flex gap-4 text-left bg-reel-surface hover:bg-reel-surface2 transition rounded-lg p-3 ring-1 ring-white/5"
                  >
                    <img
                      src={ep.thumbnail}
                      alt={ep.title}
                      className="w-32 sm:w-40 aspect-video object-cover rounded-md shrink-0"
                    />
                    <div className="min-w-0">
                      <p className="font-medium text-sm">
                        S{ep.season} · E{ep.episode} · {ep.title}
                      </p>
                      <p className="text-xs text-reel-muted mt-1 line-clamp-2">{ep.overview}</p>
                    </div>
                  </button>
                ))}
              </div>
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}
