import { useEffect, useMemo, useState } from 'react'
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
// FEATURE (user ask: "agar koi download karne jaaye point khatm hone ke
// baad to udhar quality ki jagah, jaise stream screen mein premium lene ke
// liye button diya tha, vaise hi udhar button de dena aur baaki jo batana
// hai jaise us stream mein bataya"): jab backend ke paas dene ke liye koi
// asli stream nahi hota (aaj ka free-trial point khatam / plan expired /
// channel join required / daily-monthly limit), to `getStreams()` ek
// single fake "stream" bhejta hai jiska sirf `block_reason` (+ premium/
// bot ka `url`) hota hai — dekho Player.jsx ka `isBlocked`/`lockedInfo` aur
// backend/Backend/fastapi/routes/stremio_routes.py. Pehle yahan is fake
// stream ko bhi ek normal "quality" (jaise "🚫 Aaj Ka Point Khatam") maan
// kar list mein daal diya jaata tha — usko select karke "Download" dabane
// par who premium/bot link hi ek "video" ke roop mein download hone lagta
// tha. Ab is fake stream ko turant pehchan kar, uski jagah Player.jsx wala
// hi locked-screen (subscribe premium button + wahi i18n text) dikhate
// hain, taaki behavior stream screen jaisa hi rahe.
function blockReasonOf(streams) {
  if (!streams || !streams.length) return null
  const blocked = streams.find((s) => s?.block_reason)
  return blocked || null
}

export default function DownloadQualitySheet({ open, onClose, type, imdbId, showName, showPoster, episodes }) {
  const { t } = useLanguage()
  const [labels, setLabels] = useState(null) // null = loading, [] = none found
  const [picked, setPicked] = useState(null)
  const [queueing, setQueueing] = useState(false)
  const [done, setDone] = useState(0)
  const [failed, setFailed] = useState(0)
  const [episodeStreams, setEpisodeStreams] = useState(null) // Map(episode.id -> streams[])
  const [lockedStream, setLockedStream] = useState(null) // the fake block_reason "stream", if this title/season is locked
  const downloadsList = useDownloadsList()

  const isSeason = episodes.length > 1

  const lockedInfo = useMemo(() => {
    if (!lockedStream?.block_reason) return null
    switch (lockedStream.block_reason) {
      case 'trial_exhausted':
        return {
          title: t('player_locked_trial_title'),
          body: t('player_locked_trial_body'),
          note: t('player_locked_trial_note'),
        }
      case 'plan_expired':
        return { title: t('player_locked_expired_title'), body: t('player_locked_expired_body'), note: '' }
      case 'join_required':
        return { title: t('player_locked_join_title'), body: t('player_locked_join_body'), note: '' }
      case 'limit_daily':
      case 'limit_monthly':
        return { title: t('player_locked_limit_title'), body: t('player_locked_limit_body'), note: '' }
      default:
        return { title: lockedStream.name || '', body: lockedStream.title || '', note: '' }
    }
  }, [lockedStream, t])

  // Same "open Telegram directly" pattern as Player.jsx's openSubscribeLink.
  function openSubscribeLink() {
    if (!lockedStream?.url) return
    window.open(lockedStream.url, '_blank', 'noopener,noreferrer')
  }

  useEffect(() => {
    if (!open) return
    setLabels(null)
    setPicked(null)
    setQueueing(false)
    setDone(0)
    setFailed(0)
    setEpisodeStreams(null)
    setLockedStream(null)
    if (!episodes.length) {
      setLabels([])
      return
    }
    let cancelled = false
    Promise.all(episodes.map((ep) => getStreams(type, ep.id).catch(() => [])))
      .then((allStreams) => {
        if (cancelled) return
        // Agar HAR episode ke paas sirf blocked/fake stream hai (koi bhi
        // episode ki koi asli playable quality nahi), to poora sheet hi
        // locked-screen dikhayega — jaise season-batch download ho ya
        // single-episode, jab tak kam se kam ek episode mein real quality
        // hai tab tak wahi dikhate rahenge (baaki blocked episodes
        // download-time par apne aap skip/fail ho jaayenge).
        const blockedPerEpisode = allStreams.map((s) => blockReasonOf(s))
        const anyRealStream = allStreams.some(
          (s, i) => (s || []).length && !(blockedPerEpisode[i] && (s || []).length === 1)
        )
        if (!anyRealStream) {
          const firstBlocked = blockedPerEpisode.find(Boolean)
          if (firstBlocked) {
            setLockedStream(firstBlocked)
            setLabels([])
            return
          }
        }
        const streamMap = new Map()
        const seen = new Map() // label -> resolution, for sorting
        episodes.forEach((ep, i) => {
          const streams = allStreams[i] || []
          // Fake block_reason entries are never a real selectable quality —
          // drop them here so they can never end up as a "Download · <label>"
          // choice that actually downloads a premium/bot link.
          const realStreams = streams.filter((s) => !s?.block_reason)
          streamMap.set(ep.id, realStreams)
          for (const s of realStreams) {
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
        className="relative w-full max-w-md bg-reel-bg rounded-t-2xl pt-3 pb-[calc(1.5rem+env(safe-area-inset-bottom))] px-5 ring-1 ring-reel-ink/10 shadow-[0_-8px_32px_rgba(0,0,0,0.7)] max-h-[80vh] overflow-y-auto"
      >
        <div className="w-10 h-1 rounded-full bg-reel-ink/15 mx-auto mb-4" />

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
        ) : lockedInfo ? (
          // FEATURE: same locked-screen as the stream/player screen —
          // quality buttons ki jagah seedha "Subscribe Premium Plan"
          // button, jo Telegram khol deta hai.
          <div className="flex flex-col items-center gap-3 py-4 text-center">
            <div className="w-11 h-11 rounded-full bg-reel-surface2/80 flex items-center justify-center">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-reel-gold">
                <rect x="5" y="11" width="14" height="9" rx="2" />
                <path d="M8 11V7a4 4 0 0 1 8 0v4" />
              </svg>
            </div>
            <div className="space-y-1">
              <p className="text-reel-ink font-semibold text-[15px]">{lockedInfo.title}</p>
              {lockedInfo.body ? <p className="text-reel-ink/60 text-[12px]">{lockedInfo.body}</p> : null}
            </div>
            <button
              onClick={openSubscribeLink}
              className="px-6 py-2 rounded-full bg-reel-gold text-reel-bg text-sm font-semibold active:scale-95 transition"
            >
              {t('player_locked_subscribe_cta')}
            </button>
            {lockedInfo.note ? <p className="text-[10px] text-reel-ink/40">{lockedInfo.note}</p> : null}
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
                    picked === l ? 'bg-reel-gold text-reel-bg' : 'bg-reel-ink/10 text-reel-ink'
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
                  className="w-full py-2.5 rounded-xl bg-reel-ink/10 text-reel-ink font-semibold active:scale-[0.98] transition"
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
