import { useRef } from 'react'
import MediaCard from './MediaCard'

// One scrollable row with hover-revealed scroll arrows on desktop. On
// touch it's just a swipe like before; on desktop, a wide horizontal list
// with no visible scrollbar and no arrows was easy to miss entirely
// unless someone happened to click-drag or shift-scroll it.
function Row({ items, keySuffix = '' }) {
  const scrollerRef = useRef(null)

  function scrollBy(dir) {
    scrollerRef.current?.scrollBy({ left: dir * 480, behavior: 'smooth' })
  }

  return (
    <div className="relative group/row">
      <div ref={scrollerRef} className="flex gap-3 overflow-x-auto no-scrollbar scroll-smooth px-4 sm:px-0 pb-1">
        {items.map((item, i) => (
          <MediaCard key={`${item.id}${keySuffix}`} item={item} index={i} />
        ))}
      </div>
      {items.length > 5 && (
        <>
          <button
            onClick={() => scrollBy(-1)}
            aria-label="Scroll left"
            className="hidden sm:flex opacity-0 group-hover/row:opacity-100 transition absolute left-0 top-0 bottom-1 w-10 items-center justify-center bg-gradient-to-r from-reel-bg to-transparent"
          >
            <span className="w-8 h-8 rounded-full bg-reel-bg/95 ring-1 ring-white/10 flex items-center justify-center text-reel-ink hover:bg-reel-surface2">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round"><path d="m15 18-6-6 6-6" /></svg>
            </span>
          </button>
          <button
            onClick={() => scrollBy(1)}
            aria-label="Scroll right"
            className="hidden sm:flex opacity-0 group-hover/row:opacity-100 transition absolute right-0 top-0 bottom-1 w-10 items-center justify-center bg-gradient-to-l from-reel-bg to-transparent"
          >
            <span className="w-8 h-8 rounded-full bg-reel-bg/95 ring-1 ring-white/10 flex items-center justify-center text-reel-ink hover:bg-reel-surface2">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6" /></svg>
            </span>
          </button>
        </>
      )}
    </div>
  )
}

// Two-row rail for one language within a content-type tab. Splitting
// into two rows (instead of one long scroll) surfaces more titles for
// languages that have a lot of content.
export default function LanguageRail({ language, items }) {
  if (!items || items.length === 0) return null

  const mid = Math.ceil(items.length / 2)
  const row1 = items.slice(0, mid)
  const row2 = items.slice(mid)

  return (
    <section className="mb-8">
      <h2 className="font-display text-xl font-semibold mb-3 px-4 sm:px-0">{language}</h2>
      <Row items={row1} />
      {row2.length > 0 ? (
        <div className="mt-3">
          <Row items={row2} keySuffix="-row2" />
        </div>
      ) : null}
    </section>
  )
}
