import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getStreams, getMeta } from '../api'
import VideoPlayer from '../components/VideoPlayer'
import Comments from '../components/Comments'
import { useLocalReactions } from '../components/localInteractions'
import { useIsSaved, toggleSaved } from '../lib/savedStore'
import { useDownloadEntry, startDownload, downloadId } from '../lib/downloadsStore'

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
  const [movieMeta, setMovieMeta] = useState(null)
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
  // Saved is per-title (whole movie/show), not per-episode — so saving from
  // any episode of a series shows the show once in the Saved tab.
  const saved = useIsSaved(type, imdbId)

  // Drive-sourced streams occasionally fail server-side extraction. When the
  // <video> element errors out, we probe the same /dl/ URL with a manual
  // (non-followed) redirect: a successful stream answers with a 302 straight
  // to googlevideo.com (type stays "opaqueredirect", nothing downloaded), a
  // failed Drive extraction answers with JSON containing a previewUrl instead
  // — in which case we swap to Drive's own embedded preview player.
  const [driveFallbackUrl, setDriveFallbackUrl] = useState(null)
  const fallbackCheckedFor = useRef(null)

  async function handleVideoFatalError() {
    if (!active?.url || fallbackCheckedFor.current === active.url) return
    fallbackCheckedFor.current = active.url
    try {
      const res = await fetch(active.url, { redirect: 'manual' })
      if (res.type === 'opaqueredirect') return // genuine playback/network issue, not this
      if (res.status === 409 || res.status === 502) {
        const body = await res.json().catch(() => null)
        if (body?.preview_url || body?.detail?.preview_url) {
          setDriveFallbackUrl(body.preview_url || body.detail.preview_url)
          return
        }
      }
      setError('Yeh stream play nahi ho payi.')
    } catch {
      // network hiccup while probing — leave the player's own error state as-is
    }
  }

  const [retryKey, setRetryKey] = useState(0)
  const [toast, setToast] = useState('')
  // Continuously tracks current playback position so that switching quality
  // mid-video can resume from the same spot instead of restarting.
  const resumeAt = useRef(0)

  function switchQuality(stream) {
    setDriveFallbackUrl(null)
    fallbackCheckedFor.current = null
    setActive(stream)
  }

  function showToast(msg) {
    setToast(msg)
    setTimeout(() => setToast(''), 2000)
  }

  useEffect(() => {
    setStreams(null)
    setActive(null)
    setError('')
    setDriveFallbackUrl(null)
    fallbackCheckedFor.current = null
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
  }, [type, id, retryKey])

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

  useEffect(() => {
    if (isSeries) {
      setMovieMeta(null)
      return
    }
    let cancelled = false
    getMeta('movie', imdbId)
      .then((m) => {
        if (!cancelled) setMovieMeta(m)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [isSeries, imdbId])

  const meta = useMemo(() => parseStreamMeta(active), [active])

  // Title-level info (used for the Save button + in-app Downloads list) —
  // whichever of series/movie meta is loaded for this page.
  const titleInfo = useMemo(() => {
    const m = isSeries ? seriesMeta : movieMeta
    return {
      name: m?.name || meta.filename,
      poster: m?.poster || null,
      releaseInfo: m?.releaseInfo || '',
    }
  }, [isSeries, seriesMeta, movieMeta, meta.filename])

  function handleToggleSaved() {
    toggleSaved(type, imdbId, titleInfo)
  }

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

  // Art used to drive the ambient glow bleeding above/below the player.
  const glowImage = useMemo(() => {
    if (isSeries) {
      const ep = allEpisodes.find((e) => e.season === currentSeason && e.episode === currentEpisode)
      return ep?.thumbnail || seriesMeta?.background || seriesMeta?.poster || null
    }
    return movieMeta?.background || movieMeta?.poster || null
  }, [isSeries, allEpisodes, currentSeason, currentEpisode, seriesMeta, movieMeta])

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

  const nextEpisode = upNext.episodes[0] || null

  // "Up Next" sirf aage dekhta hai — pichla episode nikalne ke liye poori
  // (sabhi seasons milakar banayi) list mein current se ek pehle wala dhoondo.
  const prevEpisode = useMemo(() => {
    if (!isSeries || !allEpisodes.length || currentSeason == null) return null
    const idx = allEpisodes.findIndex(
      (e) => e.season === currentSeason && e.episode === currentEpisode
    )
    return idx > 0 ? allEpisodes[idx - 1] : null
  }, [isSeries, allEpisodes, currentSeason, currentEpisode])

  function handleEnded() {
    if (autoplay && isSeries && upNext.episodes[0]) {
      navigate(`/watch/series/${encodeURIComponent(upNext.episodes[0].id)}`)
    }
  }

  // Android app ke chhote (inline) native player ke prev/next button is
  // page ki normal episode-navigation ka hi istemal karte hain (URL update,
  // title/comments/Up-Next-list sab isi se sync rehte hain) — native khud
  // episode ka URL nahi jaanta, bas yeh do window function call karta hai.
  useEffect(() => {
    window.__suhaniOnNativeNext = () => {
      if (nextEpisode) navigate(`/watch/series/${encodeURIComponent(nextEpisode.id)}`)
    }
    window.__suhaniOnNativePrev = () => {
      if (prevEpisode) navigate(`/watch/series/${encodeURIComponent(prevEpisode.id)}`)
    }
    return () => {
      delete window.__suhaniOnNativeNext
      delete window.__suhaniOnNativePrev
    }
  }, [nextEpisode, prevEpisode, navigate])

  // Android ko batao ki agla/pichla episode maujood hai ya nahi, taaki
  // wahan prev/next button sahi se enable/dim ho.
  useEffect(() => {
    window.AndroidPlayer?.setAdjacentEpisodes?.(!!nextEpisode, !!prevEpisode)
  }, [nextEpisode, prevEpisode])

  // Download is per-quality — one entry per stream URL, so switching
  // quality and downloading again doesn't clash with an earlier download.
  const thisDownloadId = activeQualityObj ? downloadId(type, imdbId, activeQualityObj.label) : null
  const downloadEntry = useDownloadEntry(thisDownloadId)

  // Kicks off an in-app managed download (progress tracked globally, file
  // saved into IndexedDB) instead of pushing straight to the device's
  // Downloads folder — this is what makes it show up, and stay playable
  // offline, inside the app's own Downloads tab.
  function downloadFile() {
    if (!active?.url || downloadEntry?.status === 'downloading' || downloadEntry?.status === 'done') return
    startDownload(active.url, {
      type,
      titleId: imdbId,
      filename: meta.filename,
      poster: titleInfo.poster,
      qualityLabel: activeQualityObj?.label || '',
    })
    showToast('Download shuru ho gaya — Downloads tab mein dekho')
  }

  function shareIt() {
    if (navigator.share) {
      navigator.share({ title: meta.filename, url: window.location.href }).catch(() => {})
    } else {
      navigator.clipboard
        ?.writeText(window.location.href)
        .then(() => showToast('Link copied!'))
        .catch(() => {})
    }
  }

  return (
    <div className="max-w-3xl mx-auto pb-6">
      {error ? (
        <div className="px-4 sm:px-6">
          <p className="text-reel-rust mb-3">{error}</p>
          <button
            onClick={() => setRetryKey((k) => k + 1)}
            className="text-sm px-4 py-2 rounded-full bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-95 transition"
          >
            Retry
          </button>
        </div>
      ) : !active ? (
        <div>
          <div className="aspect-video bg-reel-surface2 animate-pulse" />
          <div className="px-4 sm:px-6 mt-4 space-y-3">
            <div className="h-5 w-3/4 rounded bg-reel-surface2 animate-pulse" />
            <div className="flex gap-2">
              <div className="h-6 w-20 rounded-full bg-reel-surface2 animate-pulse" />
              <div className="h-6 w-16 rounded-full bg-reel-surface2 animate-pulse" />
            </div>
            <div className="flex gap-2 mt-2">
              <div className="h-8 w-16 rounded-full bg-reel-surface2 animate-pulse" />
              <div className="h-8 w-16 rounded-full bg-reel-surface2 animate-pulse" />
              <div className="h-8 w-24 rounded-full bg-reel-surface2 animate-pulse" />
            </div>
          </div>
        </div>
      ) : (
        <>
          {/* Bug fix (user report): page scroll karte waqt video "andar ghus
              jaata" (galat z-order/position mein chala jaata) tha kyunki yeh
              container normal flow mein tha. Ab YouTube jaisa hi — video ek
              hi jagah top par sticky/fixed rehta hai jab neeche ka content
              (title, comments, Up Next) scroll hota hai. */}
          <div className="relative sticky top-0 z-30 bg-reel-bg">
            {glowImage ? (
              <>
                <div
                  aria-hidden="true"
                  className="absolute inset-x-0 -top-9 h-14 -z-10 pointer-events-none"
                >
                  <img
                    src={glowImage}
                    alt=""
                    className="w-full h-full object-cover blur-2xl scale-125 opacity-35"
                    style={{
                      maskImage: 'linear-gradient(to top, black 0%, transparent 90%)',
                      WebkitMaskImage: 'linear-gradient(to top, black 0%, transparent 90%)',
                    }}
                  />
                </div>
                <div
                  aria-hidden="true"
                  className="absolute inset-x-0 -bottom-9 h-14 -z-10 pointer-events-none"
                >
                  <img
                    src={glowImage}
                    alt=""
                    className="w-full h-full object-cover blur-2xl scale-125 opacity-35"
                    style={{
                      maskImage: 'linear-gradient(to bottom, black 0%, transparent 90%)',
                      WebkitMaskImage: 'linear-gradient(to bottom, black 0%, transparent 90%)',
                    }}
                  />
                </div>
              </>
            ) : null}
            <div className="relative aspect-video bg-black overflow-hidden">
              {driveFallbackUrl ? (
                // Direct extraction failed for this Drive file (Google restricts
                // the unofficial method per-file) — fall back to Drive's own
                // embedded preview player, which always works but has no custom
                // controls/skin of ours.
                <iframe
                  src={driveFallbackUrl}
                  title={meta.filename || 'Video'}
                  className="w-full h-full"
                  allow="autoplay; fullscreen"
                  allowFullScreen
                  frameBorder="0"
                />
              ) : (
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
                  onFatalError={handleVideoFatalError}
                />
              )}
            </div>
          </div>

          <div className="px-4 sm:px-6">
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
              aria-label="Like"
              aria-pressed={reactions.mine === 'like'}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 active:scale-95 transition ${
                reactions.mine === 'like' ? 'bg-reel-gold text-reel-bg font-semibold' : 'bg-reel-surface2 text-reel-muted'
              }`}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"/></svg>
              {reactions.likes}
            </button>
            <button
              onClick={() => react('dislike')}
              aria-label="Dislike"
              aria-pressed={reactions.mine === 'dislike'}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 active:scale-95 transition ${
                reactions.mine === 'dislike' ? 'bg-reel-rust text-reel-ink font-semibold' : 'bg-reel-surface2 text-reel-muted'
              }`}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm4 0v12h4V3h-4z"/></svg>
              {reactions.dislikes}
            </button>
            <button
              onClick={downloadFile}
              disabled={downloadEntry?.status === 'downloading'}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 bg-reel-surface2 text-reel-muted hover:text-reel-ink active:scale-95 transition disabled:opacity-70"
              title="Download"
              aria-label="Download"
            >
              {downloadEntry?.status === 'downloading' ? (
                <>
                  <span className="w-3 h-3 border-2 border-reel-muted/30 border-t-reel-gold rounded-full animate-spin" />
                  {downloadEntry.progress ? `${downloadEntry.progress}%` : 'Downloading…'}
                </>
              ) : downloadEntry?.status === 'done' ? (
                <>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round"><polyline points="20 6 9 17 4 12"/></svg>
                  Downloaded
                </>
              ) : (
                <>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                  Download
                </>
              )}
            </button>
            <div className="relative shrink-0">
              <button onClick={shareIt} aria-label="Share" className="p-2 rounded-full text-xs bg-reel-surface2 text-reel-muted hover:text-reel-ink active:scale-95 transition" title="Share">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
              </button>
              {toast ? (
                <div className="animate-toast-in absolute top-full mt-2 left-1/2 -translate-x-1/2 whitespace-nowrap text-[11px] bg-reel-ink text-reel-bg font-semibold px-2.5 py-1 rounded-full z-10">
                  {toast}
                </div>
              ) : null}
            </div>
            <button
              onClick={handleToggleSaved}
              aria-label={saved ? 'Remove from saved' : 'Save'}
              aria-pressed={saved}
              className={`p-2 rounded-full text-xs shrink-0 active:scale-95 transition ${saved ? 'bg-reel-gold text-reel-bg' : 'bg-reel-surface2 text-reel-muted hover:text-reel-ink'}`}
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
                  aria-pressed={autoplay}
                  className={`flex items-center gap-2 text-xs px-3 py-1.5 rounded-full shrink-0 active:scale-95 transition ${
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
                    className="w-full flex gap-4 text-left bg-reel-surface hover:bg-reel-surface2 active:scale-[0.98] transition rounded-lg p-3 ring-1 ring-white/5"
                  >
                    <img
                      src={ep.thumbnail}
                      alt={ep.title}
                      loading="lazy"
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
          </div>
        </>
      )}
    </div>
  )
}
