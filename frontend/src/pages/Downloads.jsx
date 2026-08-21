import { useEffect, useState } from 'react'
import { getMeta } from '../api'
import {
  useDownloadsList,
  cancelDownload,
  deleteDownload,
  getDownloadBlobUrl,
  groupDownloads,
} from '../lib/downloadsStore'
import DownloadQualitySheet from '../components/DownloadQualitySheet'
import VideoPlayer from '../components/VideoPlayer'

function formatSize(bytes) {
  if (!bytes) return ''
  const mb = bytes / (1024 * 1024)
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

// Uses the exact same VideoPlayer component as online playback (custom
// gestures, skip, speed, PiP, fullscreen — same "structure" as the Player
// page) instead of a bare <video> tag. That bare tag was the actual cause
// of the black screen: it was missing `playsInline`, which made Android
// WebView try to hand it off to a native fullscreen surface our app never
// wired up for generic HTML5 video — see the comment above isBlobSrc() in
// VideoPlayer.jsx for the full root-cause writeup.
function OfflinePlayer({ entry, onClose }) {
  const [url, setUrl] = useState(null)
  const [err, setErr] = useState(false)

  useEffect(() => {
    let cancelled = false
    let objectUrl = null
    getDownloadBlobUrl(entry.id)
      .then((u) => {
        if (cancelled) return
        if (!u) return setErr(true)
        objectUrl = u
        setUrl(u)
      })
      .catch(() => !cancelled && setErr(true))
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [entry.id])

  return (
    <div className="fixed inset-0 z-50 bg-black flex flex-col">
      <button
        onClick={onClose}
        aria-label="Close"
        className="absolute top-3 right-3 z-[85] w-9 h-9 rounded-full bg-black/55 backdrop-blur-sm flex items-center justify-center text-reel-ink active:scale-90 transition"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></svg>
      </button>
      <div className="flex-1 min-h-0">
        {err ? (
          <div className="w-full h-full flex items-center justify-center px-6">
            <p className="text-reel-muted text-sm text-center">Yeh download offline play nahi ho paa raha.</p>
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
  )
}

// One movie's download row (movies don't have seasons/episodes) — unchanged
// from before, Play/Cancel/Delete.
function MovieRow({ d, onPlay }) {
  return (
    <div className="flex items-center gap-3 bg-reel-surface rounded-lg p-3 ring-1 ring-white/5">
      <div className="w-16 aspect-[2/3] rounded-md overflow-hidden bg-reel-surface2 shrink-0">
        {d.poster ? <img src={d.poster} alt="" className="w-full h-full object-cover" /> : null}
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-reel-ink line-clamp-1">{d.filename}</p>
        {d.status === 'downloading' ? (
          <>
            <div className="h-1.5 mt-2 rounded-full bg-reel-surface2 overflow-hidden">
              <div className="h-full bg-reel-gold transition-all" style={{ width: `${d.progress || 0}%` }} />
            </div>
            <p className="text-xs text-reel-muted mt-1">{d.progress || 0}% · Downloading…</p>
          </>
        ) : d.status === 'error' ? (
          <p className="text-xs text-reel-rust mt-1">Download fail ho gayi</p>
        ) : (
          <p className="text-xs text-reel-muted mt-1">
            {d.qualityLabel ? `${d.qualityLabel} · ` : ''}
            {formatSize(d.sizeBytes)} · Offline available
          </p>
        )}
      </div>
      <div className="flex items-center gap-2 shrink-0">
        {d.status === 'done' ? (
          <button
            onClick={() => onPlay(d)}
            className="px-3 py-1.5 rounded-full text-xs bg-reel-gold text-reel-bg font-semibold active:scale-95 transition"
          >
            Play
          </button>
        ) : null}
        {d.status === 'downloading' ? (
          <button
            onClick={() => cancelDownload(d.id)}
            className="px-3 py-1.5 rounded-full text-xs bg-reel-surface2 text-reel-muted active:scale-95 transition"
          >
            Cancel
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
  const [menuOpen, setMenuOpen] = useState(false)
  const isDownloaded = entry?.status === 'done'
  const isDownloading = entry?.status === 'downloading'
  const hasAny = !!entry

  return (
    <div
      className={`relative flex items-center gap-3 rounded-lg p-2.5 ring-1 ring-white/5 transition ${
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
        {isDownloading ? (
          <>
            <div className="h-1.5 mt-2 rounded-full bg-reel-surface2 overflow-hidden">
              <div className="h-full bg-reel-gold transition-all" style={{ width: `${entry.progress || 0}%` }} />
            </div>
            <p className="text-xs text-reel-muted mt-1">{entry.progress || 0}% · Downloading…</p>
          </>
        ) : isDownloaded ? (
          <p className="text-xs text-reel-muted mt-1">
            {entry.qualityLabel ? `${entry.qualityLabel} · ` : ''}
            {formatSize(entry.sizeBytes)} · Offline available
          </p>
        ) : entry?.status === 'error' ? (
          <p className="text-xs text-reel-rust mt-1">Download fail ho gayi</p>
        ) : (
          <p className="text-xs text-reel-muted mt-1">Download nahi hui</p>
        )}
      </div>

      <div className="flex items-center gap-1.5 shrink-0">
        {isDownloaded ? (
          <button
            onClick={() => onPlay(entry)}
            className="px-3 py-1.5 rounded-full text-xs bg-reel-gold text-reel-bg font-semibold active:scale-95 transition"
          >
            Play
          </button>
        ) : null}
        {isDownloading ? (
          <button
            onClick={() => cancelDownload(entry.id)}
            className="px-3 py-1.5 rounded-full text-xs bg-reel-surface2 text-reel-muted active:scale-95 transition"
          >
            Cancel
          </button>
        ) : null}

        <div className="relative">
          <button
            onClick={() => setMenuOpen((m) => !m)}
            aria-label="Episode options"
            className="w-8 h-8 rounded-full flex items-center justify-center text-reel-muted hover:bg-white/10 active:scale-90 transition"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
          </button>
          {menuOpen ? (
            <div className="absolute top-full right-0 mt-1 min-w-[150px] rounded-xl overflow-hidden bg-reel-bg/97 backdrop-blur-md ring-1 ring-white/10 shadow-[0_8px_28px_rgba(0,0,0,0.65)] z-10">
              {!isDownloaded && !isDownloading ? (
                <button
                  onClick={() => {
                    setMenuOpen(false)
                    onDownloadOne(ep)
                  }}
                  className="w-full flex items-center gap-2 px-3.5 py-3 text-xs text-reel-ink hover:bg-white/5"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" /></svg>
                  Download
                </button>
              ) : (
                <button
                  onClick={() => {
                    setMenuOpen(false)
                    deleteDownload(entry.id)
                  }}
                  className="w-full flex items-center gap-2 px-3.5 py-3 text-xs text-reel-rust hover:bg-white/5"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"><polyline points="3 6 5 6 21 6" /><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" /><path d="M10 11v6" /><path d="M14 11v6" /></svg>
                  Remove
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
  const [expanded, setExpanded] = useState(false)
  const [allEpisodes, setAllEpisodes] = useState(null) // null = not loaded yet
  const [downloadTarget, setDownloadTarget] = useState(null)

  const doneCount = group.entries.filter((d) => d.status === 'done').length

  useEffect(() => {
    if (!expanded || allEpisodes) return
    let cancelled = false
    getMeta('series', group.showId)
      .then((m) => {
        if (cancelled) return
        const eps = (m?.videos || []).filter((v) => v.season === group.season)
        setAllEpisodes(eps)
      })
      .catch(() => !cancelled && setAllEpisodes([]))
    return () => {
      cancelled = true
    }
  }, [expanded, allEpisodes, group.showId, group.season])

  return (
    <div className="rounded-lg overflow-hidden bg-reel-surface ring-1 ring-white/5">
      <div className="w-full flex items-center gap-3 p-3">
        <button onClick={() => setExpanded((e) => !e)} className="flex items-center gap-3 text-left flex-1 min-w-0 active:scale-[0.99] transition">
          <div className="w-16 aspect-[2/3] rounded-md overflow-hidden bg-reel-surface2 shrink-0">
            {group.showPoster ? <img src={group.showPoster} alt="" className="w-full h-full object-cover" /> : null}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium text-reel-ink line-clamp-1">{group.showName}</p>
            <p className="text-xs text-reel-muted mt-1">
              {group.season != null && group.season !== 0 ? `Season ${group.season} · ` : ''}
              {doneCount}/{allEpisodes ? allEpisodes.length : group.entries.length} episode{group.entries.length > 1 ? 's' : ''} downloaded
            </p>
          </div>
        </button>

        <button
          onClick={() => allEpisodes && setDownloadTarget(allEpisodes)}
          disabled={!allEpisodes}
          className="shrink-0 flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-sm font-semibold bg-white/10 text-reel-ink active:scale-95 transition disabled:opacity-40"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" /></svg>
          Season
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
        <div className="px-3 pb-3 space-y-2 border-t border-white/5 pt-3">
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

export default function Downloads() {
  const list = useDownloadsList()
  const [playing, setPlaying] = useState(null)

  const groups = groupDownloads(list)

  if (!groups.length) {
    return (
      <div className="max-w-6xl mx-auto py-16 px-4 text-center">
        <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-reel-surface2 flex items-center justify-center text-reel-muted">
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" /></svg>
        </div>
        <p className="text-reel-ink font-medium">Koi download nahi hai</p>
        <p className="text-reel-muted text-sm mt-1">
          Kisi bhi title ke Detail ya Player screen par Download dabaakar yahan add karo.
        </p>
      </div>
    )
  }

  return (
    <div className="max-w-6xl mx-auto py-6 px-4 sm:px-6">
      <h1 className="font-display text-2xl text-reel-ink mb-4">Downloads</h1>
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
