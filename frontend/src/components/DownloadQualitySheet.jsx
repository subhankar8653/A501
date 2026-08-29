import { useEffect, useState } from 'react'
import { getStreams, qualityLabel } from '../api'
import { startDownload, downloadId, useDownloadsList } from '../lib/downloadsStore'
import { useLanguage } from '../i18n/LanguageContext'

// Turns a quality label ("360p", "1080p", "4K"...) into a comparable number,
// so we can sort qualities and find "the next one down" from what the user
// picked. Non-numeric labels (odd stream names) sort last.
function resolutionOf(label) {
  if (!label) return null
  if (/^4k$/i.test(label)) return 2160
  const m = String(label).match(/(\d+)/)
  return m ? Number(m[1]) : null
}

// ROOT CAUSE FIX (user report: "batch mein 480p 720p 1080p dikhaya, but E1-E2
// hi 480p mein the, E3-E12 sirf 360p mein — un episodes ka download hi shuru
// nahi hota tha kyunki `list.find(...) || list[0]` kabhi bhi list ka pehla
// wala (jo highest quality bhi ho sakta hai) utha leta tha, next-LOWER
// quality nahi dhoondta tha"): agar is episode ke paas exact picked quality
// nahi hai, to sabse najdeeki quality jo picked se KAM (ya barabar) ho use
// karo — bilkul jaisa online streaming players (YouTube/Netflix) karte hain.
// Sirf tabhi upar wali quality le jab is episode ke paas bilkul bhi kam
// quality na ho.
function pickBestStream(streams, pickedLabel) {
  if (!streams.length) return null
  const pickedRes = resolutionOf(pickedLabel)
  const withRes = streams
    .map((s) => ({ stream: s, res: resolutionOf(qualityLabel(s)) }))
    .filter((x) => x.res != null)
  if (pickedRes == null || !withRes.length) return streams[0]

  const exact = withRes.find((x) => x.res === pickedRes)
  if (exact) return exact.stream

  const lowerOrEqual = withRes.filter((x) => x.res <= pickedRes).sort((a, b) => b.res - a.res)
  if (lowerOrEqual.length) return lowerOrEqual[0].stream

  // Is episode ke paas picked se kam koi quality hi nahi — majboori mein
  // sabse kareebi (sabse chhoti) available quality le lo.
  const higher = withRes.slice().sort((a, b) => a.res - b.res)
  return higher[0].stream
}

// Bottom sheet used for both:
//  - single-episode download (⋮ menu on one row in Detail.jsx)
//  - whole-season batch download (the "Download Season" button in Detail.jsx)
//
// Flow: peeks EVERY episode's stream list to build the quality-label choices
// (360p/480p/720p/1080p/2160p...) — not just episode 1's, so a quality like
// 4K that only some episodes have still shows up as an option — user picks
// one, then confirmDownload() reuses those same per-episode stream lists to
// grab the URL that best matches that label for THAT episode (falling back
// to the next lower quality if this episode doesn't have the picked one),
// and each one is handed off to the existing downloadsStore.
export default function DownloadQualitySheet({ open, onClose, type, imdbId, showName, showPoster, episodes }) {
  const { t } = useLanguage()
  const [labels, setLabels] = useState(null) // null = loading, [] = none found
  const [picked, setPicked] = useState(null)
  const [queueing, setQueueing] = useState(false)
  const [done, setDone] = useState(0)
  const [failed, setFailed] = useState(0)
  const [episodeStreams, setEpisodeStreams] = useState(null) // Map(episode.id -> streams[])
  const downloadsList = useDownloadsList()

  const isSeason = episodes.length > 1

  useEffect(() => {
    if (!open) return
    setLabels(null)
    setPicked(null)
    setQueueing(false)
    setDone(0)
    setFailed(0)
    setEpisodeStreams(null)
    if (!episodes.length) {
      setLabels([])
      return
    }
    let cancelled = false
    Promise.all(episodes.map((ep) => getStreams(type, ep.id).catch(() => [])))
      .then((allStreams) => {
        if (cancelled) return
        const streamMap = new Map()
        const seen = new Map() // label -> resolution, for sorting
        episodes.forEach((ep, i) => {
          streamMap.set(ep.id, allStreams[i] || [])
          for (const s of allStreams[i] || []) {
            const label = qualityLabel(s)
            if (!seen.has(label)) seen.set(label, resolutionOf(label))
          }
        })
        const out = [...seen.keys()].sort((a, b) => {
          const ra = seen.get(a)
          const rb = seen.get(b)
          if (ra == null && rb == null) return 0
          if (ra == null) return 1
          if (rb == null) return -1
          return rb - ra
        })
        setEpisodeStreams(streamMap)
        setLabels(out)
        if (out.length) setPicked(out[0])
      })
      .catch(() => !cancelled && setLabels([]))
    return () => {
      cancelled = true
    }
  }, [open, episodes, type])

  if (!open) return null

  async function confirmDownload() {
    if (!picked) return
    setQueueing(true)
    setDone(0)
    setFailed(0)
    for (const ep of episodes) {
      try {
        const list = episodeStreams?.get(ep.id) || (await getStreams(type, ep.id))
        const match = pickBestStream(list, picked)
        if (!match) {
          setFailed((f) => f + 1)
          continue
        }
        const id = downloadId(type, ep.id, qualityLabel(match))
        const already = downloadsList.find((d) => d.id === id)
        if (already && (already.status === 'downloading' || already.status === 'done' || already.status === 'queued')) {
          setDone((n) => n + 1)
          continue
        }
        // Fire-and-forget: startDownload manages its own progress (and its
        // own one-at-a-time queue) in the store, Downloads tab will show
        // every episode ticking up on its own.
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
    // ROOT CAUSE FIX (user report: "download button niche navigation bar ke
    // neeche chala ja raha hai"): panel ka bottom padding pehle fixed `pb-6`
    // tha, device ke on-screen nav bar (safe-area) ke liye extra jagah nahi
    // chhodta tha, isliye "Download · <quality>" CTA button us bar se overlap
    // ho jaata. Baaki app mein (BottomNav, DownloadToast) hamesha
    // env(safe-area-inset-bottom) add kiya jaata hai — yahan bhi wahi.
    <div className="fixed inset-0 z-[95] flex items-end justify-center" onClick={onClose}>
      <div className="absolute inset-0 bg-black/60" />
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-md bg-reel-bg rounded-t-2xl pt-3 pb-[calc(1.5rem+env(safe-area-inset-bottom))] px-5 ring-1 ring-white/10 shadow-[0_-8px_32px_rgba(0,0,0,0.7)] max-h-[80vh] overflow-y-auto"
      >
        <div className="w-10 h-1 rounded-full bg-white/15 mx-auto mb-4" />

        <p className="text-reel-ink font-semibold mb-1">
          {isSeason
            ? `${t('download')} ${t('season')} (${episodes.length} ${t('episodes')})`
            : episodes[0]?.episode != null
              ? `${t('download')} E${episodes[0].episode}`
              : `${t('download')} ${showName || ''}`}
        </p>
        <p className="text-reel-muted text-xs mb-4">
          {isSeason ? t('dl_sheet_season_sub') : t('dl_sheet_single_sub')}
        </p>

        {labels === null ? (
          <div className="py-6 flex justify-center">
            <span className="w-6 h-6 border-2 border-reel-muted/30 border-t-reel-gold rounded-full animate-spin" />
          </div>
        ) : labels.length === 0 ? (
          <p className="text-reel-rust text-sm py-4 text-center">{t('dl_sheet_no_stream')}</p>
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
              {isSeason ? `${t('dl_sheet_download_all')} ${episodes.length} ${t('episodes')} · ${picked || ''}` : `${t('download')} · ${picked || ''}`}
            </button>
          </>
        ) : (
          <div className="py-4 text-center">
            <p className="text-reel-ink text-sm mb-2">
              {done + failed} / {episodes.length} {t('dl_sheet_queued')}
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
                  {done} {t('dl_sheet_started')}{failed ? `, ${failed} ${t('dl_sheet_some_failed')}` : ''} — {t('dl_sheet_check_downloads')}
                </p>
                <button
                  onClick={onClose}
                  className="w-full py-2.5 rounded-xl bg-white/10 text-reel-ink font-semibold active:scale-[0.98] transition"
                >
                  {t('done')}
                </button>
              </>
            ) : (
              <p className="text-reel-muted text-xs">{t('dl_sheet_queueing')}</p>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
