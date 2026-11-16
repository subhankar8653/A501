import { useRef, useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext'
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
            <span className="w-8 h-8 rounded-full bg-reel-bg/95 ring-1 ring-reel-ink/10 flex items-center justify-center text-reel-ink hover:bg-reel-surface2">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round"><path d="m15 18-6-6 6-6" /></svg>
            </span>
          </button>
          <button
            onClick={() => scrollBy(1)}
            aria-label="Scroll right"
            className="hidden sm:flex opacity-0 group-hover/row:opacity-100 transition absolute right-0 top-0 bottom-1 w-10 items-center justify-center bg-gradient-to-l from-reel-bg to-transparent"
          >
            <span className="w-8 h-8 rounded-full bg-reel-bg/95 ring-1 ring-reel-ink/10 flex items-center justify-center text-reel-ink hover:bg-reel-surface2">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6" /></svg>
            </span>
          </button>
        </>
      )}
    </div>
  )
}

// Ek language ki rail. Chhoti list (< 10) ek hi row mein, badi list do rows mein.
// Shuru mein PAGE items dikhte hain; "Show more" se aur khulte jaate hain.
const PAGE = 12

export default function LanguageRail({ language, items }) {
  const { t } = useLanguage()
  const [visible, setVisible] = useState(PAGE)
  if (!items || items.length === 0) return null

  const shown = items.slice(0, visible)
  const remaining = items.length - shown.length
  const twoRows = shown.length >= 10
  const mid = twoRows ? Math.ceil(shown.length / 2) : shown.length
  const row1 = shown.slice(0, mid)
  const row2 = shown.slice(mid)

  return (
    <section className="mb-9">
      <div className="flex items-center justify-between gap-3 mb-3 px-4 sm:px-0">
        <h2 className="flex items-center gap-2.5 font-display text-lg sm:text-xl font-semibold">
          <span className="w-1 h-4 rounded-full bg-reel-gold" />
          {language}
          <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-reel-surface2 text-reel-muted font-sans">
            {items.length}
          </span>
        </h2>
      </div>
      <Row items={row1} />
      {row2.length > 0 ? (
        <div className="mt-3">
          <Row items={row2} keySuffix="-row2" />
        </div>
      ) : null}
      {remaining > 0 ? (
        <div className="flex justify-center mt-4 px-4">
          <button
            onClick={() => setVisible((v) => v + PAGE)}
            className="inline-flex items-center gap-2 text-sm font-semibold px-5 py-2 rounded-full bg-reel-surface2 text-reel-ink ring-1 ring-reel-ink/10 active:scale-95 transition"
          >
            {t('show_more')}
            <span className="text-xs text-reel-gold">+{Math.min(PAGE, remaining)}</span>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
          </button>
        </div>
      ) : null}
    </section>
  )
}
