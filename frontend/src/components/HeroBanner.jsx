import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import { Play, Info, ChevronLeft, ChevronRight } from 'lucide-react'

export default function HeroBanner({ items = [] }) {
  const [current, setCurrent] = useState(0)
  const [imageLoaded, setImageLoaded] = useState(false)

  const featured = items.slice(0, 5)
  if (!featured.length) return null

  const item = featured[current]

  useEffect(() => {
    setImageLoaded(false)
    const timer = setInterval(() => {
      setCurrent(prev => (prev + 1) % featured.length)
    }, 8000)
    return () => clearInterval(timer)
  }, [featured.length])

  const next = () => setCurrent(prev => (prev + 1) % featured.length)
  const prev = () => setCurrent(prev => (prev - 1 + featured.length) % featured.length)

  return (
    <div className="relative w-full aspect-[21/9] min-h-[400px] max-h-[70vh] overflow-hidden">
      {/* Background Image */}
      <img
        src={item.background || item.poster}
        alt={item.name}
        onLoad={() => setImageLoaded(true)}
        className={`absolute inset-0 w-full h-full object-cover transition-all duration-700 ${
          imageLoaded ? 'opacity-100 scale-100' : 'opacity-0 scale-105'
        }`}
      />

      {/* Overlays */}
      <div className="absolute inset-0 bg-gradient-to-r from-netflix-black via-netflix-black/60 to-transparent" />
      <div className="absolute inset-0 bg-gradient-to-t from-netflix-black via-transparent to-transparent" />

      {/* Content */}
      <div className="absolute bottom-0 left-0 right-0 p-6 sm:p-10 lg:p-16 max-w-3xl animate-slide-up">
        <h1 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-white mb-3 leading-tight">
          {item.name}
        </h1>
        <div className="flex items-center gap-3 mb-4 text-sm text-netflix-lightgray">
          {item.year && <span>{item.year}</span>}
          {item.imdbRating && (
            <span className="flex items-center gap-1 text-yellow-400">
              ★ {item.imdbRating}
            </span>
          )}
          {item.runtime && <span>{item.runtime}</span>}
          {item.genres?.[0] && <span>{item.genres[0]}</span>}
        </div>
        <p className="text-sm sm:text-base text-netflix-lightgray line-clamp-3 mb-6 max-w-xl">
          {item.description || 'No description available.'}
        </p>
        <div className="flex items-center gap-3">
          <Link
            to={`/watch/${item.type}/${encodeURIComponent(item.id)}`}
            className="flex items-center gap-2 bg-netflix-red hover:bg-red-700 text-white font-semibold px-6 py-3 rounded-md transition-colors"
          >
            <Play className="w-5 h-5 fill-white" />
            Play Now
          </Link>
          <Link
            to={`/title/${item.type}/${encodeURIComponent(item.id)}`}
            className="flex items-center gap-2 bg-white/20 hover:bg-white/30 text-white font-semibold px-6 py-3 rounded-md backdrop-blur-sm transition-colors"
          >
            <Info className="w-5 h-5" />
            More Info
          </Link>
        </div>
      </div>

      {/* Navigation Dots */}
      <div className="absolute bottom-6 right-6 sm:right-10 flex items-center gap-2">
        <button onClick={prev} className="p-2 bg-black/40 hover:bg-black/60 rounded-full transition-colors">
          <ChevronLeft className="w-5 h-5" />
        </button>
        <div className="flex gap-1.5">
          {featured.map((_, idx) => (
            <button
              key={idx}
              onClick={() => setCurrent(idx)}
              className={`h-1.5 rounded-full transition-all duration-300 ${
                idx === current ? 'w-8 bg-netflix-red' : 'w-1.5 bg-white/40 hover:bg-white/60'
              }`}
            />
          ))}
        </div>
        <button onClick={next} className="p-2 bg-black/40 hover:bg-black/60 rounded-full transition-colors">
          <ChevronRight className="w-5 h-5" />
        </button>
      </div>
    </div>
  )
}
