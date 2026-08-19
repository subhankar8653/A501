import { Link } from 'react-router-dom'

// Big featured banner at the top of Home — uses the first item of the
// active tab's top language group as the "spotlight" pick. This is what
// fills the dead empty space that used to sit between the header and the
// category pills, and is the single biggest lever for a "wow" first look.
export default function HomeHero({ item, loading }) {
  if (loading) {
    return (
      <div className="relative w-full aspect-[3/4] sm:aspect-[21/9] max-h-[460px] bg-reel-surface2 overflow-hidden">
        <div className="absolute inset-0 -translate-x-full animate-[shimmer_1.6s_infinite] bg-gradient-to-r from-transparent via-white/[0.06] to-transparent" />
      </div>
    )
  }

  if (!item) return null

  const backdrop = item.background || item.poster

  return (
    <div className="relative w-full aspect-[3/4] sm:aspect-[21/9] max-h-[460px] overflow-hidden bg-reel-bg">
      {backdrop ? (
        <img
          src={backdrop}
          alt=""
          className="absolute inset-0 w-full h-full object-cover scale-105"
        />
      ) : null}
      {/* Bottom-up fade into the page background, plus a side fade on wide
          screens so the text panel stays legible over busy artwork. */}
      <div className="absolute inset-0 bg-gradient-to-t from-reel-bg via-reel-bg/55 to-reel-bg/10" />
      <div className="absolute inset-0 bg-gradient-to-r from-reel-bg/80 via-transparent to-transparent sm:from-reel-bg/85 sm:via-reel-bg/10" />

      <div className="relative h-full flex flex-col justify-end px-4 sm:px-6 pb-8 sm:pb-10 max-w-6xl mx-auto">
        <span className="inline-flex w-fit items-center gap-1.5 text-[10px] font-bold tracking-[0.15em] uppercase text-reel-gold bg-reel-gold/10 ring-1 ring-reel-gold/30 backdrop-blur-sm px-2.5 py-1 rounded-full mb-3">
          <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l2.9 6.6L22 9.3l-5 4.9 1.2 7-6.2-3.4L5.8 21.2 7 14.2 2 9.3l7.1-.7L12 2z"/></svg>
          Featured
        </span>
        <h1 className="font-display text-2xl sm:text-4xl font-bold text-reel-ink drop-shadow-[0_2px_12px_rgba(0,0,0,0.6)] max-w-md sm:max-w-xl line-clamp-2">
          {item.name}
        </h1>
        <div className="flex items-center gap-3 mt-2.5 text-xs sm:text-sm text-reel-muted">
          {item.imdbRating ? (
            <span className="flex items-center gap-1 text-reel-gold font-semibold">★ {item.imdbRating}</span>
          ) : null}
          {item.releaseInfo ? <span>{item.releaseInfo}</span> : null}
        </div>
        <div className="flex items-center gap-3 mt-5">
          <Link
            to={`/title/${item.type}/${encodeURIComponent(item.id)}`}
            className="flex items-center gap-2 bg-reel-gold text-reel-bg font-semibold text-sm px-5 py-2.5 rounded-full active:scale-95 transition-transform shadow-[0_10px_28px_-8px_rgba(232,163,61,0.65)]"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
            Watch Now
          </Link>
          <Link
            to={`/title/${item.type}/${encodeURIComponent(item.id)}`}
            className="flex items-center gap-2 bg-white/10 backdrop-blur-sm ring-1 ring-white/15 text-reel-ink font-medium text-sm px-5 py-2.5 rounded-full active:scale-95 transition-transform hover:bg-white/[0.14]"
          >
            Details
          </Link>
        </div>
      </div>
    </div>
  )
}
