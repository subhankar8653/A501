import { useState, useEffect, useRef, useCallback } from 'react'
import { useParams, useNavigate, useLocation } from 'react-router-dom'
import { getMeta, getStreams } from '../api'

// ── Icons (inline SVGs for zero-dependency reliability) ──
const IconPlay = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
)
const IconPause = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>
)
const IconBack = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>
)
const IconVolumeHigh = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>
)
const IconVolumeMute = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73 4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>
)
const IconForward10 = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M18 13c0 3.31-2.69 6-6 6s-6-2.69-6-6 2.69-6 6-6v4l5-5-5-5v4c-4.42 0-8 3.58-8 8s3.58 8 8 8 8-3.58 8-8h-2zm-2.05 1.65L14 13.5V9h-1v5.34l2.45 1.31.5-.9z"/></svg>
)
const IconReplay10 = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8zm-2.95 9.65L10 13.5V9h1v5.34l-2.45 1.31-.5-.9z"/></svg>
)
const IconFullscreen = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>
)
const IconFullscreenExit = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"/></svg>
)
const IconSettings = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.488.488 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>
)
const IconSpeed = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 4 16.25V19h16v-2.75a10 10 0 0 0 .38-7.68zM12 22a2 2 0 0 1-2-2h4a2 2 0 0 1-2 2z"/></svg>
)
const IconNext = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
)
const IconSpinner = ({ className }) => (
  <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
    <path d="M12 2a10 10 0 0 1 10 10" strokeLinecap="round"/>
  </svg>
)

// ── Helpers ──
const fmtTime = (s) => {
  if (!isFinite(s) || s < 0) return '0:00'
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = Math.floor(s % 60)
  const pad = (n) => n.toString().padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`
}

export default function Player() {
  const { type, id } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const videoRef = useRef(null)
  const containerRef = useRef(null)
  const controlsTimeout = useRef(null)
  const skipAnimTimeout = useRef(null)

  // Data
  const [meta, setMeta] = useState(null)
  const [streams, setStreams] = useState([])
  const [activeStreamIdx, setActiveStreamIdx] = useState(0)
  const [loadingMeta, setLoadingMeta] = useState(true)

  // Playback state
  const [playing, setPlaying] = useState(false)
  const [buffering, setBuffering] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [volume, setVolume] = useState(() => {
    const v = parseFloat(localStorage.getItem('ss:volume'))
    return isNaN(v) ? 1 : v
  })
  const [muted, setMuted] = useState(false)
  const [playbackRate, setPlaybackRate] = useState(1)

  // UI state
  const [showControls, setShowControls] = useState(true)
  const [hoveringControls, setHoveringControls] = useState(false)
  const [showSettings, setShowSettings] = useState(false)
  const [showSpeedMenu, setShowSpeedMenu] = useState(false)
  const [showQualityMenu, setShowQualityMenu] = useState(false)
  const [skipAnim, setSkipAnim] = useState(null) // { dir: 'left'|'right', key: number }
  const [isFullscreen, setIsFullscreen] = useState(false)
  const [ended, setEnded] = useState(false)

  const activeStream = streams[activeStreamIdx]

  // ── Load meta & streams ──
  useEffect(() => {
    let cancelled = false
    async function init() {
      try {
        const [m, s] = await Promise.all([getMeta(type, id), getStreams(type, id)])
        if (cancelled) return
        setMeta(m)
        setStreams(s || [])
      } catch (e) {
        console.error(e)
      } finally {
        if (!cancelled) setLoadingMeta(false)
      }
    }
    init()
    return () => { cancelled = true }
  }, [type, id])

  // ── Restore volume ──
  useEffect(() => {
    const v = videoRef.current
    if (v) {
      v.volume = volume
      v.muted = muted
    }
  }, [volume, muted])

  // ── Keyboard shortcuts ──
  useEffect(() => {
    const onKey = (e) => {
      if (e.target.tagName === 'INPUT') return
      const v = videoRef.current
      if (!v) return
      switch (e.code) {
        case 'Space':
        case 'KeyK':
          e.preventDefault()
          togglePlay()
          break
        case 'ArrowLeft':
        case 'KeyJ':
          e.preventDefault()
          seek(-10)
          break
        case 'ArrowRight':
        case 'KeyL':
          e.preventDefault()
          seek(10)
          break
        case 'ArrowUp':
          e.preventDefault()
          changeVolume(Math.min(1, volume + 0.1))
          break
        case 'ArrowDown':
          e.preventDefault()
          changeVolume(Math.max(0, volume - 0.1))
          break
        case 'KeyF':
          e.preventDefault()
          toggleFullscreen()
          break
        case 'KeyM':
          e.preventDefault()
          toggleMute()
          break
        case 'Home':
          e.preventDefault()
          v.currentTime = 0
          break
        case 'End':
          e.preventDefault()
          v.currentTime = v.duration
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [volume, muted])

  // ── Auto-hide controls ──
  const resetControlsTimeout = useCallback(() => {
    clearTimeout(controlsTimeout.current)
    if (!hoveringControls && playing) {
      controlsTimeout.current = setTimeout(() => setShowControls(false), 3000)
    }
  }, [hoveringControls, playing])

  useEffect(() => {
    resetControlsTimeout()
    return () => clearTimeout(controlsTimeout.current)
  }, [resetControlsTimeout])

  // ── Fullscreen listener ──
  useEffect(() => {
    const onFsChange = () => setIsFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', onFsChange)
    return () => document.removeEventListener('fullscreenchange', onFsChange)
  }, [])

  // ── Handlers ──
  const togglePlay = useCallback(() => {
    const v = videoRef.current
    if (!v) return
    if (v.paused || v.ended) {
      v.play().catch(() => {})
      setEnded(false)
    } else {
      v.pause()
    }
  }, [])

  const seek = useCallback((seconds) => {
    const v = videoRef.current
    if (!v || !isFinite(v.duration)) return
    const wasPlaying = !v.paused
    const newTime = Math.max(0, Math.min(v.duration, v.currentTime + seconds))
    v.currentTime = newTime
    setCurrentTime(newTime)
    setEnded(false)
    // Show skip animation
    const dir = seconds > 0 ? 'right' : 'left'
    setSkipAnim({ dir, key: Date.now() })
    clearTimeout(skipAnimTimeout.current)
    skipAnimTimeout.current = setTimeout(() => setSkipAnim(null), 700)
    if (wasPlaying) v.play().catch(() => {})
  }, [])

  const handleSeekBar = (e) => {
    const v = videoRef.current
    if (!v || !duration) return
    const rect = e.currentTarget.getBoundingClientRect()
    const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width))
    v.currentTime = ratio * duration
  }

  const changeVolume = (v) => {
    const clamped = Math.max(0, Math.min(1, v))
    setVolume(clamped)
    localStorage.setItem('ss:volume', clamped.toString())
    if (clamped > 0 && muted) setMuted(false)
    if (videoRef.current) videoRef.current.volume = clamped
  }

  const toggleMute = () => {
    const v = videoRef.current
    if (!v) return
    const next = !muted
    setMuted(next)
    v.muted = next
  }

  const toggleFullscreen = () => {
    const el = containerRef.current
    if (!el) return
    if (!document.fullscreenElement) {
      el.requestFullscreen?.().catch(() => {})
    } else {
      document.exitFullscreen?.().catch(() => {})
    }
  }

  const changeSpeed = (rate) => {
    const v = videoRef.current
    if (v) v.playbackRate = rate
    setPlaybackRate(rate)
    setShowSpeedMenu(false)
  }

  const changeQuality = (idx) => {
    const v = videoRef.current
    if (!v) return
    const wasPlaying = !v.paused
    const current = v.currentTime
    setActiveStreamIdx(idx)
    // After src change, restore time
    requestAnimationFrame(() => {
      if (videoRef.current) {
        videoRef.current.currentTime = current
        if (wasPlaying) videoRef.current.play().catch(() => {})
      }
    })
    setShowQualityMenu(false)
  }

  // ── Double-tap to seek ──
  const lastTap = useRef({ time: 0, side: null })
  const handleVideoClick = (e) => {
    const rect = e.currentTarget.getBoundingClientRect()
    const x = e.clientX - rect.left
    const side = x < rect.width / 2 ? 'left' : 'right'
    const now = Date.now()
    if (lastTap.current.side === side && now - lastTap.current.time < 400) {
      seek(side === 'left' ? -10 : 10)
      lastTap.current = { time: 0, side: null }
    } else {
      lastTap.current = { time: now, side }
      // Single tap → toggle play (delayed to allow double-tap detection)
      setTimeout(() => {
        if (Date.now() - lastTap.current.time >= 400) togglePlay()
      }, 400)
    }
  }

  // ── Progress bar hover preview ──
  const [hoverRatio, setHoverRatio] = useState(0)
  const [isHoveringBar, setIsHoveringBar] = useState(false)

  const progressRatio = duration ? currentTime / duration : 0

  if (loadingMeta) {
    return (
      <div className="w-full h-screen bg-black flex items-center justify-center">
        <IconSpinner className="w-10 h-10 text-white/60 animate-spin" />
      </div>
    )
  }

  if (!activeStream?.url) {
    return (
      <div className="w-full h-screen bg-black flex flex-col items-center justify-center text-white gap-4">
        <p className="text-lg text-white/70">No stream available</p>
        <button onClick={() => navigate(-1)} className="flex items-center gap-2 text-netflix-red hover:underline">
          <IconBack className="w-5 h-5" /> Go Back
        </button>
      </div>
    )
  }

  return (
    <div
      ref={containerRef}
      className="relative w-full h-screen bg-black select-none overflow-hidden"
      onMouseMove={() => { setShowControls(true); resetControlsTimeout() }}
    >
      {/* Video */}
      <video
        ref={videoRef}
        src={activeStream.url}
        className="w-full h-full object-contain cursor-pointer"
        onClick={handleVideoClick}
        onWaiting={() => setBuffering(true)}
        onPlaying={() => setBuffering(false)}
        onCanPlay={() => setBuffering(false)}
        onPlay={() => { setPlaying(true); setEnded(false) }}
        onPause={() => setPlaying(false)}
        onTimeUpdate={(e) => { setCurrentTime(e.target.currentTime); setDuration(e.target.duration || 0) }}
        onLoadedMetadata={(e) => setDuration(e.target.duration || 0)}
        onEnded={() => setEnded(true)}
        autoPlay
        playsInline
      />

      {/* Buffering spinner */}
      {buffering && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <IconSpinner className="w-12 h-12 text-white/80 animate-spin" />
        </div>
      )}

      {/* Skip animation overlay */}
      {skipAnim && (
        <div
          key={skipAnim.key}
          className={`absolute top-1/2 -translate-y-1/2 pointer-events-none animate-fade-out ${
            skipAnim.dir === 'left' ? 'left-8' : 'right-8'
          }`}
        >
          <div className="flex flex-col items-center gap-1 bg-black/60 backdrop-blur-md rounded-2xl px-6 py-4">
            {skipAnim.dir === 'left' ? (
              <IconReplay10 className="w-10 h-10 text-white" />
            ) : (
              <IconForward10 className="w-10 h-10 text-white" />
            )}
            <span className="text-white text-sm font-semibold">{skipAnim.dir === 'left' ? '-10' : '+10'}</span>
          </div>
        </div>
      )}

      {/* Center replay button (when ended) */}
      {ended && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-auto">
          <button
            onClick={() => { videoRef.current.currentTime = 0; videoRef.current.play(); setEnded(false) }}
            className="flex flex-col items-center gap-2 bg-black/50 hover:bg-black/70 backdrop-blur-sm rounded-2xl px-8 py-6 transition-colors"
          >
            <IconReplay10 className="w-12 h-12 text-white" />
            <span className="text-white font-medium">Replay</span>
          </button>
        </div>
      )}

      {/* ── Controls Overlay ── */}
      <div
        className={`absolute inset-0 flex flex-col justify-between transition-opacity duration-300 ${
          showControls || !playing || ended ? 'opacity-100' : 'opacity-0'
        }`}
        onMouseEnter={() => setHoveringControls(true)}
        onMouseLeave={() => { setHoveringControls(false); resetControlsTimeout() }}
      >
        {/* Top bar */}
        <div className="bg-gradient-to-b from-black/80 via-black/30 to-transparent px-4 pt-3 pb-8">
          <div className="flex items-center gap-3">
            <button
              onClick={() => navigate(-1)}
              className="p-2 rounded-full hover:bg-white/15 transition-colors"
            >
              <IconBack className="w-5 h-5 text-white" />
            </button>
            <div className="min-w-0">
              <h1 className="text-sm font-semibold text-white truncate">{meta?.name || 'Unknown'}</h1>
              {meta?.year && <p className="text-xs text-white/50">{meta.year}</p>}
            </div>
          </div>
        </div>

        {/* Center play/pause (only when paused, not ended) */}
        {!playing && !ended && (
          <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
            <button
              onClick={togglePlay}
              className="pointer-events-auto w-16 h-16 bg-white/20 hover:bg-white/30 backdrop-blur-sm rounded-full flex items-center justify-center transition-transform hover:scale-110"
            >
              <IconPlay className="w-8 h-8 text-white ml-1" />
            </button>
          </div>
        )}

        {/* Bottom controls */}
        <div className="bg-gradient-to-t from-black/90 via-black/50 to-transparent px-4 pb-4 pt-10">
          {/* Progress bar */}
          <div
            className="relative h-1.5 mb-3 cursor-pointer group"
            onClick={handleSeekBar}
            onMouseMove={(e) => {
              const rect = e.currentTarget.getBoundingClientRect()
              setHoverRatio(Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width)))
            }}
            onMouseEnter={() => setIsHoveringBar(true)}
            onMouseLeave={() => setIsHoveringBar(false)}
          >
            {/* Background */}
            <div className="absolute inset-0 bg-white/20 rounded-full" />
            {/* Buffered */}
            <div
              className="absolute inset-y-0 left-0 bg-white/30 rounded-full"
              style={{ width: `${(videoRef.current?.buffered?.length ? videoRef.current.buffered.end(videoRef.current.buffered.length - 1) / duration : 0) * 100}%` }}
            />
            {/* Progress */}
            <div
              className="absolute inset-y-0 left-0 bg-netflix-red rounded-full transition-all"
              style={{ width: `${progressRatio * 100}%` }}
            />
            {/* Hover preview */}
            {isHoveringBar && (
              <div
                className="absolute top-0 bottom-0 w-0.5 bg-white/60"
                style={{ left: `${hoverRatio * 100}%` }}
              />
            )}
            {/* Thumb */}
            <div
              className="absolute top-1/2 -translate-y-1/2 w-3 h-3 bg-netflix-red rounded-full shadow-lg opacity-0 group-hover:opacity-100 transition-opacity"
              style={{ left: `calc(${progressRatio * 100}% - 6px)` }}
            />
            {/* Hover time tooltip */}
            {isHoveringBar && (
              <div
                className="absolute -top-8 bg-black/80 text-white text-xs px-2 py-1 rounded transform -translate-x-1/2"
                style={{ left: `${hoverRatio * 100}%` }}
              >
                {fmtTime(hoverRatio * duration)}
              </div>
            )}
          </div>

          {/* Control buttons row */}
          <div className="flex items-center justify-between">
            {/* Left group */}
            <div className="flex items-center gap-1">
              {/* Play/Pause */}
              <button onClick={togglePlay} className="p-2 rounded-full hover:bg-white/15 transition-colors">
                {playing ? (
                  <IconPause className="w-6 h-6 text-white" />
                ) : (
                  <IconPlay className="w-6 h-6 text-white ml-0.5" />
                )}
              </button>

              {/* Skip back */}
              <button onClick={() => seek(-10)} className="p-2 rounded-full hover:bg-white/15 transition-colors">
                <IconReplay10 className="w-5 h-5 text-white" />
              </button>

              {/* Skip forward */}
              <button onClick={() => seek(10)} className="p-2 rounded-full hover:bg-white/15 transition-colors">
                <IconForward10 className="w-5 h-5 text-white" />
              </button>

              {/* Volume */}
              <div className="flex items-center gap-1 group/vol">
                <button onClick={toggleMute} className="p-2 rounded-full hover:bg-white/15 transition-colors">
                  {muted || volume === 0 ? (
                    <IconVolumeMute className="w-5 h-5 text-white" />
                  ) : (
                    <IconVolumeHigh className="w-5 h-5 text-white" />
                  )}
                </button>
                <div className="w-0 overflow-hidden group-hover/vol:w-20 transition-all duration-200">
                  <input
                    type="range"
                    min="0"
                    max="1"
                    step="0.05"
                    value={muted ? 0 : volume}
                    onChange={(e) => changeVolume(parseFloat(e.target.value))}
                    className="w-20 h-1 accent-white cursor-pointer"
                  />
                </div>
              </div>

              {/* Time */}
              <div className="text-xs text-white/80 font-medium ml-2 tabular-nums">
                {fmtTime(currentTime)} / {fmtTime(duration)}
              </div>
            </div>

            {/* Right group */}
            <div className="flex items-center gap-1">
              {/* Speed */}
              <div className="relative">
                <button
                  onClick={() => { setShowSpeedMenu(!showSpeedMenu); setShowQualityMenu(false); setShowSettings(false) }}
                  className="p-2 rounded-full hover:bg-white/15 transition-colors text-xs text-white font-medium"
                >
                  {playbackRate}x
                </button>
                {showSpeedMenu && (
                  <div className="absolute bottom-full right-0 mb-2 bg-black/90 backdrop-blur-md rounded-lg overflow-hidden border border-white/10 min-w-[100px]">
                    {[0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map((r) => (
                      <button
                        key={r}
                        onClick={() => changeSpeed(r)}
                        className={`w-full text-left px-4 py-2 text-sm hover:bg-white/10 transition-colors ${
                          playbackRate === r ? 'text-netflix-red font-semibold' : 'text-white'
                        }`}
                      >
                        {r === 1 ? 'Normal' : `${r}x`}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {/* Quality */}
              {streams.length > 1 && (
                <div className="relative">
                  <button
                    onClick={() => { setShowQualityMenu(!showQualityMenu); setShowSpeedMenu(false); setShowSettings(false) }}
                    className="p-2 rounded-full hover:bg-white/15 transition-colors"
                  >
                    <IconSettings className="w-5 h-5 text-white" />
                  </button>
                  {showQualityMenu && (
                    <div className="absolute bottom-full right-0 mb-2 bg-black/90 backdrop-blur-md rounded-lg overflow-hidden border border-white/10 min-w-[140px]">
                      {streams.map((s, i) => (
                        <button
                          key={i}
                          onClick={() => changeQuality(i)}
                          className={`w-full text-left px-4 py-2 text-sm hover:bg-white/10 transition-colors ${
                            i === activeStreamIdx ? 'text-netflix-red font-semibold' : 'text-white'
                          }`}
                        >
                          {s.title || `Stream ${i + 1}`}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {/* Fullscreen */}
              <button onClick={toggleFullscreen} className="p-2 rounded-full hover:bg-white/15 transition-colors">
                {isFullscreen ? (
                  <IconFullscreenExit className="w-5 h-5 text-white" />
                ) : (
                  <IconFullscreen className="w-5 h-5 text-white" />
                )}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
