import { useEffect, useState } from 'react'
import { getMeta } from '../api'
import { useOnlineStatus } from '../lib/connectivity'
import {
  useDownloadsList,
  cancelDownload,
  deleteDownload,
  getDownloadPlaybackSrc,
  groupDownloads,
} from '../lib/downloadsStore'
import DownloadQualitySheet from '../components/DownloadQualitySheet'
import VideoPlayer from '../components/VideoPlayer'
import Comments from '../components/Comments'
import { useLocalReactions } from '../components/localInteractions'
import { useLanguage } from '../i18n/LanguageContext'

function formatSize(bytes) {
  if (!bytes) return ''
  const mb = bytes / (1024 * 1024)
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

// BUG FIX (user report: "offline wala player jyada hi fullscreen kar diya,
// online jaisa chahiye"): this used to be a `fixed inset-0` overlay that
// covered the ENTIRE screen edge-to-edge with the video stretched to fit
// whatever space was left — nothing like the real Player page, which keeps
// the video in a normal 16:9 box at the top of an actual scrollable page
// with the filename, badges, like/dislike/share/save row, and comments
// below it. Rebuilt this to be that same page layout (reusing the exact
// same pieces — VideoPlayer, Comments, useLocalReactions), just fed from
// the local downloaded file — a native content:// URI when running inside
// the app (same rich player as online, see downloadsStore.js), or a blob:
// URL as a plain-browser fallback. Same look whether you're online watching
// a stream or offline watching a download.
function OfflinePlayer({ entry, onClose }) {
  const { t } = useLanguage()
  const [url, setUrl] = useState(null)
  const [err, setErr] = useState(false)
  const storageKey = `download:${entry.id}`
  const { reactions, react } = useLocalReactions(`suhani-screen:reactions:${storageKey}`)

  useEffect(() => {
    let cancelled = false
    let objectUrl = null
    getDownloadPlaybackSrc(entry.id)
      .then((u) => {
        if (cancelled) return
        if (!u) return setErr(true)
        if (u.startsWith('blob:')) objectUrl = u
        setUrl(u)
      })
      .catch(() => !cancelled && setErr(true))
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [entry.id])

  const badges = [
    entry.qualityLabel,
    formatSize(entry.sizeBytes),
    t('offline'),
  ].filter(Boolean)

  return (
    <div className="fixed inset-0 z-50 bg-reel-bg overflow-y-auto">
      <div className="max-w-3xl mx-auto pb-6">
        <div className="relative sticky top-0 z-30 bg-reel-bg">
          <button
            onClick={onClose}
            aria-label="Back"
            className="absolute top-3 left-3 z-[85] w-9 h-9 rounded-full bg-black/55 backdrop-blur-sm flex items-center justify-center text-reel-ink active:scale-90 transition"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><polyline points="15 18 9 12 15 6" /></svg>
          </button>
          <div className="relative aspect-video bg-black overflow-hidden">
            {err ? (
              <div className="w-full h-full flex items-center justify-center px-6">
                <p className="text-reel-muted text-sm text-center">{t('dl_offline_play_failed')}</p>
              </div>
            ) : url ? (
              <VideoPlayer
                key={url}
                src={url}
                title={entry.filename}
                onEnded={onClose}
                onFatalError={() => setErr(true)}
              />
            ) : (
              <div className="w-full h-full flex items-center justify-center">
                <span className="w-8 h-8 border-2 border-reel-muted/30 border-t-reel-gold rounded-full animate-spin" />
              </div>
            )}
          </div>
        </div>

        <div className="px-4 sm:px-6">
          <div className="mt-4">
            <h1 className="font-display text-lg text-reel-ink break-words">{entry.filename}</h1>
            <div className="flex flex-wrap gap-2 mt-2">
              {badges.map((b, i) => (
                <span key={i} className="text-xs px-2.5 py-1 rounded-full bg-reel-surface2 text-reel-muted whitespace-pre">
                  {b}
                </span>
              ))}
            </div>
          </div>

          <div className="flex items-center gap-2 mt-4 overflow-x-auto no-scrollbar">
            <button
              onClick={() => react('like')}
              aria-label="Like"
              aria-pressed={reactions.mine === 'like'}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 active:scale-95 transition ${
                reactions.mine === 'like' ? 'bg-reel-gold text-reel-bg font-semibold' : 'bg-reel-surface2 text-reel-muted'
              }`}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z" /></svg>
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
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm4 0v12h4V3h-4z" /></svg>
              {reactions.dislikes}
            </button>
            <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 bg-reel-surface2 text-reel-muted">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round"><polyline points="20 6 9 17 4 12" /></svg>
              {t('downloaded')}
            </span>
          </div>

          <div className="mt-6">
            <Comments storageKey={`suhani-screen:comments:${storageKey}`} />
          </div>
        </div>
      </div>
    </div>
  )
}

// One movie's download row (movies don't have seasons/episodes) — unchanged
// from before, Play/Cancel/Delete.
function MovieRow({ d, onPlay }) {
  const { t } = useLanguage()
  return (
    <div className="flex items-center gap-3 bg-reel-surface rounded-lg p-3 ring-1 ring-reel-ink/5">
      <div className="w-16 aspect-[2/3] rounded-md overflow-hidden bg-reel-surface2 shrink-0">
        {d.poster ? <img src={d.poster} alt="" className="w-full h-full object-cover" /> : null}
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-reel-ink line-clamp-1">{d.filename}</p>
        {d.status === 'queued' ? (
          <p className="text-xs text-reel-muted mt-1">{t('dl_queued')}</p>
        ) : d.status === 'downloading' ? (
          <>
            <div className="h-1.5 mt-2 rounded-full bg-reel-surface2 overflow-hidden">
              <div className="h-full bg-reel-gold transition-all" style={{ width: `${d.progress || 0}%` }} />
            </div>
            <p className="text-xs text-reel-muted mt-1">{d.progress || 0}% · {t('dl_downloading')}</p>
          </>
        ) : d.status === 'error' ? (
          <p className="text-xs text-reel-rust mt-1">{t('dl_failed')}</p>
        ) : (
          <p className="text-xs text-reel-muted mt-1">
            {d.qualityLabel ? `${d.qualityLabel} · ` : ''}
            {formatSize(d.sizeBytes)} · {t('dl_offline_available')}
          </p>
        )}
      </div>
      <div className="flex items-center gap-2 shrink-0">
        {d.status === 'done' ? (
          <button
            onClick={() => onPlay(d)}
            className="px-3 py-1.5 rounded-full text-xs bg-reel-gold text-reel-bg font-semibold active:scale-95 transition"
          >
            {t('play')}
          </button>
        ) : null}
        {d.status === 'downloading' || d.status === 'queued' ? (
          <button
            onClick={() => cancelDownload(d.id)}
            className="px-3 py-1.5 rounded-full text-xs bg-reel-surface2 text-reel-muted active:scale-95 transition"
          >
            {t('cancel')}
          </button>
        ) : (
          <button
            onClick={() => deleteDownload(d.id)}
            aria-label="Delete download"
            className="w-8 h-8 rounded-full bg-reel-surface2 text-reel-muted flex items-center justify-center active:scale-90 transition"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"><polyline points="3 6 5 6 21 6" /><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" /><path d="M10 11v6" /><path d="M14 11v6" /></svg>
          </button>
        )}
      </div>
    </div>
  )
}

// One episode row inside an expanded season — shows the SAME thumbnail the
// Detail page uses for that episode (ep.thumbnail), not the show poster.
// Downloaded/downloading episodes look normal + active (Play/Cancel/Delete).
// Not-yet-downloaded episodes are dimmed (greyed, not clickable to play) —
// they still get a ⋮ menu with a "Download" action so a single missing
// episode can be grabbed without leaving the Downloads tab.
function SeasonEpisodeRow({ ep, entry, onPlay, onDownloadOne }) {
  const { t } = useLanguage()
  const [menuOpen, setMenuOpen] = useState(false)
  const isDownloaded = entry?.status === 'done'
  const isDownloading = entry?.status === 'downloading'
  const isQueued = entry?.status === 'queued'
  const hasAny = !!entry

  return (
    <div
      className={`relative flex items-center gap-3 rounded-lg p-2.5 ring-1 ring-reel-ink/5 transition ${
        hasAny ? 'bg-reel-surface' : 'bg-reel-surface/40 opacity-50'
      }`}
    >
      <div className="w-20 aspect-video rounded-md overflow-hidden bg-reel-surface2 shrink-0">
        {ep.thumbnail ? <img src={ep.thumbnail} alt="" className="w-full h-full object-cover" /> : null}
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-reel-ink line-clamp-1">
          E{ep.episode} · {ep.title}
        </p>
        {isQueued ? (
          <p className="text-xs text-reel-muted mt-1">{t('dl_queued')}</p>
        ) : isDownloading ? (
          <>
            <div className="h-1.5 mt-2 rounded-full bg-reel-surface2 overflow-hidden">
              <div className="h-full bg-reel-gold transition-all" style={{ width: `${entry.progress || 0}%` }} />
            </div>
            <p className="text-xs text-reel-muted mt-1">{entry.progress || 0}% · {t('dl_downloading')}</p>
          </>
        ) : isDownloaded ? (
          <p className="text-xs text-reel-muted mt-1">
            {entry.qualityLabel ? `${entry.qualityLabel} · ` : ''}
            {formatSize(entry.sizeBytes)} · {t('dl_offline_available')}
          </p>
        ) : entry?.status === 'error' ? (
          <p className="text-xs text-reel-rust mt-1">{t('dl_failed')}</p>
        ) : (
          <p className="text-xs text-reel-muted mt-1">{t('dl_not_downloaded')}</p>
        )}
      </div>

      <div className="flex items-center gap-1.5 shrink-0">
        {isDownloaded ? (
          <button
            onClick={() => onPlay(entry)}
            className="px-3 py-1.5 rounded-full text-xs bg-reel-gold text-reel-bg font-semibold active:scale-95 transition"
          >
            {t('play')}
          </button>
        ) : null}
        {isDownloading || isQueued ? (
          <button
            onClick={() => cancelDownload(entry.id)}
            className="px-3 py-1.5 rounded-full text-xs bg-reel-surface2 text-reel-muted active:scale-95 transition"
          >
            {t('cancel')}
          </button>
        ) : null}

        <div className="relative">
          <button
            onClick={() => setMenuOpen((m) => !m)}
            aria-label="Episode options"
            className="w-8 h-8 rounded-full flex items-center justify-center text-reel-muted hover:bg-reel-ink/10 active:scale-90 transition"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
          </button>
          {menuOpen ? (
            <div className="absolute top-full right-0 mt-1 min-w-[150px] rounded-xl overflow-hidden bg-reel-bg/97 backdrop-blur-md ring-1 ring-reel-ink/10 shadow-[0_8px_28px_rgba(0,0,0,0.65)] z-10">
              {!isDownloaded && !isDownloading && !isQueued ? (
                <button
                  onClick={() => {
                    setMenuOpen(false)
                    onDownloadOne(ep)
                  }}
                  className="w-full flex items-center gap-2 px-3.5 py-3 text-xs text-reel-ink hover:bg-reel-ink/5"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" /></svg>
                  {t('download')}
                </button>
              ) : (
                <button
                  onClick={() => {
                    setMenuOpen(false)
                    deleteDownload(entry.id)
                  }}
                  className="w-full flex items-center gap-2 px-3.5 py-3 text-xs text-reel-rust hover:bg-reel-ink/5"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"><polyline points="3 6 5 6 21 6" /><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" /><path d="M10 11v6" /><path d="M14 11v6" /></svg>
                  {t('remove')}
                </button>
              )}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  )
}

// A season's cover card — poster + name + "N/total downloaded", tap to
// expand into the FULL episode list for that season (fetched fresh from
// getMeta, same as Detail.jsx — not just the ones already downloaded), each
// with its own thumbnail and status. Header also carries a "Season" download
// button, exactly like the one on the Detail page.
function SeasonCard({ group, onPlay }) {
  const { t } = useLanguage()
  const [expanded, setExpanded] = useState(false)
  const [allEpisodes, setAllEpisodes] = useState(null) // null = not loaded yet
  const [downloadTarget, setDownloadTarget] = useState(null)
  const isOnline = useOnlineStatus()

  const doneCount = group.entries.filter((d) => d.status === 'done').length

  // BUG FIX (offline: expanding a season card showed nothing — "1/0
  // episode downloaded" and an empty list, even for the episode that WAS
  // sitting there downloaded and playable). Root cause: this always called
  // getMeta() to get the season's full episode list, same as when online.
  // Offline, that fetch just fails, and the .catch() fell back to an EMPTY
  // array — wiping out even the already-downloaded episodes from view,
  // since the whole list here was solely getMeta's, never the local
  // downloads themselves. Fix: build the list straight from what's actually
  // downloaded (group.entries) whenever we're offline or getMeta fails, so
  // downloaded episodes always show and stay playable no matter what. Only
  // when online + getMeta succeeds do we still show the fuller list that
  // includes not-yet-downloaded episodes (dimmed, with a Download action).
  function episodesFromLocalEntries() {
    return group.entries
      .slice()
      .sort((a, b) => (a.episode || 0) - (b.episode || 0))
      .map((d) => ({
        id: d.id,
        season: d.season,
        episode: d.episode,
        title: d.episodeTitle || d.filename,
        thumbnail: d.poster || group.showPoster,
      }))
  }

  useEffect(() => {
    if (!expanded || allEpisodes) return
    if (!isOnline) {
      setAllEpisodes(episodesFromLocalEntries())
      return
    }
    let cancelled = false
    getMeta('series', group.showId)
      .then((m) => {
        if (cancelled) return
        const eps = (m?.videos || []).filter((v) => v.season === group.season)
        setAllEpisodes(eps.length ? eps : episodesFromLocalEntries())
      })
      .catch(() => !cancelled && setAllEpisodes(episodesFromLocalEntries()))
    return () => {
      cancelled = true
    }
  }, [expanded, allEpisodes, isOnline, group.showId, group.season])

  return (
    <div className="rounded-lg overflow-hidden bg-reel-surface ring-1 ring-reel-ink/5">
      <div className="w-full flex items-center gap-3 p-3">
        <button onClick={() => setExpanded((e) => !e)} className="flex items-center gap-3 text-left flex-1 min-w-0 active:scale-[0.99] transition">
          <div className="w-16 aspect-[2/3] rounded-md overflow-hidden bg-reel-surface2 shrink-0">
            {group.showPoster ? <img src={group.showPoster} alt="" className="w-full h-full object-cover" /> : null}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium text-reel-ink line-clamp-1">{group.showName}</p>
            <p className="text-xs text-reel-muted mt-1">
              {group.season != null && group.season !== 0 ? `${t('season')} ${group.season} · ` : ''}
              {doneCount}/{allEpisodes ? allEpisodes.length : group.entries.length} {t('dl_episodes_downloaded')}
            </p>
          </div>
        </button>

        <button
          onClick={() => allEpisodes && setDownloadTarget(allEpisodes)}
          disabled={!allEpisodes}
          className="shrink-0 flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-sm font-semibold bg-reel-ink/10 text-reel-ink active:scale-95 transition disabled:opacity-40"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" /></svg>
          {t('season')}
        </button>

        <button
          onClick={() => setExpanded((e) => !e)}
          aria-label={expanded ? 'Collapse' : 'Expand'}
          className="w-8 h-8 shrink-0 flex items-center justify-center text-reel-muted"
        >
          <svg
            width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"
            className={`transition-transform ${expanded ? 'rotate-180' : ''}`}
          >
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </button>
      </div>

      {expanded ? (
        <div className="px-3 pb-3 space-y-2 border-t border-reel-ink/5 pt-3">
          {allEpisodes === null ? (
            <div className="py-6 flex justify-center">
              <span className="w-6 h-6 border-2 border-reel-muted/30 border-t-reel-gold rounded-full animate-spin" />
            </div>
          ) : (
            allEpisodes.map((ep) => (
              <SeasonEpisodeRow
                key={ep.id}
                ep={ep}
                entry={group.entries.find((d) => d.episode === ep.episode) || null}
                onPlay={onPlay}
                onDownloadOne={(oneEp) => setDownloadTarget([oneEp])}
              />
            ))
          )}
        </div>
      ) : null}

      <DownloadQualitySheet
        open={!!downloadTarget}
        onClose={() => setDownloadTarget(null)}
        type="series"
        imdbId={group.showId}
        showName={group.showName}
        showPoster={group.showPoster}
        episodes={downloadTarget || []}
      />
    </div>
  )
}

// FEATURE (user ask): while offline, Home redirects here and its tab shows
// locked — this banner is the "bolna chahiye internet on karo" part, so it's
// clear on the Downloads screen itself *why* you landed here / when Home
// will open back up.
function OfflineBanner() {
  const { t } = useLanguage()
  return (
    <div className="mb-4 flex items-center gap-2.5 rounded-xl bg-reel-surface2 ring-1 ring-reel-gold/20 px-3.5 py-2.5">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-reel-gold shrink-0">
        <line x1="2" y1="2" x2="22" y2="22" />
        <path d="M8.5 16.5a5 5 0 0 1 7 0" />
        <path d="M5 12.9a10 10 0 0 1 3.5-2.4" />
        <path d="M19 12.9a10 10 0 0 0 -2.2-1.8" />
        <path d="M1.5 8.5A16 16 0 0 1 6 5.5" />
        <path d="M22.5 8.5a16 16 0 0 0 -6-3.4" />
        <line x1="12" y1="20" x2="12.01" y2="20" />
      </svg>
      <p className="text-xs text-reel-muted">
        {t('dl_offline_banner')}
      </p>
    </div>
  )
}

export default function Downloads() {
  const { t } = useLanguage()
  const list = useDownloadsList()
  const [playing, setPlaying] = useState(null)
  const isOnline = useOnlineStatus()

  const groups = groupDownloads(list)

  if (!groups.length) {
    return (
      <div className="max-w-6xl mx-auto py-16 px-4 text-center">
        {!isOnline ? <OfflineBanner /> : null}
        <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-reel-surface2 flex items-center justify-center text-reel-muted">
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" /></svg>
        </div>
        <p className="text-reel-ink font-medium">{t('dl_empty_title')}</p>
        <p className="text-reel-muted text-sm mt-1">
          {t('dl_empty_sub')}
        </p>
      </div>
    )
  }

  return (
    <div className="max-w-6xl mx-auto py-6 px-4 sm:px-6">
      {!isOnline ? <OfflineBanner /> : null}
      <h1 className="font-display text-2xl text-reel-ink mb-4">{t('nav_downloads')}</h1>
      <div className="space-y-3">
        {groups.map((g) =>
          g.type === 'series' ? (
            <SeasonCard key={g.key} group={g} onPlay={setPlaying} />
          ) : (
            <MovieRow key={g.key} d={g.entries[0]} onPlay={setPlaying} />
          )
        )}
      </div>

      {playing ? <OfflinePlayer entry={playing} onClose={() => setPlaying(null)} /> : null}
    </div>
  )
}
