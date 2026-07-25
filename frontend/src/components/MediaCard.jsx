import { Link } from 'react-router-dom'

export default function MediaCard({ item }) {
  const year = item.releaseInfo || item.year || ''
  return (
    <Link
      to={`/title/${item.type}/${encodeURIComponent(item.id)}`}
      className="group shrink-0 w-[140px] sm:w-[160px]"
    >
      <div className="relative aspect-[2/3] rounded-md overflow-hidden bg-reel-surface2 ring-1 ring-white/5 group-hover:ring-reel-gold/60 transition">
        {item.poster ? (
          <img
            src={item.poster}
            alt={item.name}
            loading="lazy"
            className="w-full h-full object-cover group-hover:scale-105 transition duration-300"
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center text-reel-muted text-xs px-2 text-center">
            {item.name}
          </div>
        )}
        {item.imdbRating ? (
          <span className="absolute top-1.5 right-1.5 bg-black/70 text-reel-gold text-[10px] font-semibold px-1.5 py-0.5 rounded">
            ★ {item.imdbRating}
          </span>
        ) : null}
      </div>
      <p className="mt-1.5 text-sm font-medium text-reel-ink line-clamp-1">{item.name}</p>
      <p className="text-xs text-reel-muted">{year}</p>
    </Link>
  )
}
