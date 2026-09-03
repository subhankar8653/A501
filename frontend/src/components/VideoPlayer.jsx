import { useCallback, useEffect, useRef, useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext'

const SPEEDS = [1, 1.25, 1.5, 2, 3]
const HIDE_DELAY = 2800
const DOUBLE_TAP_WINDOW = 320
const DRAG_PIP_THRESHOLD = 90 // px downward drag needed to trigger PiP
const PIP_W = 168
const PIP_H = 94
const PIP_MARGIN = 14
const FLIP_MS = 320

function fmt(t) {
  if (!isFinite(t) || t < 0) t = 0
  const h = Math.floor(t / 3600)
  const m = Math.floor((t % 3600) / 60)
  const s = Math.floor(t % 60)
  const mm = String(m).padStart(h ? 2 : 1, '0')
  const ss = String(s).padStart(2, '0')
  return h ? `${h}:${mm.padStart(2, '0')}:${ss}` : `${mm}:${ss}`
}

// ---------------------------------------------------------------------------
// NATIVE INLINE BRIDGE (Sisisisi / A501 Android shell)
// ---------------------------------------------------------------------------
// App ke andar video YouTube jaisa chhote (inline) native player mein play
// hota hai, is exact div ki jagah par — poori-screen wale native player par
// seedhe nahi jaate. Chhote player ke apne built-in controller mein hi ek
// fullscreen icon hota hai; usse tap karte hi poora rich native PlayerActivity
// (gestures, equalizer, subtitles, cast, PiP, quality-switch) khulta hai —
// aur wahan se wapas aane par chhota player wahi position se resume ho jaata hai.
//
// window.AndroidPlayer.mount(uri, title, qualitiesJson, currentPath) — naya video load/start
// window.AndroidPlayer.updateRect(left, top, width, height, currentPath) — CSS px, is div ka
//   rect jab bhi badle (resize/scroll), chhote player ko wahi jagah chipkaye rakhta hai
// window.AndroidPlayer.unmount() — naya video/page chhodne par hata do
//
// Web par (bina app ke, plain browser mein) yeh bridge exist nahi karta,
// isliye wahan normal HTML5 <video> fallback + poora custom control UI
// (neeche wala code) hi chalta hai — koi behavior change nahi.
function detectNativeBridge() {
  return !!(window.AndroidPlayer && typeof window.AndroidPlayer.mount === 'function')
}
// ROOT CAUSE (offline download playback used to fall back to a plain web
// player): the native AndroidPlayer bridge is an app-side ExoPlayer instance —
// it can only be handed a real fetchable URI (http/https/file/content). It has
// no way to read a `blob:` URL, because blob: URLs only exist inside this
// WebView's own in-memory registry, unreachable from the native/app process.
// FIX: inside the app, downloads are now saved to a real file natively
// (window.AndroidDownloader) and resolve to a `content://` URI (see
// lib/downloadsStore.js -> getDownloadPlaybackSrc), so offline playback goes
// through the exact same native bridge — same equalizer/cast/decoder-select/
// PiP-featured player — as online streaming. `blob:` URLs only remain as a
// fallback for plain-browser (no native app) usage, where there's no
// AndroidPlayer bridge to begin with; isBlobSrc() below exists purely to keep
// that browser-only path on the web <video> controls instead of trying (and
// silently failing) to hand a blob: URI to a native bridge that isn't there.
function isBlobSrc(src) {
  return typeof src === 'string' && src.startsWith('blob:')
}
export default function VideoPlayer({ src, poster, title, onEnded, qualities, activeQuality, onQualityChange, startAt, onProgressTick, onFatalError, onPlayStateChange, ambientEnabled }) {
  const { t } = useLanguage()
  const isNative = useRef(detectNativeBridge() && !isBlobSrc(src)).current
  const videoRef = useRef(null)
  const containerRef = useRef(null)
  const slotRef = useRef(null)
  const progressRef = useRef(null)
  const hideTimer = useRef(null)
  const lastTap = useRef({ time: 0, side: null })
  const scrubbing = useRef(false)

  const [playing, setPlaying] = useState(false)
  const [duration, setDuration] = useState(0)
  const [current, setCurrent] = useState(0)
  const [buffered, setBuffered] = useState(0)
  const [muted, setMuted] = useState(false)
  const [speed, setSpeed] = useState(1)
  const [fullscreen, setFullscreen] = useState(false)
  const [buffering, setBuffering] = useState(true)
  const [showControls, setShowControls] = useState(true)
  const [skipPulse, setSkipPulse] = useState(null) // 'left' | 'right' | null
  const [qualityMenuOpen, setQualityMenuOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [speedSheetOpen, setSpeedSheetOpen] = useState(false)

  // --- Picture-in-Picture (mini player) state --------------------------------
  const [isPip, setIsPip] = useState(false)
  const [pipRect, setPipRect] = useState(null) // {top,left,width,height,anim}
  // BUG FIX (user ne pakda — "PiP ke X pe click karne par wapas upar bhari
  // screen mein khul jaata hai"): PiP mini-player ke "X" (close) aur "▲"
  // (expand) button — dono ke onClick pehle `exitPip` par hi wired the. Lekin
  // `exitPip()` ka poora kaam hi "wapas bade/original player mein expand karo"
  // hai — woh "close/off" nahi karta, sirf mini-player ko bada karke wahi jagah
  // (jahan se PiP shuru hui thi) restore kar deta hai. Isliye X dabane par bhi
  // video expand ho ke chalta rehta tha, kabhi "off" nahi hota tha — bilkul
  // jaisa user ne report kiya.
  // Fix: ek naya `closed` state + `closePip()` function — yeh video ko poori
  // tarah pause kar deta hai aur poora player area hi hata deta hai (khaali
  // black box), bilkul back-button jaisa "sab band". Sirf "X" is naye function
  // ko call karta hai ab; "▲" (expand) pehle jaisa `exitPip` hi use karta
  // rehta hai.
  const [closed, setClosed] = useState(false)
  const pipDrag = useRef(null) // pointer-drag-to-reposition state, once already in PiP
  const swipeDrag = useRef(null) // pointer-drag state for swipe-down-to-PiP gesture
  const [dragY, setDragY] = useState(0)
  const dragging = useRef(false)
  const suppressClick = useRef(false)

  const scheduleHide = useCallback(() => {
    clearTimeout(hideTimer.current)
    hideTimer.current = setTimeout(() => {
      if (isNative ? playing : videoRef.current && !videoRef.current.paused) {
        setShowControls(false)
        setQualityMenuOpen(false)
        setSettingsOpen(false)
      }
    }, HIDE_DELAY)
  }, [isNative, playing])

  const wake = useCallback(() => {
    setShowControls(true)
    scheduleHide()
  }, [scheduleHide])

  // --- Web fallback: wire up the real <video> element ---------------------
  useEffect(() => {
    if (isNative) return
    const v = videoRef.current
    if (!v) return
    const onTime = () => {
      setCurrent(v.currentTime)
      onProgressTick && onProgressTick(v.currentTime, v.duration || 0)
    }
    const onDur = () => {
      setDuration(v.duration || 0)
      if (startAt > 0) v.currentTime = startAt
    }
    const onProgress = () => {
      if (v.buffered.length) setBuffered(v.buffered.end(v.buffered.length - 1))
    }
    const onWait = () => setBuffering(true)
    const onPlaying = () => setBuffering(false)
    // FEATURE (user ask: "stream hote waqt download queue mein chala jaega,
    // watch band karte hi apne aap shuru ho jaega"): plain-browser (no
    // native bridge) fallback ke liye watching-state signal yahin se jaata
    // hai — native/inline player ke liye yahi kaam MainActivity.kt ka
    // `notifyWatchingChanged` karta hai (dekho downloadsStore.js ke
    // `window.__nativeWatchingChanged`).
    const onPlay = () => { setPlaying(true); scheduleHide(); onPlayStateChange && onPlayStateChange(true) }
    const onPause = () => { setPlaying(false); setShowControls(true); clearTimeout(hideTimer.current); onPlayStateChange && onPlayStateChange(false) }
    const onEnd = () => { setPlaying(false); setShowControls(true); onPlayStateChange && onPlayStateChange(false); onEnded && onEnded() }
    // Drive-sourced streams can fail to extract server-side (unofficial method,
    // Google can restrict it per-file). The backend then answers the /dl/ request
    // with an error status instead of a redirect to the real video bytes, which
    // makes the <video> element fire a plain "error" event with no useful detail
    // of its own. Bubble it up so the page can probe *why* and fall back to an
    // iframe embed of Drive's own preview player if that's the reason.
    const onErr = () => { onFatalError && onFatalError() }

    v.addEventListener('timeupdate', onTime)
    v.addEventListener('loadedmetadata', onDur)
    v.addEventListener('durationchange', onDur)
    v.addEventListener('progress', onProgress)
    v.addEventListener('waiting', onWait)
    v.addEventListener('playing', onPlaying)
    v.addEventListener('canplay', onPlaying)
    v.addEventListener('play', onPlay)
    v.addEventListener('pause', onPause)
    v.addEventListener('ended', onEnd)
    v.addEventListener('error', onErr)
    return () => {
      v.removeEventListener('timeupdate', onTime)
      v.removeEventListener('loadedmetadata', onDur)
      v.removeEventListener('durationchange', onDur)
      v.removeEventListener('progress', onProgress)
      v.removeEventListener('waiting', onWait)
      v.removeEventListener('playing', onPlaying)
      v.removeEventListener('canplay', onPlaying)
      v.removeEventListener('play', onPlay)
      v.removeEventListener('pause', onPause)
      v.removeEventListener('ended', onEnd)
      v.removeEventListener('error', onErr)
      // Player page chhod diya / video source badla beech playback mein hi —
      // ab watching definitely band, download resume ho sakta hai.
      onPlayStateChange && onPlayStateChange(false)
    }
  }, [isNative, scheduleHide, onEnded, onFatalError, onPlayStateChange])

  // Native player ko qualities bhejne ke liye {url, label} tak trim kar do —
  // baaki stream metadata (title/size/etc.) native side ko nahi chahiye.
  const qualityPayload = useCallback(
    () => JSON.stringify((qualities || []).map((q) => ({ url: q.url, label: q.label }))),
    [qualities]
  )

  // --- Native bridge: chhota inline player mount/unmount karo har naye src par ---
  // BUG FIX (user ne pakda — "PiP off wala system website pe add nahi kiya"):
  // pehle website sirf apni taraf se mount()/unmount() call karti thi (naya src
  // aane par ya component/page chhodne par) — lekin native side (MainActivity)
  // kabhi bhi website ko yeh nahi batata tha ki uska poora player genuinely
  // "cut"/off ho gaya hai (PiP "X" se band karne par — dekho
  // MainActivity.onActivityResult()'s `pipGenuinelyClosed` branch). Result:
  // native taraf player release ho jaata, lekin website ko lagta rehta ki uska
  // `window.AndroidPlayer.mount()` call abhi bhi zinda/mounted hai — is div ki
  // jagah khaali reh jaati, play/scroll-back-in par kuch resume nahi hota, jab
  // tak page reload na ho.
  // Fix: native ab `window.__suhaniOnNativeClosed()` call karta hai jab bhi
  // genuinely poora cut kare. Yahan is signal ko sunte hain aur ek fresh
  // `mount()` trigger karte hain taaki chhota player is jagah dobara zinda ho
  // jaaye (naye sire se — jaisa back-button/off ke baad hona chahiye).
  const [nativeCloseTick, setNativeCloseTick] = useState(0)
  useEffect(() => {
    if (!isNative) return
    window.__suhaniOnNativeClosed = () => setNativeCloseTick((t) => t + 1)
    return () => {
      delete window.__suhaniOnNativeClosed
    }
  }, [isNative])

  useEffect(() => {
    if (!isNative || !src) return
    // PERMANENT FIX (native side: window.AndroidPlayer.mount() ab ek 4th
    // param — currentPath — bhi leta hai): Android WebView ka apna `.url`
    // getter Chromium ke renderer<->browser IPC ke through async update hota
    // hai, isliye pehli baar video par tap karne jaisa "navigate() + turant
    // isi tick mein mount()" wale fast case mein native side ko kabhi-kabhi
    // abhi bhi PURANA path dikhta tha (race) — result: native player play to
    // hota tha (audio sunayi deta), lekin visual overlay kabhi VISIBLE nahi
    // hota (root-caused ek pichle PiP/Home-bleed fix se, jo overlay dikhane
    // se pehle "kya hum genuinely /watch/ page par hain" confirm karta hai).
    // Fix: yahan JS khud apna already-accurate `window.location.pathname`
    // (history.pushState turant/synchronously update karta hai, koi IPC-lag
    // nahi) bhi bhej deta hai — native ab is par bharosa karta hai, apne
    // racy `webView.url` ke bajaye.
    window.AndroidPlayer.mount(src, title || 'Video', qualityPayload(), window.location.pathname)
    return () => {
      window.AndroidPlayer.unmount && window.AndroidPlayer.unmount()
    }
  }, [isNative, src, title, qualityPayload, nativeCloseTick])

  // BUG FIX (user report: "video ke andar wala Ambient mode on/off nahi ho
  // raha, bahar wala ho raha hai"): the web page's Ambient toggle (Player.jsx)
  // only ever controlled the CSS blur-glow bleeding above/below the sticky
  // player container — it had no way to reach the *native* in-video ambient
  // glow (AmbientGlowView, rendered by the Android app itself behind the
  // actual video surface). That native glow only ever read its own
  // independent SharedPreferences default at mount time. Now every mount
  // AND every ambientEnabled change is pushed to native via a new
  // `setAmbientEnabled` bridge method (mirrors setThemeColor's pattern in
  // ThemeContext.jsx), so the one toggle genuinely controls both.
  useEffect(() => {
    if (!isNative) return
    window.AndroidPlayer.setAmbientEnabled && window.AndroidPlayer.setAmbientEnabled(ambientEnabled !== false)
  }, [isNative, ambientEnabled, src])

  // --- Native bridge: chhote player ko is div ki exact jagah par chipkaaye rakho ---
  //
  // PERF FIX (user ask: "poora app fast/smooth banao"): pehle sendRect()
  // seedha `scroll`/`resize` event par call hota tha — koi throttling nahi.
  // Jab bhi mini-player Home/Detail ke peeche visible rehta (background
  // playback), har scroll pixel par getBoundingClientRect() (jo layout
  // force karta hai) + ek native-bridge call chal jaata — is wajah se
  // scrolling jhatkedaar (janky) lagta tha, khaaskar rails scroll karte
  // waqt. Fix: rAF se throttle — ek scroll "burst" mein chaahe 50 event fire
  // ho, hum sirf agle paint frame se pehle EK baar hi rect bhejte hain.
  useEffect(() => {
    if (!isNative) return
    const el = containerRef.current
    if (!el) return
    let rafId = null
    function sendRect() {
      const r = el.getBoundingClientRect()
      // PERMANENT FIX: dekho upar wale mount() effect ka comment — yahan bhi
      // wahi accurate window.location.pathname bhejte hain, taaki
      // updateInlinePlayerRect() (jo PiP-return ke baad overlay dobara
      // dikhane ka asli trigger hai) native racy webView.url par bharosa na
      // kare.
      window.AndroidPlayer.updateRect(r.left, r.top, r.width, r.height, window.location.pathname)
    }
    function scheduleSendRect() {
      if (rafId != null) return
      rafId = requestAnimationFrame(() => {
        rafId = null
        sendRect()
      })
    }
    sendRect()
    const ro = new ResizeObserver(scheduleSendRect)
    ro.observe(el)
    window.addEventListener('scroll', scheduleSendRect, { capture: true, passive: true })
    window.addEventListener('resize', scheduleSendRect)
    return () => {
      if (rafId != null) cancelAnimationFrame(rafId)
      ro.disconnect()
      window.removeEventListener('scroll', scheduleSendRect, true)
      window.removeEventListener('resize', scheduleSendRect)
    }
  }, [isNative])

  useEffect(() => {
    const onFsChange = () => setFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', onFsChange)
    return () => {
      document.removeEventListener('fullscreenchange', onFsChange)
      // BUG FIX (black screen after navigating away mid-video — e.g. hitting
      // Home right after watching an offline download): if this player's own
      // container was the browser's real Fullscreen-API element and the user
      // navigates (SPA route change) instead of tapping the fullscreen-exit
      // button, this div gets unmounted while still "the" fullscreen element.
      // WebView/Chromium can be left compositing a black fullscreen layer
      // with nothing in it until something explicitly exits — no fullscreen
      // button exists anymore once we're unmounted, so it would otherwise
      // stay stuck black. Exiting here, on unmount, guarantees it's always
      // cleared no matter how the player leaves the screen.
      if (document.fullscreenElement === containerRef.current) {
        document.exitFullscreen?.().catch(() => {})
      }
    }
  }, [])

  function togglePlay() {
    const v = videoRef.current
    if (!v) return
    if (v.paused) v.play().catch(() => {})
    else v.pause()
  }

  function skip(sec) {
    const v = videoRef.current
    if (!v) return
    const max = duration || v.duration || Infinity
    v.currentTime = Math.min(Math.max(v.currentTime + sec, 0), max)
    setSkipPulse(sec < 0 ? 'left' : 'right')
    setTimeout(() => setSkipPulse(null), 600)
  }

  function toggleMute() {
    const next = !muted
    videoRef.current.muted = next
    setMuted(next)
    wake()
  }

  function applySpeed(next) {
    const v = videoRef.current
    const clamped = Math.min(3, Math.max(0.25, Number(next.toFixed(2))))
    if (v) v.playbackRate = clamped
    setSpeed(clamped)
    wake()
  }

  function selectQuality(q) {
    setQualityMenuOpen(false)
    if (q === activeQuality) return
    // Parent remounts this component with the new src (key={active.url} in
    // Player.jsx) and passes startAt from the tracked progress, so both the
    // native engine and the <video> fallback resume at the same spot.
    onQualityChange && onQualityChange(q)
  }

  function toggleFullscreen() {
    const el = containerRef.current
    if (!document.fullscreenElement) el.requestFullscreen?.()
    else document.exitFullscreen?.()
    wake()
  }

  function handleZoneTap(side) {
    if (suppressClick.current) {
      suppressClick.current = false
      return
    }
    if (side === 'center') {
      togglePlay()
      wake()
      return
    }
    const now = Date.now()
    if (now - lastTap.current.time < DOUBLE_TAP_WINDOW && lastTap.current.side === side) {
      skip(side === 'left' ? -10 : 10)
      lastTap.current = { time: 0, side: null }
    } else {
      lastTap.current = { time: now, side }
      setShowControls((s) => !s)
      scheduleHide()
    }
  }

  function seekFromClientX(clientX) {
    const bar = progressRef.current
    if (!bar) return
    const rect = bar.getBoundingClientRect()
    const frac = Math.min(Math.max((clientX - rect.left) / rect.width, 0), 1)
    const dur = duration || videoRef.current?.duration || 0
    const target = frac * dur
    videoRef.current.currentTime = target
    setCurrent(target)
  }

  function onScrubStart(e) {
    scrubbing.current = true
    seekFromClientX(e.clientX)
    wake()
  }

  useEffect(() => {
    function onMove(e) {
      if (!scrubbing.current) return
      seekFromClientX(e.clientX)
    }
    function onUp() {
      scrubbing.current = false
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
  }, [duration])

  // ---------------------------------------------------------------------------
  // PICTURE-IN-PICTURE (mini player) — YouTube-jaisa
  // ---------------------------------------------------------------------------
  // Trigger 1: top-left chevron-down icon par tap
  // Trigger 2: video ko hold karke neeche ki taraf drag/push karna (swipe-down)
  //
  // Smooth "FLIP" transition: chhota hone se pehle current on-screen rect
  // measure karke wahi rect par ek invisible fixed div "commit" karte hain
  // (koi visual jump nahi), fir agle frame mein target (bottom-right corner)
  // rect par animate kar dete hain — CSS transition khud smoothly shrink
  // dikhata hai. Wapas expand karte waqt bhi wahi ulta hota hai.
  function enterPip() {
    if (isPip || !containerRef.current) return
    const r = containerRef.current.getBoundingClientRect()
    setDragY(0)
    setPipRect({ top: r.top, left: r.left, width: r.width, height: r.height, anim: false })
    setIsPip(true)
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        setPipRect({
          top: window.innerHeight - PIP_H - PIP_MARGIN,
          left: window.innerWidth - PIP_W - PIP_MARGIN,
          width: PIP_W,
          height: PIP_H,
          anim: true,
        })
      })
    })
  }

  function exitPip() {
    if (!isPip) return
    const slot = slotRef.current
    const target = slot
      ? slot.getBoundingClientRect()
      : { top: 80, left: 12, width: window.innerWidth - 24, height: (window.innerWidth - 24) * 0.5625 }
    setPipRect({ top: target.top, left: target.left, width: target.width, height: target.height, anim: true })
    setTimeout(() => {
      setIsPip(false)
      setPipRect(null)
    }, FLIP_MS)
  }

  // "X" button — poora band karo (back-button jaisa "sab off"), expand mat
  // karo. Video ko turant pause karo taaki background mein audio na chale,
  // fir poora player hi hata do.
  function closePip() {
    const v = videoRef.current
    if (v) v.pause()
    setPipRect(null)
    setIsPip(false)
    setClosed(true)
  }

  // Drag-to-reposition the mini player once it's already docked as PiP
  function onPipPointerDown(e) {
    if (!isPip) return
    e.stopPropagation()
    pipDrag.current = {
      startX: e.clientX,
      startY: e.clientY,
      top: pipRect.top,
      left: pipRect.left,
      moved: false,
    }
    window.addEventListener('pointermove', onPipPointerMove)
    window.addEventListener('pointerup', onPipPointerUp)
  }
  function onPipPointerMove(e) {
    const d = pipDrag.current
    if (!d) return
    const dx = e.clientX - d.startX
    const dy = e.clientY - d.startY
    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) d.moved = true
    const left = Math.min(Math.max(d.left + dx, PIP_MARGIN), window.innerWidth - PIP_W - PIP_MARGIN)
    const top = Math.min(Math.max(d.top + dy, PIP_MARGIN), window.innerHeight - PIP_H - PIP_MARGIN)
    setPipRect((r) => ({ ...r, top, left, anim: false }))
  }
  function onPipPointerUp() {
    if (pipDrag.current && !pipDrag.current.moved) {
      // treated as a tap, not a drag — let click handlers (play/pause) fire
    }
    pipDrag.current = null
    window.removeEventListener('pointermove', onPipPointerMove)
    window.removeEventListener('pointerup', onPipPointerUp)
  }

  // "Video ko hold karke neeche push karo" — swipe-down gesture anywhere on
  // the (non-PiP) player to shrink it into the mini player.
  function onSwipePointerDown(e) {
    if (isPip || e.button === 2) return
    swipeDrag.current = { startX: e.clientX, startY: e.clientY, moved: false }
  }
  function onSwipePointerMove(e) {
    const d = swipeDrag.current
    if (!d) return
    const dy = e.clientY - d.startY
    const dx = Math.abs(e.clientX - d.startX)
    if (dy < 6 || dx > Math.max(40, dy)) return // ignore small jitter / mostly-horizontal moves
    if (!dragging.current) dragging.current = true
    d.moved = true
    setDragY(Math.min(dy, 260))
  }
  function onSwipePointerUp() {
    const d = swipeDrag.current
    swipeDrag.current = null
    if (!dragging.current) return
    dragging.current = false
    if (d && d.moved) suppressClick.current = true
    if (dragY > DRAG_PIP_THRESHOLD) {
      // Measure the currently (visually dragged) rect BEFORE clearing the
      // drag transform, then hand off to enterPip's own FLIP so there is
      // zero visible jump between "being dragged" and "docked as PiP".
      const r = containerRef.current.getBoundingClientRect()
      setDragY(0)
      setPipRect({ top: r.top, left: r.left, width: r.width, height: r.height, anim: false })
      setIsPip(true)
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          setPipRect({
            top: window.innerHeight - PIP_H - PIP_MARGIN,
            left: window.innerWidth - PIP_W - PIP_MARGIN,
            width: PIP_W,
            height: PIP_H,
            anim: true,
          })
        })
      })
    } else {
      setDragY(0) // spring back — CSS transition animates it smoothly
    }
  }

  useEffect(() => {
    window.addEventListener('pointermove', onSwipePointerMove)
    window.addEventListener('pointerup', onSwipePointerUp)
    return () => {
      window.removeEventListener('pointermove', onSwipePointerMove)
      window.removeEventListener('pointerup', onSwipePointerUp)
    }
  }, [dragY])

  // Keep the mini player inside the viewport on window resize.
  useEffect(() => {
    if (!isPip) return
    function onResize() {
      setPipRect((r) => r && ({
        ...r,
        top: Math.min(r.top, window.innerHeight - PIP_H - PIP_MARGIN),
        left: Math.min(r.left, window.innerWidth - PIP_W - PIP_MARGIN),
      }))
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [isPip])

  const progressPct = duration ? Math.min((current / duration) * 100, 100) : 0
  const bufferedPct = duration ? Math.min((buffered / duration) * 100, 100) : 0

  // App ke andar: is div ki exact jagah par chhota native player khud chipak
  // (overlay ho) jaata hai (mount/updateRect effects se) — isliye yahan sirf
  // ek khaali placeholder chahiye, apna koi control/UI nahi.
  if (isNative) {
    return <div ref={containerRef} className="relative w-full h-full bg-black" />
  }

  // BUG FIX: "X" se genuinely band karne ke baad poora player hata do — bilkul
  // back-button jaisa "sab off". Khaali black box, na video na controls, jab
  // tak naya src na aaye (parent naya `key` de kar remount karega).
  if (closed) {
    return <div ref={containerRef} className="relative w-full h-full bg-black" />
  }

  const dragScale = 1 - Math.min(dragY / 900, 0.16)
  const dragOpacity = 1 - Math.min(dragY / 500, 0.35)

  return (
    <>
      {/* Reserves the original spot in the page while the player floats as PiP,
          so expanding back can FLIP-animate to exactly the right place. */}
      <div ref={slotRef} className={isPip ? 'w-full h-full bg-black' : 'hidden'} />

      <div
        ref={containerRef}
        className={`${isPip ? 'fixed' : 'relative'} w-full h-full bg-black select-none overflow-hidden ${
          isPip ? 'rounded-xl shadow-[0_12px_32px_rgba(0,0,0,0.6)] ring-1 ring-white/10 z-[70]' : 'z-0'
        }`}
        style={
          isPip
            ? {
                top: pipRect?.top,
                left: pipRect?.left,
                width: pipRect?.width,
                height: pipRect?.height,
                transition: pipRect?.anim ? `top ${FLIP_MS}ms cubic-bezier(.2,.8,.2,1), left ${FLIP_MS}ms cubic-bezier(.2,.8,.2,1), width ${FLIP_MS}ms cubic-bezier(.2,.8,.2,1), height ${FLIP_MS}ms cubic-bezier(.2,.8,.2,1)` : 'none',
              }
            : {
                transform: dragY ? `translateY(${dragY}px) scale(${dragScale})` : 'none',
                opacity: dragOpacity,
                transition: dragging.current ? 'none' : 'transform 260ms cubic-bezier(.2,.8,.2,1), opacity 260ms',
              }
        }
        onMouseMove={wake}
        onPointerDown={!isPip ? onSwipePointerDown : onPipPointerDown}
      >
        <video
          ref={videoRef}
          src={src}
          poster={poster}
          playsInline
          autoPlay
          className="w-full h-full pointer-events-none"
        />

        {buffering && (
          <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
            <div className={`${isPip ? 'w-5 h-5 border-2' : 'w-10 h-10 border-[3px]'} border-white/20 border-t-reel-gold rounded-full animate-spin`} />
          </div>
        )}

        {isPip ? (
          // ---------------- Mini player controls ----------------
          <>
            <button
              onClick={() => handleZoneTap('center')}
              aria-label={playing ? 'Pause' : 'Play'}
              className="absolute inset-0 flex items-center justify-center"
            >
              {!playing && (
                <div className="w-8 h-8 rounded-full bg-black/60 flex items-center justify-center">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="#fff"><path d="M8 5v14l11-7z" /></svg>
                </div>
              )}
            </button>
            <button
              onClick={closePip}
              aria-label="Close mini player"
              className="absolute top-1 right-1 w-6 h-6 rounded-full bg-black/60 flex items-center justify-center text-white active:scale-90 transition"
              title="Band karo"
            >
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
            </button>
            <button
              onClick={exitPip}
              aria-label="Expand player"
              className="absolute top-1 left-1 w-6 h-6 rounded-full bg-black/60 flex items-center justify-center text-white active:scale-90 transition"
              title="Bada karo"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="m18 15-6-6-6 6" /></svg>
            </button>
          </>
        ) : (
          <>
            {/* Tap zones — left/right double-tap to skip, center toggles play */}
            <div className="absolute inset-0 grid grid-cols-3">
              <div onClick={() => handleZoneTap('left')} />
              <div onClick={() => handleZoneTap('center')} />
              <div onClick={() => handleZoneTap('right')} />
            </div>

            {skipPulse && (
              <div
                className={`absolute top-1/2 -translate-y-1/2 flex items-center gap-1.5 bg-black/75 text-reel-ink text-xs font-semibold px-3 py-1.5 rounded-full pointer-events-none animate-skip-pulse ${
                  skipPulse === 'left' ? 'left-5' : 'right-5'
                }`}
              >
                {skipPulse === 'left' ? (
                  <>
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z" /></svg>
                    10s
                  </>
                ) : (
                  <>
                    10s
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M13 6v12l8.5-6L13 6zm-.5 6L4 6v12l8.5-6z" /></svg>
                  </>
                )}
              </div>
            )}

            {!playing && !buffering && (
              <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                <div className="w-16 h-16 rounded-full bg-black/35 backdrop-blur-sm ring-1 ring-white/15 flex items-center justify-center shadow-[0_8px_24px_rgba(0,0,0,0.45)] animate-toast-in">
                  <div className="w-12 h-12 rounded-full bg-reel-gold flex items-center justify-center shadow-[0_0_18px_rgba(232,163,61,0.55)]">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="#0B0B12"><path d="M8 5v14l11-7z" /></svg>
                  </div>
                </div>
              </div>
            )}

            {/* Thin YouTube-style progress line — flush with the video's
                bottom edge, this is the ONLY progress indicator visible
                while playing quietly (controls auto-hidden). The full rich
                scrub bar below (inside the Controls panel) takes over the
                instant controls are shown — this one hides then so the two
                never show at once. */}
            {!showControls && (
              <div className="absolute inset-x-0 bottom-0 h-[2px] bg-white/25 pointer-events-none z-10">
                <div className="h-full bg-white" style={{ width: `${progressPct}%` }} />
              </div>
            )}

            {/* Top bar — chevron-down = shrink into mini player (PiP); title
                shown next to it once controls are visible, YouTube-style. */}
            <div
              className={`absolute inset-x-0 top-0 px-3 pt-3 pb-10 bg-gradient-to-b from-black/75 via-black/25 to-transparent transition-opacity duration-200 ${
                showControls ? 'opacity-100' : 'opacity-0 pointer-events-none'
              }`}
            >
              <div className="flex items-center gap-2.5">
                <button
                  onClick={enterPip}
                  aria-label="Switch to mini player"
                  className="w-8 h-8 shrink-0 rounded-full bg-black/40 backdrop-blur-sm flex items-center justify-center text-reel-ink active:scale-90 transition"
                  title="Mini player"
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
                </button>
                {title ? (
                  <p className="min-w-0 flex-1 truncate text-xs font-medium text-reel-ink/90 drop-shadow-[0_1px_2px_rgba(0,0,0,0.8)]">
                    {title}
                  </p>
                ) : null}
              </div>
            </div>

            {/* Controls
                LAYOUT FIX (user ask, with YouTube screenshots: "time bar
                video ke niche ke sath laga rehna chahiye, aur jab play ho
                tab bhi bas ek patla sa white bar dikhna chahiye"): the scrub
                bar used to render ABOVE the play/mute/quality/fullscreen row
                with padding below that row, so it never actually touched the
                video's bottom edge. Swapped the order (icon row first, scrub
                bar last) and dropped the wrapper's bottom padding so the bar
                is now the literal last pixel row of the video, exactly like
                YouTube's reference screenshots — the icon row keeps its own
                bottom padding instead so it doesn't visually collide with
                the bar sitting right under it. */}
            <div
              className={`absolute inset-x-0 bottom-0 px-3 pt-10 bg-gradient-to-t from-black/90 via-black/40 to-transparent transition-opacity duration-200 ${
                showControls ? 'opacity-100' : 'opacity-0 pointer-events-none'
              }`}
            >
              <div className="flex items-center gap-3 pb-2 text-reel-ink">
                <button onClick={() => handleZoneTap('center')} aria-label={playing ? 'Pause' : 'Play'} className="p-1 shrink-0 active:scale-90 transition">
                  {playing ? (
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h4v14H6zM14 5h4v14h-4z" /></svg>
                  ) : (
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
                  )}
                </button>

                <span className="text-xs tabular-nums text-reel-muted whitespace-nowrap">
                  {fmt(current)} / {fmt(duration)}
                </span>

                <div className="flex-1" />

                <button onClick={toggleMute} aria-label={muted ? 'Unmute' : 'Mute'} className="p-1 shrink-0 active:scale-90 transition">
                  {muted ? (
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M16.5 12A4.5 4.5 0 0 0 14 8v2.2l2.45 2.45c.03-.2.05-.43.05-.65zm2.5 0c0 .94-.2 1.82-.54 2.63l1.51 1.51A8.8 8.8 0 0 0 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3 3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06a9 9 0 0 0 3.69-1.81L19.73 21 21 19.73 4.27 3zM12 4 9.91 6.09 12 8.18V4z" /></svg>
                  ) : (
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z" /></svg>
                  )}
                </button>

                {qualities && qualities.length > 1 && (
                  <div className="relative shrink-0">
                    <button
                      onClick={() => { setQualityMenuOpen((s) => !s); setSettingsOpen(false); wake() }}
                      aria-label="Change video quality"
                      aria-expanded={qualityMenuOpen}
                      className={`flex items-center gap-1 text-xs font-semibold px-2 py-1 rounded transition active:scale-95 ${
                        qualityMenuOpen ? 'bg-reel-gold text-reel-bg' : 'bg-white/10 text-reel-ink'
                      }`}
                    >
                      {activeQuality?.label || t('player_quality')}
                    </button>

                    {qualityMenuOpen && (
                      <div className="absolute bottom-full right-0 mb-2 min-w-[132px] rounded-xl overflow-hidden backdrop-blur-md bg-reel-surface/95 ring-1 ring-reel-gold/25 shadow-[0_8px_24px_rgba(0,0,0,0.5)]">
                        <p className="text-[10px] uppercase tracking-wide text-reel-muted px-3 pt-2 pb-1">{t('player_quality')}</p>
                        {qualities.map((q, i) => (
                          <button
                            key={i}
                            onClick={() => selectQuality(q)}
                            className={`w-full flex items-center justify-between gap-2 text-left px-3 py-2 text-xs transition ${
                              q === activeQuality ? 'text-reel-gold font-semibold' : 'text-reel-ink hover:bg-white/5'
                            }`}
                          >
                            {q.label}
                            {q === activeQuality && (
                              <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"/></svg>
                            )}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                )}

                {/* Settings gear -> "Playback speed" -> premium dark speed sheet */}
                <div className="relative shrink-0">
                  <button
                    onClick={() => { setSettingsOpen((s) => !s); setQualityMenuOpen(false); wake() }}
                    aria-label="Settings"
                    aria-expanded={settingsOpen}
                    className={`p-1.5 rounded-full transition active:scale-90 ${settingsOpen ? 'bg-white/15' : ''}`}
                    title="Settings"
                  >
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="3" />
                      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
                    </svg>
                  </button>

                  {settingsOpen && (
                    <div className="absolute bottom-full right-0 mb-2 min-w-[172px] rounded-xl overflow-hidden bg-reel-bg/97 backdrop-blur-md ring-1 ring-white/10 shadow-[0_8px_28px_rgba(0,0,0,0.65)]">
                      <button
                        onClick={() => { setSpeedSheetOpen(true); setSettingsOpen(false) }}
                        className="w-full flex items-center justify-between gap-3 px-3.5 py-3 text-xs text-reel-ink hover:bg-white/5"
                      >
                        <span className="flex items-center gap-2">
                          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
                          {t('player_playback_speed')}
                        </span>
                        <span className="text-reel-muted">{speed === 1 ? t('player_normal') : `${speed}x`} ›</span>
                      </button>
                    </div>
                  )}
                </div>

                <button onClick={toggleFullscreen} aria-label={fullscreen ? 'Exit fullscreen' : 'Enter fullscreen'} className="p-1 shrink-0 active:scale-90 transition">
                  {fullscreen ? (
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z" /></svg>
                  ) : (
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z" /></svg>
                  )}
                </button>
              </div>

              <div
                ref={progressRef}
                onPointerDown={onScrubStart}
                className="group relative h-4 -mt-2 flex items-end pb-[6px] cursor-pointer touch-none"
              >
                <div className="absolute inset-x-0 bottom-[6px] h-[3px] rounded-full bg-white/25 transition-all group-active:h-[5px]" />
                <div className="absolute bottom-[6px] h-[3px] rounded-full bg-white/45 transition-all group-active:h-[5px]" style={{ width: `${bufferedPct}%` }} />
                <div
                  className="absolute bottom-[6px] h-[3px] rounded-full bg-gradient-to-r from-reel-gold to-amber-300 shadow-[0_0_8px_rgba(232,163,61,0.65)] transition-all group-active:h-[5px]"
                  style={{ width: `${progressPct}%` }}
                />
                <div
                  className="absolute bottom-[3.5px] w-3.5 h-3.5 rounded-full bg-reel-gold ring-2 ring-white/80 shadow-[0_1px_4px_rgba(0,0,0,0.5)] transition-transform group-active:scale-125"
                  style={{ left: `calc(${progressPct}% - 7px)` }}
                />
              </div>
            </div>
          </>
        )}
      </div>

      {/* Premium dark playback-speed bottom sheet (YouTube-style) */}
      {speedSheetOpen && !isPip && (
        <div
          className="fixed inset-0 z-[90] flex items-end justify-center"
          onClick={() => setSpeedSheetOpen(false)}
        >
          <div className="absolute inset-0 bg-black/60" />
          <div
            onClick={(e) => e.stopPropagation()}
            className="relative w-full max-w-md bg-reel-bg rounded-t-2xl pt-3 pb-[calc(1.5rem+env(safe-area-inset-bottom))] px-5 ring-1 ring-white/10 shadow-[0_-8px_32px_rgba(0,0,0,0.7)]"
          >
            <div className="w-10 h-1 rounded-full bg-white/15 mx-auto mb-4" />
            <p className="text-center text-reel-ink text-xl font-semibold tabular-nums mb-4">{speed.toFixed(2)}x</p>

            <div className="flex items-center gap-4 mb-5">
              <button
                onClick={() => applySpeed(speed - 0.25)}
                aria-label="Decrease speed"
                className="w-9 h-9 rounded-full bg-white/10 flex items-center justify-center text-reel-ink text-lg leading-none active:scale-90 transition"
              >
                −
              </button>
              <input
                type="range"
                min="0.25"
                max="3"
                step="0.05"
                value={speed}
                onChange={(e) => applySpeed(Number(e.target.value))}
                className="flex-1 accent-reel-gold"
              />
              <button
                onClick={() => applySpeed(speed + 0.25)}
                aria-label="Increase speed"
                className="w-9 h-9 rounded-full bg-white/10 flex items-center justify-center text-reel-ink text-lg leading-none active:scale-90 transition"
              >
                +
              </button>
            </div>

            <div className="flex items-center gap-2">
              {SPEEDS.map((s) => (
                <button
                  key={s}
                  onClick={() => applySpeed(s)}
                  className={`flex-1 py-2 rounded-full text-xs font-semibold transition ${
                    speed === s ? 'bg-reel-gold text-reel-bg' : 'bg-white/10 text-reel-ink'
                  }`}
                >
                  {s === 1 ? t('player_normal') : `${s}x`}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
    </>
  )
}
