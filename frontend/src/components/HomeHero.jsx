import { Link } from 'react-router-dom'
import { useEffect, useRef, useState } from 'react'

// Big featured banner at the top of Home — auto-rotating carousel over the
// top spotlight pick from each language group in the active tab. This is
// what fills the dead empty space that used to sit between the header and
// the category pills, and is the single biggest lever for a "wow" first
// look — a static single card felt flat, so it now cycles on its own.
const ROTATE_MS = 5000

export default function HomeHero({ items, loading }) {
  const [index, setIndex] = useState(0)
  const timerRef = useRef(null)

  const list = items || []

  // Reset to the first slide whenever the underlying set of items changes
  // (e.g. switching category tabs), so we never point past the end.
  useEffect(() => {
    setIndex(0)
  }, [list.length ? list[0]?.id : null, list.length])

  useEffect(() => {
    if (list.length < 2) return undefined
    timerRef.current = setInterval(() => {
      setIndex((i) => (i + 1) % list.length)
    }, ROTATE_MS)
    return () => clearInterval(timerRef.current)
  }, [list.length])

  if (loading) {
    return (
      <div className="relative w-full aspect-[16/11] sm:aspect-[21/9] max-h-[280px] bg-reel-surface2 overflow-hidden rounded-2xl mx-auto max-w-6xl">
        <div className="absolute inset-0 -translate-x-full animate-[shimmer_1.6s_infinite] bg-gradient-to-r from-transparent via-white/[0.06] to-transparent" />
      </div>
    )
  }

  if (!list.length) return null

  const item = list[index]
  const backdrop = item.background || item.poster

  return (
    <div className="relative w-full aspect-[16/11] sm:aspect-[21/9] max-h-[280px] overflow-hidden bg-reel-bg">
      {/* Each slide is stacked absolutely and cross-fades via opacity — key
          on item.id so the fade actually replays on every rotation. */}
      {list.map((slide, i) => {
        const slideBackdrop = slide.background || slide.poster
        return (
          <div
            key={slide.id || i}
            className={`absolute inset-0 transition-opacity duration-700 ease-out ${
              i === index ? 'opacity-100 z-[1]' : 'opacity-0 z-0'
            }`}
          >
            {slideBackdrop ? (
              <img
                src={slideBackdrop}
                alt=""
                className="absolute inset-0 w-full h-full object-cover scale-105"
              />
            ) : null}
          </div>
        )
      })}

      {/* Bottom-up fade into the page background, plus a side fade on wide
          screens so the text panel stays legible over busy artwork. */}
      <div className="absolute inset-0 z-[2] bg-gradient-to-t from-reel-bg via-reel-bg/55 to-reel-bg/10" />
      <div className="absolute inset-0 z-[2] bg-gradient-to-r from-reel-bg/80 via-transparent to-transparent sm:from-reel-bg/85 sm:via-reel-bg/10" />

      <div key={item.id || index} className="relative z-[3] h-full flex flex-col justify-end px-4 sm:px-6 pb-4 sm:pb-6 max-w-6xl mx-auto page-fade-in">
        <span className="inline-flex w-fit items-center gap-1.5 text-[9px] font-bold tracking-[0.15em] uppercase text-reel-gold bg-reel-gold/10 ring-1 ring-reel-gold/30 backdrop-blur-sm px-2 py-0.5 rounded-full mb-2">
          <svg width="9" height="9" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l2.9 6.6L22 9.3l-5 4.9 1.2 7-6.2-3.4L5.8 21.2 7 14.2 2 9.3l7.1-.7L12 2z"/></svg>
          Featured
        </span>
        <h1 className="font-display text-lg sm:text-3xl font-bold text-reel-ink drop-shadow-[0_2px_12px_rgba(0,0,0,0.6)] max-w-md sm:max-w-xl line-clamp-1">
          {item.name}
        </h1>
        <div className="flex items-center gap-3 mt-1.5 text-[11px] sm:text-sm text-reel-muted">
          {item.imdbRating ? (
            <span className="flex items-center gap-1 text-reel-gold font-semibold">★ {item.imdbRating}</span>
          ) : null}
          {item.releaseInfo ? <span>{item.releaseInfo}</span> : null}
        </div>
        <div className="flex items-center gap-2.5 mt-3">
          <Link
            to={`/title/${item.type}/${encodeURIComponent(item.id)}`}
            className="glossy-btn flex items-center gap-1.5 text-reel-bg font-semibold text-xs sm:text-sm px-4 py-2 rounded-full active:scale-95 transition-transform"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" className="relative z-10"><path d="M8 5v14l11-7z"/></svg>
            <span className="relative z-10">Watch Now</span>
          </Link>
          <Link
            to={`/title/${item.type}/${encodeURIComponent(item.id)}`}
            className="glossy-chip flex items-center gap-1.5 ring-1 ring-reel-ink/15 text-reel-ink font-medium text-xs sm:text-sm px-4 py-2 rounded-full active:scale-95 transition-transform hover:bg-reel-ink/[0.06]"
          >
            Details
          </Link>
        </div>

        {/* Dot indicators — also tappable so users can jump straight to a
            slide instead of waiting for the auto-rotate. */}
        {list.length > 1 ? (
          <div className="flex items-center gap-1.5 mt-3">
            {list.map((slide, i) => (
              <button
                key={slide.id || i}
                aria-label={`Slide ${i + 1}`}
                onClick={() => setIndex(i)}
                className={`h-1.5 rounded-full transition-all duration-300 ${
                  i === index ? 'w-5 bg-reel-gold' : 'w-1.5 bg-reel-ink/30 hover:bg-reel-ink/50'
                }`}
              />
            ))}
          </div>
        ) : null}
      </div>
    </div>
  )
}
