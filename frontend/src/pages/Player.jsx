import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getStreams, getMeta, qualityLabel, isVerified, getContinueWatching, saveWatchProgress, removeWatchProgress, getRelatedTitles, getComments, getReportStatus, submitReport, reportStreamLanguages } from '../api'
import VideoPlayer from '../components/VideoPlayer'
import Comments from '../components/Comments'
import CommentsSheet from '../components/CommentsSheet'
import DownloadQualitySheet from '../components/DownloadQualitySheet'
import Rail from '../components/Rail'
import RatingStars from '../components/RatingStars'
import ReportForm from '../components/ReportForm'
import { useLocalReactions, useLocalRating } from '../components/localInteractions'
import { useIsSaved, toggleSaved } from '../lib/savedStore'
import { useDownloadEntry, downloadId, setWatching, isWatchingNow } from '../lib/downloadsStore'
import { formatDisplayTitle } from '../lib/formatTitle'
import VerifyGate from '../components/VerifyGate'
import PlayerErrorBoundary from '../components/PlayerErrorBoundary'
import { useLanguage } from '../i18n/LanguageContext'

// Splits the backend's stream.title (e.g. "📁 file.mkv\n💾 3.34GB\n👤 @Channel")
// into a clean filename + list of badge lines.
//
// BUG FIX (user ask: "MB ke bagal mein @HindiNewMovies jaisa username nahi
// dikhna chahiye"): 👤 line is the source channel/encoder credit — useful
// internally but looks unpolished/unbranded shown to viewers, so it's
// filtered out here. Everything else (size, codec, etc.) still shows.
function parseStreamMeta(stream) {
  const lines = (stream?.title || '').split('\n').map((l) => l.trim()).filter(Boolean)
  let filename = stream?.name || ''
  const badges = []
  for (const line of lines) {
    if (line.startsWith('📁')) filename = line.replace('📁', '').trim()
    else if (line.startsWith('👤')) continue
    else badges.push(line)
  }
  return { filename, badges }
}

// Default playback quality should be the LOWEST available (360p over 480p
// over 720p... etc), not whatever the backend happens to list first — user
// ask: data-friendly default, let them manually bump it up if they want.
// Streams with no parseable resolution (odd/"Auto" names) sort after every
// known resolution since we can't tell where they actually rank.
function lowestQualityStream(list) {
  const rank = (stream) => {
    const label = qualityLabel(stream)
    const match = label.match(/(\d+)/)
    return match ? Number(match[1]) : Infinity
  }
  return [...list].sort((a, b) => rank(a) - rank(b))[0]
}

export default function Player() {
  const { type, id } = useParams()

  // FEATURE (user ask: "jahan MB dikhta hai uske side mein audio-panel jaisi
  // language buttons chahiye — jis language pe tap karega usi mein play hona
  // shuru ho jaaye"): native (MainActivity.kt) STATE_READY par
  // window.__suhaniOnNativeTracksReady() call karta hai — us par yeh
  // getAudioTracksJson() se poori list utha leta hai. Episode badalne par
  // list turant purani ho jaati hai, isliye id badalte hi khaali kar dete
  // hain jab tak naya episode apni khud ki READY event na bhej de.
  const [audioTracks, setAudioTracks] = useState([])
  useEffect(() => {
    setAudioTracks([])
  }, [id])
  const navigate = useNavigate()
  const [streams, setStreams] = useState(null)
  const [active, setActive] = useState(null)
  const [error, setError] = useState('')
  const verified = isVerified()
  const { t } = useLanguage()

  // FEATURE (user ask: "sabhi ka language metadata caption/track se acche
  // se detect ho"): `active` changes independently of `id` (quality
  // switches don't remount this effect), so a ref keeps fetchTracks() able
  // to always report against whichever quality is *actually* loaded right
  // now, not a stale closure from when the effect first ran.
  // BUG FIX (blank-page crash: "Cannot access 'active' before
  // initialization"): this MUST sit after `const [active] = useState(...)`
  // above — a useEffect's dependency array is evaluated immediately
  // (unlike its callback body), so referencing `active` here before its
  // own declaration line throws a TDZ ReferenceError on every render.
  const activeRef = useRef(null)
  useEffect(() => {
    activeRef.current = active
  }, [active])
  useEffect(() => {
    const fetchTracks = () => {
      try {
        const json = window.AndroidPlayer?.getAudioTracksJson?.()
        if (!json) return
        const parsed = JSON.parse(json)
        setAudioTracks(parsed)
        // Crowd-source real detected languages back to the backend so the
        // language-first picker below is accurate for every viewer after
        // this one — see api.js reportStreamLanguages / bug report ("480p
        // select karne par Persian nikla, kisi ko pehle se pata nahi tha").
        const streamId = activeRef.current?.id
        const detected = parsed.map((tr) => tr.label).filter(Boolean)
        if (streamId && detected.length) {
          reportStreamLanguages(type, id, streamId, detected).catch(() => {})
        }
      } catch {
        // native bridge abhi maujood nahi ya JSON garbled — chup rehte hain,
        // buttons bas nahi dikhenge
      }
    }
    window.__suhaniOnNativeTracksReady = fetchTracks
    fetchTracks()
    return () => { delete window.__suhaniOnNativeTracksReady }
  }, [id, type])
  const selectAudioTrack = (index) => {
    window.AndroidPlayer?.selectAudioTrackByIndex?.(index)
    setAudioTracks((prev) => prev.map((t) => ({ ...t, selected: t.index === index })))
  }

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

  // FEATURE (user ask: "ek aur naya icon add karna hai ambient mode on/off
  // ka"): the blurred-poster glow bleeding above/below the player already
  // existed (see glowImage below) — this just makes it toggleable, same
  // localStorage-persisted on/off pattern as autoplay above. Defaults on
  // so existing behaviour doesn't change for anyone who never touches it.
  const [ambientMode, setAmbientMode] = useState(() => {
    try {
      return localStorage.getItem('suhani-screen:ambient') !== 'off'
    } catch {
      return true
    }
  })

  function toggleAmbient() {
    setAmbientMode((a) => {
      const next = !a
      try {
        localStorage.setItem('suhani-screen:ambient', next ? 'on' : 'off')
      } catch {
        // ignore storage failures
      }
      return next
    })
  }

  const { reactions, react } = useLocalReactions(type, id)
  // FEATURE (user ask: "like/dislike/download/share/save ke alawa kuch
  // add karo — Report ya Rating"): star rating shares the same
  // per-title-per-user pattern as reactions above. `ratingOpen` /
  // `reportOpen` drive the two bottom sheets (reusing CommentsSheet's
  // generic shell — dekho neeche render).
  const { rating, rate } = useLocalRating(type, id)
  const [ratingOpen, setRatingOpen] = useState(false)
  const [reportOpen, setReportOpen] = useState(false)
  const [alreadyReported, setAlreadyReported] = useState(false)
  const [reportSubmitting, setReportSubmitting] = useState(false)
  const [reportSubmitted, setReportSubmitted] = useState(false)

  useEffect(() => {
    let cancelled = false
    setAlreadyReported(false)
    setReportSubmitted(false)
    getReportStatus(type, id)
      .then((r) => {
        if (!cancelled) setAlreadyReported(!!r.reported)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [type, id])

  async function handleReportSubmit(reason, note) {
    setReportSubmitting(true)
    try {
      // Same title/poster/season/episode the download sheet uses (see
      // downloadEpisode below) — reused here so the report Telegram
      // message can show the owner exactly what's being reported.
      await submitReport(type, id, reason, note, {
        title: isSeries ? (allEpisodes.find((e) => e.id === id)?.title || displayTitle) : titleInfo.name,
        poster: titleInfo.poster,
        season: isSeries ? currentSeason : undefined,
        episode: isSeries ? currentEpisode : undefined,
      })
      setReportSubmitted(true)
    } catch {
      // leave the form open so the user can retry — a stuck "submitting"
      // state would be worse than just letting them tap submit again
    } finally {
      setReportSubmitting(false)
    }
  }

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
      setError(t('player_stream_failed'))
    } catch {
      // network hiccup while probing — leave the player's own error state as-is
    }
  }

  const [retryKey, setRetryKey] = useState(0)
  const [toast, setToast] = useState('')
  // Continuously tracks current playback position so that switching quality
  // mid-video can resume from the same spot instead of restarting.
  const resumeAt = useRef(0)
  // FEATURE (user ask: "Watch history / Continue Watching"): last time we
  // sent a progress update to the backend — throttled so scrubby playback
  // doesn't fire a network call on every single timeupdate tick.
  const lastProgressSaveRef = useRef(0)
  const lastKnownDurationRef = useRef(0)
  // FEATURE (user ask: "comments section YouTube jaisa — button dabao toh
  // khule"): count preview ke liye rakha hai, poori list sirf sheet khulne
  // par mount hoti hai.
  const [commentsOpen, setCommentsOpen] = useState(false)
  const [commentCount, setCommentCount] = useState(null)
  // FEATURE (user ask: "Related/Recommended videos"): sirf movies ke liye —
  // series mein "Up Next" (episode list) already yehi role play karta hai,
  // ek movie khatam hone ke baad koi "next" nahi hota to genre-based
  // suggestions dikha dete hain.
  const [related, setRelated] = useState(null)

  // FEATURE (user ask: "Watch history / Continue Watching"): jab user
  // player se hat kar kahin aur chala jaaye (Back dabaye, ya doosri
  // episode par jump kare) beech video mein, us waqt tak ka progress save
  // karo — warna sirf har-10s ka throttled save use karne se aakhri kuch
  // second ka progress kabhi save hi nahi hota.
  useEffect(() => {
    return () => {
      const dur = lastKnownDurationRef.current
      const pos = resumeAt.current
      if (dur > 0 && pos > 5) {
        saveWatchProgress({
          type,
          id: imdbId,
          position: pos,
          duration: dur,
          title: titleInfo.name,
          poster: titleInfo.poster,
          episodeId: isSeries ? id : undefined,
        }).catch(() => {})
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type, id])

  useEffect(() => {
    if (isSeries || !movieMeta?.genres?.length) {
      setRelated(null)
      return
    }
    let cancelled = false
    setRelated(null)
    getRelatedTitles('movie', imdbId, movieMeta.genres[0])
      .then((items) => {
        if (!cancelled) setRelated(items)
      })
      .catch(() => {
        if (!cancelled) setRelated([])
      })
    return () => {
      cancelled = true
    }
  }, [isSeries, imdbId, movieMeta?.genres])

  useEffect(() => {
    if (!verified) return
    let cancelled = false
    setCommentCount(null)
    getComments(type, id)
      .then((list) => {
        if (!cancelled) setCommentCount(list.length)
      })
      .catch(() => {
        if (!cancelled) setCommentCount(0)
      })
    return () => {
      cancelled = true
    }
  }, [type, id, verified])

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
    if (!verified) return
    setStreams(null)
    setActive(null)
    setError('')
    setDriveFallbackUrl(null)
    fallbackCheckedFor.current = null
    resumeAt.current = 0
    getStreams(type, id)
      .then((list) => {
        if (!list.length) {
          setError(t('player_no_stream'))
          return
        }
        setStreams(list)
        setActive(lowestQualityStream(list))
      })
      .catch(() => setError(t('player_stream_load_failed')))
    // FEATURE (user ask: "Watch history / Continue Watching"): backend se
    // is title/episode ka pehle se saved resume-position dhoondo — mile
    // toh wahin se video shuru karo, YouTube ki tarah.
    const episodeKey = isSeries ? id : null
    getContinueWatching()
      .then((items) => {
        const match = items.find((it) => (episodeKey ? it.episode_id === episodeKey : it.k === id))
        if (match?.pos > 5) resumeAt.current = match.pos
      })
      .catch(() => {})
  }, [type, id, retryKey, verified])

  useEffect(() => {
    if (!verified || !isSeries) {
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
  }, [verified, isSeries, imdbId])

  useEffect(() => {
    if (!verified || isSeries) {
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
  }, [verified, isSeries, imdbId])

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

  // Bug fix (user report: "480p select karne par bhi 1080p hi chalta hai"):
  // backend kabhi-kabhi ek hi actual file ke liye do stream entries bhej deta
  // hai jinke "url" bilkul same hote hain lekin "label" alag-alag (mislabeled
  // indexing/admin tagging ki wajah se — dekho backend/Backend/fastapi/routes/
  // stremio_routes.py: format_stream_details() ka `resolution = parsed.get(
  // "resolution", quality)` fallback). Jab do labels same URL par point karte
  // hain, dropdown mein "480p" dikhta hai lekin uspar click karne se bhi wahi
  // (asal mein 1080p wali) file chalti hai — kyunki url hi same hai, koi
  // real switch hota hi nahi. Fix: same-URL wale duplicate quality entries ko
  // yahin par hi drop kar do, taaki sirf genuinely-alag files hi menu mein
  // dikhein aur select karna hamesha actually-alag stream par le jaaye.
  const qualities = useMemo(() => {
    const seenUrls = new Set()
    const out = []
    for (const s of streams || []) {
      if (seenUrls.has(s.url)) continue
      seenUrls.add(s.url)
      out.push({ ...s, label: qualityLabel(s) })
    }
    return out
  }, [streams])
  const activeQualityObj = useMemo(
    () => qualities.find((q) => q.url === active?.url) || null,
    [qualities, active]
  )

  // FEATURE (user ask: "spirit away se releted jitne bhi language hai sare
  // language section mein add kar do — Hindi pe click karega toh sirf uske
  // available qualities dikhein"): flips the old flow (pick a quality, only
  // then find out — the hard way — what language it happens to be) around.
  // A stream can carry more than one language (dual-audio releases), so it
  // lands in every language bucket it has; streams with nothing detected
  // yet fall into "Unknown" rather than vanishing from the picker.
  const [selectedLanguage, setSelectedLanguage] = useState(null)
  useEffect(() => {
    setSelectedLanguage(null)
  }, [id])

  const languageGroups = useMemo(() => {
    const map = new Map()
    for (const q of qualities) {
      const langs = q.languages && q.languages.length ? q.languages : ['Unknown']
      for (const lang of langs) {
        if (!map.has(lang)) map.set(lang, [])
        map.get(lang).push(q)
      }
    }
    return map
  }, [qualities])

  const availableLanguages = useMemo(() => [...languageGroups.keys()], [languageGroups])

  // Only the "pick a quality" menu (in-page dropdown + native quality
  // sheet) narrows down to the selected language — the video itself keeps
  // playing uninterrupted until the user actually taps a different
  // quality, same as before this feature.
  const qualitiesForPicker = useMemo(() => {
    if (!selectedLanguage) return qualities
    return languageGroups.get(selectedLanguage) || qualities
  }, [selectedLanguage, languageGroups, qualities])

  function switchLanguage(lang) {
    setSelectedLanguage(lang)
    const options = languageGroups.get(lang) || []
    if (!options.length) return
    // Prefer staying at (roughly) the same quality rank the user was
    // already watching at; if that exact rank isn't available in this
    // language, fall back to the lowest quality in it (same
    // data-friendly default lowestQualityStream() uses on first load).
    const sameRank = options.find((o) => o.label === activeQualityObj?.label)
    switchQuality(sameRank || lowestQualityStream(options))
  }
  // FEATURE (user ask: "caption mein promotion (@Channel) nahi hona
  // chahiye — Name Year [Language] Source [Quality] format chahiye"):
  // meta.filename yahan bhi raw hai, VideoPlayer ko sirf clean title jaata
  // hai. Quality live update hoti hai jab bhi user quality switch karta
  // hai (activeQualityObj change hone par yeh khud recompute ho jaata hai).
  const displayTitle = useMemo(
    () => formatDisplayTitle(meta.filename, activeQualityObj?.label),
    [meta.filename, activeQualityObj]
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
        label = `${t('season')} ${nextSeasonNum}`
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
    // Video khatam ho gaya — isse "Continue Watching" mein clutter ki tarah
    // mat rakho (backend >95% ko already "finished" treat karta hai, par
    // yahan turant hata dena UI ko turant consistent rakhta hai).
    removeWatchProgress(imdbId, isSeries ? id : undefined).catch(() => {})
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
    // BUG FIX (user report: "Autoplay On hone ke bawajood episode khatam
    // hone par next episode play nahi hota"): native (inline) playback mein
    // asli <video> tag ka 'ended' event kabhi fire nahi hota — VideoPlayer.jsx
    // ka ended-listener sirf web fallback ke liye hai, isliye niche wala
    // onEnded={handleEnded} prop native mode mein kabhi call hi nahi hota
    // tha. MainActivity.kt ab STATE_ENDED par yahi window function call
    // karta hai (prev/next jaisa hi bridge pattern) — ab handleEnded()
    // (jo autoplay check karke agle episode par navigate karta hai) native
    // mode mein bhi chalta hai.
    window.__suhaniOnNativeEnded = handleEnded
    return () => {
      delete window.__suhaniOnNativeNext
      delete window.__suhaniOnNativePrev
      delete window.__suhaniOnNativeEnded
    }
  }, [nextEpisode, prevEpisode, navigate, handleEnded])

  // Android ko batao ki agla/pichla episode maujood hai ya nahi, taaki
  // wahan prev/next button sahi se enable/dim ho.
  useEffect(() => {
    window.AndroidPlayer?.setAdjacentEpisodes?.(!!nextEpisode, !!prevEpisode)
  }, [nextEpisode, prevEpisode])

  // BUG FIX (user report: "480p select kiya, quality button 480p dikha raha
  // hai, lekin niche title/filename abhi bhi 1080p wala hi dikha raha hai"):
  // chhote (inline) native player ka apna khud ka quality-picker hai
  // (dekho MainActivity.showInlineQualityDialog/switchInlineQuality) — yeh
  // switch PURELY Kotlin-side hota hai, website (`active` state) ko iska
  // kabhi pata hi nahi chalta tha. Isliye page ka poora UI jo `active` par
  // depend karta hai — filename heading, download button ki quality,
  // activeQualityObj — hamesha wahi PEHLI/original quality dikhata rehta
  // tha, chahe native mein user ne kuch bhi genuinely select kiya ho.
  // Fix: native ab ek switch ke baad `window.__suhaniOnNativeQualityChange(url)`
  // call karta hai — yahan us URL ko `streams` mein dhoondh kar `active`
  // (asli React state) ko bhi sync kar dete hain, taaki poora page turant
  // sahi quality reflect kare.
  useEffect(() => {
    window.__suhaniOnNativeQualityChange = (url) => {
      const match = (streams || []).find((s) => s.url === url)
      if (match) switchQuality(match)
    }
    return () => {
      delete window.__suhaniOnNativeQualityChange
    }
  }, [streams])

  // Download is per-quality — one entry per stream URL, so switching
  // quality and downloading again doesn't clash with an earlier download.
  // Bug fix: pehle yahan sirf `imdbId` (season/episode ke bina) use hota tha
  // — series ke har episode ka downloadId isliye sirf quality-label se banta
  // tha (jaise "series:tt123:1080p"), toh Episode 1 aur Episode 2 dono ka
  // 1080p download EK HI id/IndexedDB-entry par collide ho jaata tha, aur
  // baad wala pehle wale ko silently overwrite kar deta tha. Ab poora `id`
  // (series ke liye "imdbId:season:episode") use karte hain taaki har
  // episode ka apna alag, safe download entry bane.
  const thisDownloadId = activeQualityObj ? downloadId(type, id, activeQualityObj.label) : null
  const downloadEntry = useDownloadEntry(thisDownloadId)

  // FEATURE (user ask: player page ke Download button mein bhi quality
  // choose karne ka option chahiye — pehle yeh seedha currently-active
  // stream download kar deta tha, ab Detail.jsx wala hi DownloadQualitySheet
  // reuse karke ek chhota picker (360p/480p/720p/...) khulta hai.
  const [downloadSheetOpen, setDownloadSheetOpen] = useState(false)
  const downloadEpisode = useMemo(
    () => ({
      id,
      title: isSeries ? (allEpisodes.find((e) => e.id === id)?.title || displayTitle) : titleInfo.name,
      filename: meta.filename,
      season: isSeries ? currentSeason : undefined,
      episode: isSeries ? currentEpisode : undefined,
    }),
    [id, isSeries, allEpisodes, displayTitle, titleInfo.name, meta.filename, currentSeason, currentEpisode]
  )

  function downloadFile() {
    setDownloadSheetOpen(true)
  }

  function shareIt() {
    if (navigator.share) {
      navigator.share({ title: displayTitle, url: window.location.href }).catch(() => {})
    } else {
      navigator.clipboard
        ?.writeText(window.location.href)
        .then(() => showToast(t('player_link_copied')))
        .catch(() => {})
    }
  }

  if (!verified) {
    return <VerifyGate message={t('player_verify_message')} />
  }

  return (
    <PlayerErrorBoundary>
    <div className="max-w-3xl mx-auto pb-[calc(2.5rem+env(safe-area-inset-bottom))]">
      {error ? (
        <div className="px-4 sm:px-6">
          <p className="text-reel-rust mb-3">{error}</p>
          <button
            onClick={() => setRetryKey((k) => k + 1)}
            className="text-sm px-4 py-2 rounded-full bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-95 transition"
          >
            {t('retry')}
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
            {glowImage && ambientMode ? (
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
                  title={displayTitle}
                  qualities={qualitiesForPicker}
                  activeQuality={activeQualityObj}
                  episodeKey={id}
                  onQualityChange={(q) => switchQuality(q)}
                  ambientEnabled={ambientMode}
                  startAt={resumeAt.current}
                  onProgressTick={(t, dur) => {
                    resumeAt.current = t
                    lastKnownDurationRef.current = dur
                    // Throttle: save at most once every 10s of real time.
                    const now = Date.now()
                    if (dur > 0 && now - lastProgressSaveRef.current > 10000) {
                      lastProgressSaveRef.current = now
                      saveWatchProgress({
                        type,
                        id: imdbId,
                        position: t,
                        duration: dur,
                        title: titleInfo.name,
                        poster: titleInfo.poster,
                        episodeId: isSeries ? id : undefined,
                      }).catch(() => {})
                    }
                  }}
                  onEnded={handleEnded}
                  onFatalError={handleVideoFatalError}
                  onPlayStateChange={setWatching}
                />
              )}
            </div>
          </div>

          <div className="px-4 sm:px-6">
          {/* Title + badges */}
          <div className="mt-4">
            <h1 className="font-display text-lg text-reel-ink break-words">{displayTitle}</h1>
            <div className="flex flex-wrap gap-2 mt-2">
              {/* FEATURE (user ask: "Language alag se upar kyun likha hai —
                  sara kuch ek jagah hoga, title ke niche, jahan MB diya hua
                  hai"): pehle yeh apna alag section title ke UPAR tha — ab
                  seedha isi size/track badges wali row mein, sabse pehle,
                  taaki sab kuch ek hi jagah dikhe. Sirf tab dikhta hai jab
                  1 se zyada language ho (single-language title ke liye kuch
                  choose karne ko hai hi nahi). */}
              {availableLanguages.length > 1 && availableLanguages.map((lang) => (
                <button
                  key={lang}
                  onClick={() => switchLanguage(lang)}
                  aria-pressed={selectedLanguage === lang}
                  className={`text-xs px-2.5 py-1 rounded-full font-medium transition active:scale-95 whitespace-pre ${
                    selectedLanguage === lang
                      ? 'bg-reel-gold text-reel-bg'
                      : 'bg-reel-surface2 text-reel-muted hover:text-reel-ink'
                  }`}
                >
                  {lang} · {languageGroups.get(lang)?.length || 0}
                </button>
              ))}
              {meta.badges.map((b, i) => (
                <span key={i} className="text-xs px-2.5 py-1 rounded-full bg-reel-surface2 text-reel-muted whitespace-pre">
                  {b}
                </span>
              ))}
              {/* User ask: "jo language available hai sab yahan buttons ki
                  tarah honi chahiye — jo abhi chal rahi hai wo glow karegi,
                  baaki dim rahengi, kisi aur pe tap karte hi wahi glow karne
                  lagegi aur audio switch ho jayega." Pehle yeh sirf 2+
                  languages hone par dikhta tha — single-audio episodes
                  (jaise yeh, sirf Hindi) mein poori tarah chhupa rehta tha,
                  isliye khaali/missing lag raha tha. Ab kam se kam 1 track
                  hone par bhi dikhta hai. */}
              {audioTracks.length > 0 && audioTracks.map((t) => (
                <button
                  key={t.index}
                  onClick={() => selectAudioTrack(t.index)}
                  className={`text-xs px-2.5 py-1 rounded-full border whitespace-pre transition-colors ${
                    t.selected
                      ? 'bg-reel-gold text-reel-bg border-reel-gold'
                      : 'bg-reel-surface2 text-reel-muted border-transparent'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </div>

          {/* Reactions / download / save / report / ambient row. Wrapped in
              a relative div so the toast (download-queued messages etc,
              see showToast) still has somewhere to render now that it's
              not anchored to the (temporarily hidden) share button. */}
          <div className="relative">
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
              onClick={() => setRatingOpen(true)}
              aria-label={t('rate')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 active:scale-95 transition ${
                rating.mine ? 'bg-reel-gold text-reel-bg font-semibold' : 'bg-reel-surface2 text-reel-muted'
              }`}
              title={t('rate')}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" /></svg>
              {rating.mine ? rating.mine : rating.count > 0 ? rating.average : t('rate')}
            </button>
            <button
              onClick={downloadEntry?.status === 'queued' ? () => showToast(isWatchingNow() ? t('dl_queued_watching') : t('dl_queued')) : downloadFile}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs shrink-0 bg-reel-surface2 text-reel-muted hover:text-reel-ink active:scale-95 transition"
              title={t('download')}
              aria-label={t('download')}
            >
              {downloadEntry?.status === 'downloading' ? (
                <>
                  <span className="w-3 h-3 border-2 border-reel-muted/30 border-t-reel-gold rounded-full animate-spin" />
                  {downloadEntry.progress ? `${downloadEntry.progress}%` : t('dl_downloading')}
                </>
              ) : downloadEntry?.status === 'done' ? (
                <>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round"><polyline points="20 6 9 17 4 12"/></svg>
                  {t('downloaded')}
                </>
              ) : downloadEntry?.status === 'queued' ? (
                <>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/></svg>
                  {t('dl_queued')}
                </>
              ) : (
                <>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                  {t('download')}
                </>
              )}
            </button>
            {downloadEntry?.status === 'queued' ? (
              // FEATURE (user ask: "download button ke udhar quota wala
              // button dikhega, click karne par notification aayega ki
              // watch karte-karte download nahi kar sakte"): watching band
              // hote hi yeh badge apne aap gayab ho jaata hai (status
              // 'downloading' ban jaata hai) — koi extra state track nahi
              // karni padi, downloadEntry khud hi reflect kar deta hai.
              <button
                onClick={() => showToast(isWatchingNow() ? t('dl_queued_watching') : t('dl_queued'))}
                aria-label={t('dl_queued')}
                title={t('dl_queued')}
                className="p-2 rounded-full text-xs shrink-0 bg-reel-surface2 text-reel-muted hover:text-reel-ink active:scale-95 transition"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 22c4.97 0 9-4.03 9-9s-4.03-9-9-9-9 4.03-9 9 4.03 9 9 9z"/><path d="M12 8v5l3 2"/></svg>
              </button>
            ) : null}
            {/* Share button hidden for now (user ask: "share button ko
                abhi ke liye gayab kar do") — shareIt/toast logic kept
                as-is below (unused warnings aside) so it's a one-line
                revert whenever it comes back. */}
            <button
              onClick={handleToggleSaved}
              aria-label={saved ? 'Remove from saved' : 'Save'}
              aria-pressed={saved}
              className={`p-2 rounded-full text-xs shrink-0 active:scale-95 transition ${saved ? 'bg-reel-gold text-reel-bg' : 'bg-reel-surface2 text-reel-muted hover:text-reel-ink'}`}
              title={t('save')}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill={saved ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
            </button>
            <button
              onClick={() => setReportOpen(true)}
              aria-label={t('report')}
              className="p-2 rounded-full text-xs shrink-0 bg-reel-surface2 text-reel-muted hover:text-reel-ink active:scale-95 transition"
              title={t('report')}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z"/><line x1="4" y1="22" x2="4" y2="15"/></svg>
            </button>
            {/* Ambient mode on/off — toggles the blurred-poster glow above
                the player (see glowImage/ambientMode near the top). */}
            <button
              onClick={toggleAmbient}
              aria-label={ambientMode ? 'Turn off ambient mode' : 'Turn on ambient mode'}
              aria-pressed={ambientMode}
              className={`p-2 rounded-full text-xs shrink-0 active:scale-95 transition ${ambientMode ? 'bg-reel-gold text-reel-bg' : 'bg-reel-surface2 text-reel-muted hover:text-reel-ink'}`}
              title={ambientMode ? 'Ambient mode: on' : 'Ambient mode: off'}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></svg>
            </button>
            </div>
            {toast ? (
              <div className="animate-toast-in absolute top-full mt-2 left-1/2 -translate-x-1/2 whitespace-nowrap text-[11px] bg-reel-ink text-reel-bg font-semibold px-2.5 py-1 rounded-full z-10">
                {toast}
              </div>
            ) : null}
          </div>

          {/* Rating + Report sheets — reuse CommentsSheet's generic
              bottom-sheet shell (title + children), just with different
              content. */}
          <CommentsSheet open={ratingOpen} onClose={() => setRatingOpen(false)} title={t('rate_title')}>
            <RatingStars rating={rating} onRate={(stars) => { rate(stars, { title: isSeries ? (allEpisodes.find((e) => e.id === id)?.title || displayTitle) : titleInfo.name, poster: titleInfo.poster }); setRatingOpen(false) }} />
          </CommentsSheet>

          <CommentsSheet open={reportOpen} onClose={() => setReportOpen(false)} title={t('report_title')}>
            <ReportForm
              alreadyReported={alreadyReported}
              submitting={reportSubmitting}
              submitted={reportSubmitted}
              onSubmit={handleReportSubmit}
            />
          </CommentsSheet>

          {/* Comments — YouTube-style: chhota preview bar, poori list ek
              bottom-sheet mein khulti hai (dekho CommentsSheet.jsx) */}
          <button
            onClick={() => setCommentsOpen(true)}
            className="mt-6 w-full flex items-center justify-between gap-3 bg-reel-surface rounded-lg px-4 py-3 ring-1 ring-reel-ink/5 active:scale-[0.99] transition text-left"
          >
            <span className="text-sm font-display font-semibold text-reel-ink">
              💬 {t('comments_title')} {commentCount != null ? `· ${commentCount}` : ''}
            </span>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className="text-reel-muted shrink-0"><path d="m9 18 6-6-6-6" /></svg>
          </button>

          <CommentsSheet open={commentsOpen} onClose={() => setCommentsOpen(false)} title={`${t('comments_title')}${commentCount != null ? ` · ${commentCount}` : ''}`}>
            <Comments
              type={type}
              id={id}
              onCountChange={setCommentCount}
              title={isSeries ? (allEpisodes.find((e) => e.id === id)?.title || displayTitle) : titleInfo.name}
              poster={titleInfo.poster}
            />
          </CommentsSheet>

          <DownloadQualitySheet
            open={downloadSheetOpen}
            onClose={() => setDownloadSheetOpen(false)}
            type={type}
            imdbId={imdbId}
            showName={titleInfo.name}
            showPoster={titleInfo.poster}
            episodes={[downloadEpisode]}
          />

          {/* Up next — rest of this season, or the next season once you hit its last episode */}
          {isSeries && upNext.episodes.length > 0 ? (
            <div className="mt-8 pt-6 border-t border-reel-ink/5">
              <div className="flex items-center justify-between mb-3 gap-3">
                <h2 className="font-display text-lg text-reel-ink">{t('player_up_next')} · {upNext.label}</h2>
                <button
                  onClick={toggleAutoplay}
                  aria-pressed={autoplay}
                  className={`flex items-center gap-2 text-xs px-3 py-1.5 rounded-full shrink-0 active:scale-95 transition ${
                    autoplay ? 'bg-reel-gold text-reel-bg font-semibold' : 'bg-reel-surface2 text-reel-muted'
                  }`}
                >
                  <span className={`w-2 h-2 rounded-full ${autoplay ? 'bg-reel-bg' : 'bg-reel-muted'}`} />
                  {t('player_autoplay')} {autoplay ? t('on') : t('off')}
                </button>
              </div>
              <div className="space-y-3">
                {upNext.episodes.map((ep) => (
                  <button
                    key={ep.id}
                    onClick={() => navigate(`/watch/series/${encodeURIComponent(ep.id)}`)}
                    className="w-full flex gap-4 text-left bg-reel-surface hover:bg-reel-surface2 active:scale-[0.98] transition rounded-lg p-3 ring-1 ring-reel-ink/5"
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

          {!isSeries && related && related.length > 0 ? (
            <div className="mt-8 pt-6 border-t border-reel-ink/5">
              <Rail title={t('more_like_this')} items={related} />
            </div>
          ) : null}
          </div>
        </>
      )}
    </div>
    </PlayerErrorBoundary>
  )
}
