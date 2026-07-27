import { useCallback, useEffect, useRef, useState } from 'react'

const SPEEDS = [0.5, 1, 1.25, 1.5, 2]
const HIDE_DELAY = 2800
const DOUBLE_TAP_WINDOW = 320

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
// NATIVE INLINE BRIDGE CONTRACT (Sisisisi / A501 Android shell must implement)
// ---------------------------------------------------------------------------
// Isse pehle bridge poore video ko ek alag native Activity mein handoff kar
// deta tha (window.AndroidPlayer.playVideo). Ab bridge sirf ek "engine" hai —
// video *isi* WebView page ke andar, is exact <div> ki jagah pe, ek native
// SurfaceView/TextureView ke through render hota hai (jaise YouTube inline
// embed). Saare custom controls (play/pause, seek, quality, speed, mute,
// fullscreen) yeh React component hi banata/dikhata hai — native sirf decode
// + render + equalizer/gestures jaisा engine-level kaam karta hai.
//
// window.AndroidPlayer must expose:
//   mount(src: string, title: string, poster: string, startAtSeconds: number)
//     -> naya native surface create/attach karo is rect ki jagah par.
//   updateRect(left, top, width, height)  // CSS px, viewport-relative
//     -> jab bhi rect badle (resize/scroll/fullscreen), surface ko wahi
//        exact position/size par move/resize karo.
//   play() / pause()
//   seekTo(seconds)
//   setMuted(bool)
//   setSpeed(rate)
//   setQuality(url)   // resume time bridge khud current position se le
//   destroy()          // unmount / naya video / navigate away
//
// Native → JS callbacks (evaluateJavascript se call karo), window par:
//   window.__nativePlayerBridge.onLoadedMetadata(durationSeconds)
//   window.__nativePlayerBridge.onTimeUpdate(currentSeconds, bufferedEndSeconds)
//   window.__nativePlayerBridge.onPlay()
//   window.__nativePlayerBridge.onPause()
//   window.__nativePlayerBridge.onWaiting()
//   window.__nativePlayerBridge.onPlaying()
//   window.__nativePlayerBridge.onEnded()
//   window.__nativePlayerBridge.onError(message)
//
// IMPORTANT (Android side): native surface ko WebView ke *peeche* (z-order
// mein neeche) add karna hoga, aur WebView ka background sirf is video-rect
// ki jagah transparent hona chahiye (WebView.setBackgroundColor(Color.TRANSPARENT)
// + page ka <html>/<body> background CSS se match) — tabhi yeh upar wale
// HTML controls (progress bar, quality button, etc.) native video ke UPAR
// dikhengे, jaisa neeche render logic assume karta hai.
function detectNativeBridge() {
  return !!(window.AndroidPlayer && typeof window.AndroidPlayer.mount === 'function')
}

// Native <video> stays the playback engine — this just layers custom,
// touch-friendly controls on top of it so the underlying speed/behavior
// is unchanged. Jab native bridge available hai, wahi controls ab native
// engine ko drive karte hain instead of the <video> element.
export default function VideoPlayer({ src, poster, title, onEnded, qualities, activeQuality, onQualityChange, startAt, onProgressTick }) {
  const isNative = useRef(detectNativeBridge()).current
  const videoRef = useRef(null)
  const containerRef = useRef(null)
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

  const scheduleHide = useCallback(() => {
    clearTimeout(hideTimer.current)
    hideTimer.current = setTimeout(() => {
      if (isNative ? playing : videoRef.current && !videoRef.current.paused) {
        setShowControls(false)
        setQualityMenuOpen(false)
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
      onProgressTick && onProgressTick(v.currentTime)
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
    const onPlay = () => { setPlaying(true); scheduleHide() }
    const onPause = () => { setPlaying(false); setShowControls(true); clearTimeout(hideTimer.current) }
    const onEnd = () => { setPlaying(false); setShowControls(true); onEnded && onEnded() }

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
    }
  }, [isNative, scheduleHide, onEnded])

  // --- Native bridge: register callbacks the Android side calls into ------
  useEffect(() => {
    if (!isNative) return
    window.__nativePlayerBridge = {
      onLoadedMetadata: (dur) => setDuration(dur || 0),
      onTimeUpdate: (t, bufEnd) => {
        setCurrent(t || 0)
        if (bufEnd) setBuffered(bufEnd)
        onProgressTick && onProgressTick(t || 0)
      },
      onPlay: () => { setPlaying(true); scheduleHide() },
      onPause: () => { setPlaying(false); setShowControls(true); clearTimeout(hideTimer.current) },
      onWaiting: () => setBuffering(true),
      onPlaying: () => setBuffering(false),
      onEnded: () => { setPlaying(false); setShowControls(true); onEnded && onEnded() },
      onError: () => setBuffering(false),
    }
    return () => { delete window.__nativePlayerBridge }
  }, [isNative, scheduleHide, onEnded, onProgressTick])

  // --- Native bridge: mount/destroy the engine when src changes -----------
  useEffect(() => {
    if (!isNative || !src) return
    setBuffering(true)
    window.AndroidPlayer.mount(src, title || 'Video', poster || '', startAt || 0)
    return () => {
      window.AndroidPlayer.destroy && window.AndroidPlayer.destroy()
    }
  }, [isNative, src])

  // --- Native bridge: keep the on-screen surface glued to this div --------
  useEffect(() => {
    if (!isNative) return
    const el = containerRef.current
    if (!el) return
    function sendRect() {
      const r = el.getBoundingClientRect()
      window.AndroidPlayer.updateRect(r.left, r.top, r.width, r.height)
    }
    sendRect()
    const ro = new ResizeObserver(sendRect)
    ro.observe(el)
    window.addEventListener('scroll', sendRect, true)
    window.addEventListener('resize', sendRect)
    document.addEventListener('fullscreenchange', sendRect)
    return () => {
      ro.disconnect()
      window.removeEventListener('scroll', sendRect, true)
      window.removeEventListener('resize', sendRect)
      document.removeEventListener('fullscreenchange', sendRect)
    }
  }, [isNative])

  useEffect(() => {
    const onFsChange = () => setFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', onFsChange)
    return () => document.removeEventListener('fullscreenchange', onFsChange)
  }, [])

  function togglePlay() {
    if (isNative) {
      if (playing) window.AndroidPlayer.pause()
      else window.AndroidPlayer.play()
      return
    }
    const v = videoRef.current
    if (!v) return
    if (v.paused) v.play().catch(() => {})
    else v.pause()
  }

  function skip(sec) {
    if (isNative) {
      const max = duration || Infinity
      const target = Math.min(Math.max(current + sec, 0), max)
      window.AndroidPlayer.seekTo(target)
      setCurrent(target)
    } else {
      const v = videoRef.current
      if (!v) return
      const max = duration || v.duration || Infinity
      v.currentTime = Math.min(Math.max(v.currentTime + sec, 0), max)
    }
    setSkipPulse(sec < 0 ? 'left' : 'right')
    setTimeout(() => setSkipPulse(null), 600)
  }

  function toggleMute() {
    const next = !muted
    if (isNative) {
      window.AndroidPlayer.setMuted(next)
    } else {
      videoRef.current.muted = next
    }
    setMuted(next)
    wake()
  }

  function cycleSpeed() {
    const idx = SPEEDS.indexOf(speed)
    const next = SPEEDS[(idx + 1) % SPEEDS.length]
    if (isNative) {
      window.AndroidPlayer.setSpeed(next)
    } else {
      videoRef.current.playbackRate = next
    }
    setSpeed(next)
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
    const dur = duration || (isNative ? 0 : videoRef.current?.duration) || 0
    const target = frac * dur
    if (isNative) {
      window.AndroidPlayer.seekTo(target)
    } else {
      videoRef.current.currentTime = target
    }
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

  const progressPct = duration ? Math.min((current / duration) * 100, 100) : 0
  const bufferedPct = duration ? Math.min((buffered / duration) * 100, 100) : 0

  return (
    <div
      ref={containerRef}
      className="relative w-full h-full bg-black select-none overflow-hidden"
      onMouseMove={wake}
    >
      {isNative ? (
        // Native engine (Sisisisi ExoPlayer, gestures/equalizer intact)
        // renders *behind* this transparent div, exactly at its rect —
        // see the bridge contract note above the component.
        <div
          className="w-full h-full bg-center bg-cover"
          style={poster ? { backgroundImage: `url(${poster})` } : undefined}
        />
      ) : (
        <video
          ref={videoRef}
          src={src}
          poster={poster}
          playsInline
          autoPlay
          className="w-full h-full"
        />
      )}

      {buffering && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="w-10 h-10 border-[3px] border-white/20 border-t-reel-gold rounded-full animate-spin" />
        </div>
      )}

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
          <div className="w-14 h-14 rounded-full bg-reel-gold/90 flex items-center justify-center">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="#0B0B12"><path d="M8 5v14l11-7z" /></svg>
          </div>
        </div>
      )}

      {/* Controls */}
      <div
        className={`absolute inset-x-0 bottom-0 px-3 pb-2 pt-10 bg-gradient-to-t from-black/85 via-black/30 to-transparent transition-opacity duration-200 ${
          showControls ? 'opacity-100' : 'opacity-0 pointer-events-none'
        }`}
      >
        <div
          ref={progressRef}
          onPointerDown={onScrubStart}
          className="relative h-4 flex items-center cursor-pointer touch-none"
        >
          <div className="absolute inset-x-0 h-1 rounded-full bg-white/20" />
          <div className="absolute h-1 rounded-full bg-white/40" style={{ width: `${bufferedPct}%` }} />
          <div className="absolute h-1 rounded-full bg-reel-gold" style={{ width: `${progressPct}%` }} />
          <div
            className="absolute w-3 h-3 rounded-full bg-reel-gold shadow"
            style={{ left: `calc(${progressPct}% - 6px)` }}
          />
        </div>

        <div className="flex items-center gap-3 mt-1 text-reel-ink">
          <button onClick={() => handleZoneTap('center')} className="p-1 shrink-0">
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

          <button onClick={toggleMute} className="p-1 shrink-0">
            {muted ? (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M16.5 12A4.5 4.5 0 0 0 14 8v2.2l2.45 2.45c.03-.2.05-.43.05-.65zm2.5 0c0 .94-.2 1.82-.54 2.63l1.51 1.51A8.8 8.8 0 0 0 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3 3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06a9 9 0 0 0 3.69-1.81L19.73 21 21 19.73 4.27 3zM12 4 9.91 6.09 12 8.18V4z" /></svg>
            ) : (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z" /></svg>
            )}
          </button>

          <button onClick={cycleSpeed} className="text-xs font-semibold px-2 py-1 rounded bg-white/10 shrink-0">
            {speed}x
          </button>

          {qualities && qualities.length > 1 && (
            <div className="relative shrink-0">
              <button
                onClick={() => { setQualityMenuOpen((s) => !s); wake() }}
                className={`flex items-center gap-1 text-xs font-semibold px-2 py-1 rounded transition ${
                  qualityMenuOpen ? 'bg-reel-gold text-reel-bg' : 'bg-white/10 text-reel-ink'
                }`}
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="3" />
                  <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
                </svg>
                {activeQuality?.label || 'Quality'}
              </button>

              {qualityMenuOpen && (
                <div className="absolute bottom-full right-0 mb-2 min-w-[132px] rounded-xl overflow-hidden backdrop-blur-md bg-reel-surface/95 ring-1 ring-reel-gold/25 shadow-[0_8px_24px_rgba(0,0,0,0.5)]">
                  <p className="text-[10px] uppercase tracking-wide text-reel-muted px-3 pt-2 pb-1">Quality</p>
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

          <button onClick={toggleFullscreen} className="p-1 shrink-0">
            {fullscreen ? (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z" /></svg>
            ) : (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z" /></svg>
            )}
          </button>
        </div>
      </div>
    </div>
  )
}
