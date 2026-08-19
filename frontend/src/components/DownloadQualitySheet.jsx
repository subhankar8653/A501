import { useEffect, useState } from 'react'
import { getStreams, qualityLabel } from '../api'
import { startDownload, downloadId, useDownloadsList } from '../lib/downloadsStore'

// Bottom sheet used for both:
//  - single-episode download (⋮ menu on one row in Detail.jsx)
//  - whole-season batch download (the "Download Season" button in Detail.jsx)
//
// Flow: peeks the first episode's stream list to build the quality-label
// choices (360p/480p/720p/1080p...), user picks one, then every episode in
// `episodes` gets its own getStreams() call so we grab the URL that actually
// matches that label for THAT episode (not just episode 1's URL), and each
// one is hand off to the existing downloadsStore.
export default function DownloadQualitySheet({ open, onClose, type, imdbId, showName, showPoster, episodes }) {
  const [labels, setLabels] = useState(null) // null = loading, [] = none found
  const [picked, setPicked] = useState(null)
  const [queueing, setQueueing] = useState(false)
  const [done, setDone] = useState(0)
  const [failed, setFailed] = useState(0)
  const downloadsList = useDownloadsList()

  const isSeason = episodes.length > 1

  useEffect(() => {
    if (!open) return
    setLabels(null)
    setPicked(null)
    setQueueing(false)
    setDone(0)
    setFailed(0)
    const first = episodes[0]
    if (!first) {
      setLabels([])
      return
    }
    getStreams(type, first.id)
      .then((streams) => {
        const seen = new Set()
        const out = []
        for (const s of streams) {
          const label = qualityLabel(s)
          if (seen.has(label)) continue
          seen.add(label)
          out.push(label)
        }
        setLabels(out)
        if (out.length) setPicked(out[0])
      })
      .catch(() => setLabels([]))
  }, [open, episodes, type])

  if (!open) return null

  async function confirmDownload() {
    if (!picked) return
    setQueueing(true)
    setDone(0)
    setFailed(0)
    for (const ep of episodes) {
      try {
        const list = await getStreams(type, ep.id)
        const match = list.find((s) => qualityLabel(s) === picked) || list[0]
        if (!match) {
          setFailed((f) => f + 1)
          continue
        }
        const id = downloadId(type, ep.id, qualityLabel(match))
        const already = downloadsList.find((d) => d.id === id)
        if (already && (already.status === 'downloading' || already.status === 'done')) {
          setDone((n) => n + 1)
          continue
        }
        // Fire-and-forget: startDownload manages its own progress in the
        // store, Downloads tab will show every episode ticking up on its own.
        startDownload(match.url, {
          type,
          titleId: ep.id,
          showId: imdbId,
          showName,
          showPoster,
          season: ep.season,
          episode: ep.episode,
          episodeTitle: ep.title,
          filename: ep.filename || `${showName} E${ep.episode}`,
          poster: showPoster,
          qualityLabel: qualityLabel(match),
        })
        setDone((n) => n + 1)
      } catch {
        setFailed((f) => f + 1)
      }
    }
  }

  return (
    <div className="fixed inset-0 z-[95] flex items-end justify-center" onClick={onClose}>
      <div className="absolute inset-0 bg-black/60" />
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-md bg-reel-bg rounded-t-2xl pt-3 pb-6 px-5 ring-1 ring-white/10 shadow-[0_-8px_32px_rgba(0,0,0,0.7)] max-h-[80vh] overflow-y-auto"
      >
        <div className="w-10 h-1 rounded-full bg-white/15 mx-auto mb-4" />

        <p className="text-reel-ink font-semibold mb-1">
          {isSeason ? `Download Season (${episodes.length} episodes)` : `Download E${episodes[0]?.episode}`}
        </p>
        <p className="text-reel-muted text-xs mb-4">
          {isSeason ? 'Quality chuno, poora season ek baar mein download hoga.' : 'Is episode ke liye quality chuno.'}
        </p>

        {labels === null ? (
          <div className="py-6 flex justify-center">
            <span className="w-6 h-6 border-2 border-reel-muted/30 border-t-reel-gold rounded-full animate-spin" />
          </div>
        ) : labels.length === 0 ? (
          <p className="text-reel-rust text-sm py-4 text-center">Koi stream nahi mili.</p>
        ) : !queueing ? (
          <>
            <div className="flex flex-wrap gap-2 mb-5">
              {labels.map((l) => (
                <button
                  key={l}
                  onClick={() => setPicked(l)}
                  className={`px-4 py-2 rounded-full text-sm font-semibold transition ${
                    picked === l ? 'bg-reel-gold text-reel-bg' : 'bg-white/10 text-reel-ink'
                  }`}
                >
                  {l}
                </button>
              ))}
            </div>
            <button
              onClick={confirmDownload}
              disabled={!picked}
              className="w-full py-3 rounded-xl bg-reel-gold text-reel-bg font-semibold active:scale-[0.98] transition disabled:opacity-50"
            >
              {isSeason ? `Download all ${episodes.length} episodes · ${picked || ''}` : `Download · ${picked || ''}`}
            </button>
          </>
        ) : (
          <div className="py-4 text-center">
            <p className="text-reel-ink text-sm mb-2">
              {done + failed} / {episodes.length} queued…
            </p>
            <div className="h-1.5 rounded-full bg-reel-surface2 overflow-hidden mb-3">
              <div
                className="h-full bg-reel-gold transition-all"
                style={{ width: `${((done + failed) / episodes.length) * 100}%` }}
              />
            </div>
            {done + failed >= episodes.length ? (
              <>
                <p className="text-reel-muted text-xs mb-4">
                  {done} shuru ho gaye{failed ? `, ${failed} fail ho gaye` : ''} — progress Downloads tab mein dekho.
                </p>
                <button
                  onClick={onClose}
                  className="w-full py-2.5 rounded-xl bg-white/10 text-reel-ink font-semibold active:scale-[0.98] transition"
                >
                  Done
                </button>
              </>
            ) : (
              <p className="text-reel-muted text-xs">Episodes queue ho rahe hain, ruko…</p>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
