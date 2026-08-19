import { useEffect, useState } from 'react'
import { useDownloadsList, cancelDownload, deleteDownload, getDownloadBlobUrl, groupDownloads } from '../lib/downloadsStore'

function formatSize(bytes) {
  if (!bytes) return ''
  const mb = bytes / (1024 * 1024)
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

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
      <div className="flex items-center justify-between px-4 py-3">
        <p className="text-reel-ink text-sm truncate pr-3">{entry.filename}</p>
        <button
          onClick={onClose}
          aria-label="Close"
          className="w-9 h-9 rounded-full bg-reel-surface2 flex items-center justify-center text-reel-ink active:scale-90 shrink-0"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></svg>
        </button>
      </div>
      <div className="flex-1 flex items-center justify-center px-2">
        {err ? (
          <p className="text-reel-muted text-sm px-6 text-center">Yeh download offline play nahi ho paa raha.</p>
        ) : url ? (
          <video src={url} controls autoPlay className="w-full max-h-full" />
        ) : (
          <span className="w-8 h-8 border-2 border-reel-muted/30 border-t-reel-gold rounded-full animate-spin" />
        )}
      </div>
    </div>
  )
}

// One downloaded episode/file row — Play/Cancel/Delete controls, same as
// the earlier flat list, just reused inside both movie cards and expanded
// season episode lists.
function DownloadRow({ d, onPlay }) {
  return (
    <div className="flex items-center gap-3 bg-reel-surface rounded-lg p-3 ring-1 ring-white/5">
      <div className="w-14 aspect-video rounded-md overflow-hidden bg-reel-surface2 shrink-0">
        {d.poster ? <img src={d.poster} alt="" className="w-full h-full object-cover" /> : null}
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-reel-ink line-clamp-1">
          {d.season != null && d.episode != null ? `E${d.episode} · ` : ''}
          {d.episodeTitle || d.filename}
        </p>
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

// A season's cover card — poster + name + "N episodes downloaded", tap to
// expand into the full episode-by-episode list (same line-by-line layout as
// the show's own Detail page), each row still keeping its own
// Play/Cancel/Delete controls.
function SeasonCard({ group, onPlay }) {
  const [expanded, setExpanded] = useState(false)
  const sortedEntries = [...group.entries].sort((a, b) => (a.episode || 0) - (b.episode || 0))
  const doneCount = group.entries.filter((d) => d.status === 'done').length

  return (
    <div className="rounded-lg overflow-hidden bg-reel-surface ring-1 ring-white/5">
      <button
        onClick={() => setExpanded((e) => !e)}
        className="w-full flex items-center gap-3 p-3 text-left active:scale-[0.99] transition"
      >
        <div className="w-16 aspect-[2/3] rounded-md overflow-hidden bg-reel-surface2 shrink-0">
          {group.showPoster ? <img src={group.showPoster} alt="" className="w-full h-full object-cover" /> : null}
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-reel-ink line-clamp-1">{group.showName}</p>
          <p className="text-xs text-reel-muted mt-1">
            {group.season != null && group.season !== 0 ? `Season ${group.season} · ` : ''}
            {doneCount}/{group.entries.length} episode{group.entries.length > 1 ? 's' : ''} downloaded
          </p>
        </div>
        <svg
          width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"
          className={`shrink-0 text-reel-muted transition-transform ${expanded ? 'rotate-180' : ''}`}
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {expanded ? (
        <div className="px-3 pb-3 space-y-2 border-t border-white/5 pt-3">
          {sortedEntries.map((d) => (
            <DownloadRow key={d.id} d={d} onPlay={onPlay} />
          ))}
        </div>
      ) : null}
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
            <DownloadRow key={g.key} d={g.entries[0]} onPlay={setPlaying} />
          )
        )}
      </div>

      {playing ? <OfflinePlayer entry={playing} onClose={() => setPlaying(null)} /> : null}
    </div>
  )
}
