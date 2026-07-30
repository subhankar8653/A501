import { useState, useEffect, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getMeta, getStreams } from '../api'
import { ArrowLeft, Play, Pause, Volume2, VolumeX, Maximize, SkipBack, SkipForward } from 'lucide-react'

export default function Player() {
  const { type, id } = useParams()
  const navigate = useNavigate()
  const videoRef = useRef(null)
  const containerRef = useRef(null)

  const [meta, setMeta] = useState(null)
  const [streams, setStreams] = useState([])
  const [currentStream, setCurrentStream] = useState(0)
  const [loading, setLoading] = useState(true)

  const [playing, setPlaying] = useState(false)
  const [progress, setProgress] = useState(0)
  const [duration, setDuration] = useState(0)
  const [volume, setVolume] = useState(1)
  const [muted, setMuted] = useState(false)
  const [showControls, setShowControls] = useState(true)
  const [fullscreen, setFullscreen] = useState(false)
  const controlsTimeout = useRef(null)

  useEffect(() => {
    async function init() {
      try {
        const [metaData, streamData] = await Promise.all([
          getMeta(type, id),
          getStreams(type, id)
        ])
        setMeta(metaData)
        setStreams(streamData)
      } catch (err) {
        console.error('Player init failed:', err)
      } finally {
        setLoading(false)
      }
    }
    init()
  }, [type, id])

  const handleMouseMove = () => {
    setShowControls(true)
    clearTimeout(controlsTimeout.current)
    controlsTimeout.current = setTimeout(() => {
      if (playing) setShowControls(false)
    }, 3000)
  }

  const togglePlay = () => {
    if (videoRef.current) {
      if (playing) videoRef.current.pause()
      else videoRef.current.play()
      setPlaying(!playing)
    }
  }

  const handleTimeUpdate = () => {
    const v = videoRef.current
    if (v) {
      setProgress(v.currentTime)
      setDuration(v.duration || 0)
    }
  }

  const handleSeek = (e) => {
    const v = videoRef.current
    if (v) {
      const time = (e.target.value / 100) * duration
      v.currentTime = time
      setProgress(time)
    }
  }

  const toggleFullscreen = () => {
    if (!document.fullscreenElement) {
      containerRef.current?.requestFullscreen()
      setFullscreen(true)
    } else {
      document.exitFullscreen()
      setFullscreen(false)
    }
  }

  const skip = (seconds) => {
    const v = videoRef.current
    if (v) v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + seconds))
  }

  const formatTime = (s) => {
    if (!s) return '0:00'
    const mins = Math.floor(s / 60)
    const secs = Math.floor(s % 60)
    return `${mins}:${secs.toString().padStart(2, '0')}`
  }

  if (loading) {
    return (
      <div className="min-h-screen bg-black flex items-center justify-center">
        <div className="w-12 h-12 border-4 border-white/10 border-t-netflix-red rounded-full animate-spin" />
      </div>
    )
  }

  const stream = streams[currentStream]
  if (!stream?.url) {
    return (
      <div className="min-h-screen bg-black flex flex-col items-center justify-center text-white">
        <p className="text-xl mb-4">No stream available</p>
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-2 text-netflix-red hover:underline"
        >
          <ArrowLeft className="w-4 h-4" />
          Go Back
        </button>
      </div>
    )
  }

  return (
    <div 
      ref={containerRef}
      className="relative w-full h-screen bg-black overflow-hidden"
      onMouseMove={handleMouseMove}
      onClick={() => setShowControls(!showControls)}
    >
      {/* Video */}
      <video
        ref={videoRef}
        src={stream.url}
        className="w-full h-full object-contain"
        onTimeUpdate={handleTimeUpdate}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        autoPlay
      />

      {/* Top Bar */}
      <div className={`absolute top-0 left-0 right-0 p-4 bg-gradient-to-b from-black/80 to-transparent transition-opacity duration-300 ${
        showControls ? 'opacity-100' : 'opacity-0'
      }`}>
        <div className="flex items-center gap-3">
          <button 
            onClick={(e) => { e.stopPropagation(); navigate(-1) }}
            className="p-2 hover:bg-white/10 rounded-full transition-colors"
          >
            <ArrowLeft className="w-5 h-5 text-white" />
          </button>
          <div>
            <h1 className="text-sm font-semibold text-white">{meta?.name || 'Unknown'}</h1>
            {meta?.year && <p className="text-xs text-netflix-gray">{meta.year}</p>}
          </div>
        </div>
      </div>

      {/* Center Play/Pause */}
      {!playing && (
        <div className="absolute inset-0 flex items-center justify-center" onClick={(e) => e.stopPropagation()}>
          <button
            onClick={togglePlay}
            className="w-20 h-20 bg-netflix-red/90 hover:bg-netflix-red rounded-full flex items-center justify-center transition-transform hover:scale-110"
          >
            <Play className="w-8 h-8 text-white fill-white ml-1" />
          </button>
        </div>
      )}

      {/* Bottom Controls */}
      <div 
        className={`absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/90 via-black/50 to-transparent p-4 transition-opacity duration-300 ${
          showControls ? 'opacity-100' : 'opacity-0'
        }`}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Progress Bar */}
        <div className="mb-3">
          <input
            type="range"
            min="0"
            max="100"
            value={duration ? (progress / duration) * 100 : 0}
            onChange={handleSeek}
            className="w-full h-1 bg-white/20 rounded-lg appearance-none cursor-pointer accent-netflix-red hover:h-1.5 transition-all"
          />
          <div className="flex justify-between text-xs text-netflix-gray mt-1">
            <span>{formatTime(progress)}</span>
            <span>{formatTime(duration)}</span>
          </div>
        </div>

        {/* Control Buttons */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <button onClick={togglePlay} className="p-2 hover:bg-white/10 rounded-full transition-colors">
              {playing ? <Pause className="w-6 h-6 text-white" /> : <Play className="w-6 h-6 text-white fill-white" />}
            </button>

            <button onClick={() => skip(-10)} className="p-2 hover:bg-white/10 rounded-full transition-colors">
              <SkipBack className="w-5 h-5 text-white" />
            </button>

            <button onClick={() => skip(10)} className="p-2 hover:bg-white/10 rounded-full transition-colors">
              <SkipForward className="w-5 h-5 text-white" />
            </button>

            {/* Volume */}
            <div className="flex items-center gap-2 group">
              <button 
                onClick={() => {
                  if (videoRef.current) {
                    videoRef.current.muted = !muted
                    setMuted(!muted)
                  }
                }}
                className="p-2 hover:bg-white/10 rounded-full transition-colors"
              >
                {muted ? <VolumeX className="w-5 h-5 text-white" /> : <Volume2 className="w-5 h-5 text-white" />}
              </button>
              <input
                type="range"
                min="0"
                max="1"
                step="0.1"
                value={muted ? 0 : volume}
                onChange={(e) => {
                  const v = parseFloat(e.target.value)
                  setVolume(v)
                  if (videoRef.current) videoRef.current.volume = v
                }}
                className="w-0 group-hover:w-20 transition-all duration-200 accent-white h-1"
              />
            </div>

            {/* Quality Selector */}
            {streams.length > 1 && (
              <div className="relative">
                <select
                  value={currentStream}
                  onChange={(e) => setCurrentStream(Number(e.target.value))}
                  className="bg-white/10 hover:bg-white/20 text-white text-xs px-3 py-1.5 rounded-md border border-white/10 focus:outline-none"
                >
                  {streams.map((s, i) => (
                    <option key={i} value={i} className="bg-netflix-dark">
                      {s.title || `Quality ${i + 1}`}
                    </option>
                  ))}
                </select>
              </div>
            )}
          </div>

          <button onClick={toggleFullscreen} className="p-2 hover:bg-white/10 rounded-full transition-colors">
            <Maximize className="w-5 h-5 text-white" />
          </button>
        </div>
      </div>
    </div>
  )
}
