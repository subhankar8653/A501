import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Play, Info, Star } from 'lucide-react'

export default function MediaCard({ item, index = 0 }) {
  const [imageLoaded, setImageLoaded] = useState(false)
  const [hovered, setHovered] = useState(false)

  const poster = item.poster || item.background || ''
  const title = item.name || 'Untitled'
  const year = item.year || item.releaseInfo || ''
  const rating = item.imdbRating || item.rating || ''

  return (
    <Link 
      to={`/title/${item.type}/${encodeURIComponent(item.id)}`}
      className="relative flex-shrink-0 w-[160px] sm:w-[200px] md:w-[240px] aspect-[2/3] rounded-lg overflow-hidden group cursor-pointer card-hover"
      style={{ animationDelay: `${index * 50}ms` }}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {/* Skeleton */}
      {!imageLoaded && (
        <div className="absolute inset-0 shimmer-bg rounded-lg" />
      )}

      {/* Poster Image */}
      <img
        src={poster}
        alt={title}
        loading="lazy"
        onLoad={() => setImageLoaded(true)}
        onError={() => setImageLoaded(true)}
        className={`absolute inset-0 w-full h-full object-cover transition-opacity duration-300 ${
          imageLoaded ? 'opacity-100' : 'opacity-0'
        }`}
      />

      {/* Fallback if no image */}
      {imageLoaded && !poster && (
        <div className="absolute inset-0 bg-netflix-dark flex items-center justify-center">
          <span className="text-netflix-gray text-sm font-medium text-center px-2">{title}</span>
        </div>
      )}

      {/* Gradient Overlay */}
      <div className={`absolute inset-0 bg-gradient-to-t from-black via-black/20 to-transparent transition-opacity duration-300 ${
        hovered ? 'opacity-90' : 'opacity-60'
      }`} />

      {/* Top Badge */}
      {rating && (
        <div className="absolute top-2 right-2 flex items-center gap-1 bg-black/60 backdrop-blur-sm px-2 py-1 rounded-md">
          <Star className="w-3 h-3 text-yellow-400 fill-yellow-400" />
          <span className="text-xs font-semibold text-white">{rating}</span>
        </div>
      )}

      {/* Bottom Content */}
      <div className={`absolute bottom-0 left-0 right-0 p-3 transition-all duration-300 ${
        hovered ? 'translate-y-0 opacity-100' : 'translate-y-2 opacity-80'
      }`}>
        <h3 className="text-sm font-semibold text-white line-clamp-2 leading-tight mb-1">
          {title}
        </h3>
        {year && (
          <p className="text-xs text-netflix-lightgray">{year}</p>
        )}

        {/* Hover Actions */}
        {hovered && (
          <div className="flex items-center gap-2 mt-2 animate-fade-in">
            <button className="flex items-center gap-1 bg-netflix-red hover:bg-red-700 text-white text-xs font-medium px-3 py-1.5 rounded-full transition-colors">
              <Play className="w-3 h-3 fill-white" />
              Play
            </button>
            <button className="flex items-center gap-1 bg-white/20 hover:bg-white/30 text-white text-xs font-medium px-3 py-1.5 rounded-full transition-colors">
              <Info className="w-3 h-3" />
              Info
            </button>
          </div>
        )}
      </div>
    </Link>
  )
}
