import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getMeta, isVerified, getRelatedTitles } from '../api'
import BackButton from '../components/BackButton'
import DownloadQualitySheet from '../components/DownloadQualitySheet'
import VerifyGate from '../components/VerifyGate'
import Rail from '../components/Rail'
import { useLanguage } from '../i18n/LanguageContext'
import { useIsSaved, toggleSaved } from '../lib/savedStore'

export default function Detail() {
  const { type, id } = useParams()
  const navigate = useNavigate()
  const [meta, setMeta] = useState(null)
  const [season, setSeason] = useState(null)
  const [error, setError] = useState('')
  const [retryKey, setRetryKey] = useState(0)
  // ⋮ menu open on a single episode row, and the download sheet's target
  // (either one episode, or the whole current season).
  const [epMenuOpen, setEpMenuOpen] = useState(null) // episode id | null
  const [downloadTarget, setDownloadTarget] = useState(null) // [episode] | allEpisodesInSeason | null
  const [seasonPickerOpen, setSeasonPickerOpen] = useState(false) // top-level download button's season-choose step
  const [descExpanded, setDescExpanded] = useState(false)
  const verified = isVerified()
  const { t } = useLanguage()
  // Episode ⋮ menu ka "Save" — jaisa Player.jsx mein, saved poore title
  // (show/movie) ke against hota hai, episode ke against nahi, taaki kisi
  // bhi episode se save karo, Saved tab mein show ek hi baar dikhe.
  const saved = useIsSaved(type, id)
  const [menuToast, setMenuToast] = useState('')
  function shareEpisode(ep) {
    const url = `${window.location.origin}/watch/${type}/${encodeURIComponent(ep.id)}`
    if (navigator.share) {
      navigator.share({ title: ep.title || meta?.name, url }).catch(() => {})
    } else {
      navigator.clipboard
        ?.writeText(url)
        .then(() => {
          setMenuToast(t('player_link_copied'))
          setTimeout(() => setMenuToast(''), 1800)
        })
        .catch(() => {})
    }
  }
  // FEATURE (user ask: "Related/Recommended videos"): current title ka
  // pehla genre milte hi fetch karo — koi naya backend endpoint nahi laga,
  // existing catalog genre-filter reuse kiya hai (dekho api.js
  // getRelatedTitles).
  const [related, setRelated] = useState(null)

  // UX FIX ("clean/fast" pass): pehle episode ka ⋮ menu sirf usi button ko
  // dobara dabane ya "Download" choose karne se band hota tha — kahin bhi
  // aur (episode list, screen ka khaali hissa) tap karne par khula hi reh
  // jaata tha, jaise ek "stuck" overlay. Ab standard mobile-menu behavior:
  // menu ke bahar kahin bhi tap karo, apne aap band ho jaata hai.
  const epMenuRef = useRef(null)
  useEffect(() => {
    if (!epMenuOpen) return
    function onOutside(e) {
      if (epMenuRef.current && !epMenuRef.current.contains(e.target)) {
        setEpMenuOpen(null)
      }
    }
    document.addEventListener('pointerdown', onOutside)
    return () => document.removeEventListener('pointerdown', onOutside)
  }, [epMenuOpen])

  useEffect(() => {
    if (!verified) return
    setMeta(null)
    setError('')
    getMeta(type, id)
      .then((m) => {
        if (!m || !m.name) {
          setError(t('detail_not_found'))
          return
        }
        setMeta(m)
        if (m.videos && m.videos.length) {
          // Default to Season 1 (not whatever happens to be first in the
          // raw list) — season 0 is the "Combined" bucket and must never
          // be picked as the initial tab, only reachable by tapping it.
          const nums = [...new Set(m.videos.map((v) => v.season))]
          const sorted = nums.sort((a, b) => (a === 0 ? 1 : b === 0 ? -1 : a - b))
          setSeason(sorted[0])
        }
      })
      .catch(() => setError(t('detail_load_failed')))
  }, [type, id, retryKey, verified])

  useEffect(() => {
    if (!meta?.genres?.length) {
      setRelated(null)
      return
    }
    let cancelled = false
    setRelated(null)
    getRelatedTitles(type, id, meta.genres[0])
      .then((items) => {
        if (!cancelled) setRelated(items)
      })
      .catch(() => {
        if (!cancelled) setRelated([])
      })
    return () => {
      cancelled = true
    }
  }, [type, id, meta?.genres])

  const seasons = useMemo(() => {
    if (!meta?.videos) return []
    const nums = [...new Set(meta.videos.map((v) => v.season))]
    // Season 0 holds combined/special episodes — show it last, not first.
    return nums.sort((a, b) => {
      if (a === 0) return 1
      if (b === 0) return -1
      return a - b
    })
  }, [meta])

  const episodes = useMemo(() => {
    if (!meta?.videos) return []
    return meta.videos.filter((v) => v.season === season)
  }, [meta, season])

  if (!verified) {
    return <VerifyGate message={t('detail_verify_message')} />
  }

  if (error) {
    return (
      <div className="text-center mt-10 px-4">
        <div className="flex justify-start mb-6">
          <BackButton variant="inline" />
        </div>
        <p className="text-reel-rust mb-3">{error}</p>
        <button
          onClick={() => setRetryKey((k) => k + 1)}
          className="text-sm px-4 py-2 rounded-full bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-95 transition"
        >
          {t('retry')}
        </button>
      </div>
    )
  }

  if (!meta) {
    return (
      <div>
        <div className="w-full h-[42vh] sm:h-[52vh] bg-reel-surface2 relative overflow-hidden animate-pulse">
          <BackButton className="absolute top-4 left-4 z-20" />
        </div>
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-6 space-y-4">
          <div className="h-8 w-2/3 rounded bg-reel-surface2 animate-pulse" />
          <div className="flex gap-2">
            <div className="h-6 w-16 rounded-full bg-reel-surface2 animate-pulse" />
            <div className="h-6 w-20 rounded-full bg-reel-surface2 animate-pulse" />
            <div className="h-6 w-14 rounded-full bg-reel-surface2 animate-pulse" />
          </div>
          <div className="space-y-2 max-w-2xl">
            <div className="h-3.5 w-full rounded bg-reel-surface2 animate-pulse" />
            <div className="h-3.5 w-5/6 rounded bg-reel-surface2 animate-pulse" />
            <div className="h-3.5 w-3/4 rounded bg-reel-surface2 animate-pulse" />
          </div>
          <div className="h-10 w-32 rounded-lg bg-reel-surface2 animate-pulse" />
        </div>
      </div>
    )
  }

  const isSeries = type === 'series'

  // Top-level small download button (next to genres/languages):
  //  - movie            -> straight to quality picker for the movie itself
  //  - series, 1 season -> straight to quality picker for that season's episodes
  //  - series, 2+ seasons -> season-choose sheet first, then quality picker
  function openMainDownload() {
    if (!isSeries) {
      setDownloadTarget([{ id, title: meta.name, filename: meta.name }])
      return
    }
    if (!meta.videos?.length) return
    if (seasons.length <= 1) {
      setDownloadTarget(meta.videos.filter((v) => v.season === seasons[0]))
      return
    }
    setSeasonPickerOpen(true)
  }

  function pickSeasonForDownload(s) {
    setSeasonPickerOpen(false)
    setDownloadTarget((meta.videos || []).filter((v) => v.season === s))
  }

  const showReadMore = meta.description && meta.description.length > 110

  return (
    <div>
      <div
        className="w-full h-[30vh] sm:h-[38vh] bg-cover bg-center relative"
        style={{ backgroundImage: `linear-gradient(to top, #0B0B12 5%, rgba(11,11,18,0.4)), url(${meta.background || meta.poster})` }}
      >
        <BackButton className="absolute top-4 left-4 z-20" />
        <div className="absolute bottom-0 left-0 right-0 max-w-6xl mx-auto px-4 sm:px-6 pb-4 flex items-end gap-4">
          {meta.poster ? (
            <img
              src={meta.poster}
              alt={meta.name}
              className="hidden sm:block w-24 rounded-lg ring-1 ring-white/10 shadow-xl shrink-0"
            />
          ) : null}
          <div>
            <h1 className="font-display text-2xl sm:text-3xl font-semibold leading-tight">{meta.name}</h1>
            <p className="text-reel-muted text-xs mt-1">
              {meta.releaseInfo} {meta.runtime ? `· ${meta.runtime}` : ''} {meta.imdbRating ? `· ★ ${meta.imdbRating}` : ''}
            </p>
          </div>
        </div>
      </div>

      <div className="max-w-6xl mx-auto px-4 sm:px-6 py-3 pb-[calc(2rem+env(safe-area-inset-bottom))]">
        {meta.genres?.length || meta.languages?.length ? (
          <div className="flex items-center gap-2 mb-2.5">
            <div className="flex flex-wrap gap-1.5 flex-1 min-w-0">
              {meta.genres?.map((g) => (
                <span key={`genre-${g}`} className="text-[11px] bg-reel-surface2 text-reel-muted px-2 py-0.5 rounded-full">
                  {g}
                </span>
              ))}
              {meta.languages?.map((l) => (
                <span
                  key={`lang-${l}`}
                  className="text-[11px] border border-reel-gold/40 text-reel-gold px-2 py-0.5 rounded-full"
                >
                  {l}
                </span>
              ))}
            </div>
            <button
              onClick={openMainDownload}
              aria-label="Download"
              className="shrink-0 w-7 h-7 rounded-full flex items-center justify-center bg-white/10 text-reel-ink active:scale-90 transition"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" /></svg>
            </button>
          </div>
        ) : null}

        <p className={`text-reel-ink/90 max-w-2xl text-sm leading-snug ${descExpanded ? '' : 'line-clamp-2'}`}>
          {meta.description}
        </p>
        {showReadMore ? (
          <button
            onClick={() => setDescExpanded((v) => !v)}
            className="text-xs text-reel-gold mt-1.5 font-medium block"
          >
            {descExpanded ? t('read_less') : t('read_more')}
          </button>
        ) : null}

        {!isSeries ? (
          <button
            onClick={() => navigate(`/watch/${type}/${encodeURIComponent(id)}`)}
            className="mt-4 bg-reel-gold text-reel-bg font-semibold px-6 py-2.5 rounded-lg hover:brightness-110 active:scale-95 transition"
          >
            ▶ {t('play')}
          </button>
        ) : (
          <div className="mt-4">
            <div className="flex gap-2 overflow-x-auto no-scrollbar mb-3">
              {seasons.map((s) => (
                <button
                  key={s}
                  onClick={() => setSeason(s)}
                  className={`px-4 py-1.5 rounded-full text-sm shrink-0 transition ${
                    s === season
                      ? 'bg-reel-gold text-reel-bg font-semibold'
                      : 'bg-reel-surface2 text-reel-muted hover:text-reel-ink'
                  }`}
                >
                  {s === 0 ? t('combined') : `${t('season')} ${s}`}
                </button>
              ))}
            </div>

            <div className="space-y-2">
              {episodes.map((ep) => (
                <div
                  key={ep.id}
                  className="relative w-full flex gap-3 bg-reel-surface hover:bg-reel-surface2 active:scale-[0.98] transition rounded-lg p-2 ring-1 ring-white/5"
                >
                  <button
                    onClick={() => navigate(`/watch/${type}/${encodeURIComponent(ep.id)}`)}
                    className="flex gap-3 text-left min-w-0 flex-1"
                  >
                    <img
                      src={ep.thumbnail}
                      alt={ep.title}
                      loading="lazy"
                      className="w-24 sm:w-32 aspect-video object-cover rounded-md shrink-0"
                    />
                    <div className="min-w-0">
                      <p className="font-medium text-sm">
                        E{ep.episode} · {ep.title}
                      </p>
                      <p className="text-xs text-reel-muted mt-1 line-clamp-2">{ep.overview}</p>
                    </div>
                  </button>

                  <div className="relative shrink-0" ref={epMenuOpen === ep.id ? epMenuRef : null}>
                    <button
                      onClick={() => setEpMenuOpen((cur) => (cur === ep.id ? null : ep.id))}
                      aria-label="Episode options"
                      className="w-8 h-8 rounded-full flex items-center justify-center text-reel-muted hover:bg-white/10 active:scale-90 transition"
                    >
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
                    </button>
                    {epMenuOpen === ep.id ? (
                      <div className="absolute top-full right-0 mt-1.5 min-w-[172px] rounded-2xl overflow-hidden bg-reel-bg/97 backdrop-blur-md ring-1 ring-reel-gold/20 shadow-[0_10px_32px_rgba(0,0,0,0.7)] z-10">
                        <button
                          onClick={() => {
                            setEpMenuOpen(null)
                            setDownloadTarget([ep])
                          }}
                          className="w-full flex items-center gap-2.5 px-3.5 py-3 text-xs font-medium text-reel-ink hover:bg-reel-gold/10 active:bg-reel-gold/15 transition"
                        >
                          <span className="w-6 h-6 rounded-full bg-reel-gold/15 text-reel-gold flex items-center justify-center shrink-0">
                            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" /></svg>
                          </span>
                          {t('download')}
                        </button>
                        <div className="h-px bg-white/5 mx-3" />
                        <button
                          onClick={() => {
                            setEpMenuOpen(null)
                            toggleSaved(type, id, { name: meta.name, poster: meta.poster, releaseInfo: meta.releaseInfo, imdbRating: meta.imdbRating })
                          }}
                          className="w-full flex items-center gap-2.5 px-3.5 py-3 text-xs font-medium text-reel-ink hover:bg-reel-gold/10 active:bg-reel-gold/15 transition"
                        >
                          <span className={`w-6 h-6 rounded-full flex items-center justify-center shrink-0 ${saved ? 'bg-reel-gold text-reel-bg' : 'bg-reel-gold/15 text-reel-gold'}`}>
                            <svg width="13" height="13" viewBox="0 0 24 24" fill={saved ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2.3" strokeLinecap="round"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
                          </span>
                          {saved ? t('saved') : t('save')}
                        </button>
                        <div className="h-px bg-white/5 mx-3" />
                        <button
                          onClick={() => {
                            setEpMenuOpen(null)
                            shareEpisode(ep)
                          }}
                          className="w-full flex items-center gap-2.5 px-3.5 py-3 text-xs font-medium text-reel-ink hover:bg-reel-gold/10 active:bg-reel-gold/15 transition"
                        >
                          <span className="w-6 h-6 rounded-full bg-reel-gold/15 text-reel-gold flex items-center justify-center shrink-0">
                            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
                          </span>
                          {t('share')}
                        </button>
                      </div>
                    ) : null}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {related && related.length > 0 ? (
        <div className="max-w-6xl mx-auto px-0">
          <Rail title={t('more_like_this')} items={related} />
        </div>
      ) : null}

      {seasonPickerOpen ? (
        <div className="fixed inset-0 z-[95] flex items-end justify-center" onClick={() => setSeasonPickerOpen(false)}>
          <div className="absolute inset-0 bg-black/60" />
          <div
            onClick={(e) => e.stopPropagation()}
            className="relative w-full max-w-md bg-reel-bg rounded-t-2xl pt-3 pb-6 px-5 ring-1 ring-white/10 shadow-[0_-8px_32px_rgba(0,0,0,0.7)] max-h-[80vh] overflow-y-auto"
          >
            <div className="w-10 h-1 rounded-full bg-white/15 mx-auto mb-4" />
            <p className="text-reel-ink font-semibold mb-1">{t('detail_choose_season')}</p>
            <p className="text-reel-muted text-xs mb-4">{t('detail_choose_season_sub')}</p>
            <div className="flex flex-wrap gap-2">
              {seasons.map((s) => (
                <button
                  key={s}
                  onClick={() => pickSeasonForDownload(s)}
                  className="px-4 py-2 rounded-full text-sm font-semibold bg-white/10 text-reel-ink active:scale-95 transition"
                >
                  {s === 0 ? t('combined') : `${t('season')} ${s}`}
                </button>
              ))}
            </div>
          </div>
        </div>
      ) : null}

      {menuToast ? (
        <div className="fixed bottom-[calc(1.5rem+env(safe-area-inset-bottom))] left-1/2 -translate-x-1/2 z-[95] whitespace-nowrap text-[11px] bg-reel-ink text-reel-bg font-semibold px-3 py-1.5 rounded-full shadow-lg">
          {menuToast}
        </div>
      ) : null}

      <DownloadQualitySheet
        open={!!downloadTarget}
        onClose={() => setDownloadTarget(null)}
        type={type}
        imdbId={id}
        showName={meta.name}
        showPoster={meta.poster}
        episodes={downloadTarget || []}
      />
    </div>
  )
}
