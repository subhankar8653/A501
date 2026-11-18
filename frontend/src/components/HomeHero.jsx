import { Link } from 'react-router-dom'
import { useEffect, useRef, useState } from 'react'

// Big featured banner at the top of Home — auto-rotating carousel over the
// top spotlight pick from each language group in the active tab. This is
// what fills the dead empty space that used to sit between the header and
// the category pills, and is the single biggest lever for a "wow" first
// look — a static single card felt flat, so it now cycles on its own.
const ROTATE_MS = 5000

// Preloads every slide's artwork once. Only slides whose image REALLY loaded are
// shown (a featured card with a missing picture looks broken, so it is skipped).
// Each image gets one retry; after SETTLE_MS anything still pending counts as failed.
const SETTLE_MS = 3500
// Jo artwork ek baar load ho chuka, wapas aane par turant 'ok' maano (skeleton dobara na dikhe).
const LOADED_URLS = new Set()

function useLoadedSlides(list, onSettled) {
  const [okMap, setOkMap] = useState(() => {
    const m = {}
    list.forEach((s) => { if (LOADED_URLS.has(s.background || s.poster)) m[s.id] = true })
    return m
  })
  const [timedOut, setTimedOut] = useState(false)
  const key = list.map((s) => `${s.id}|${s.background || s.poster || ''}`).join(',')

  useEffect(() => {
    let dead = false
    setTimedOut(false)
    const t = setTimeout(() => !dead && setTimedOut(true), SETTLE_MS)
    list.forEach((s) => {
      const url = s.background || s.poster
      const id = s.id
      if (!url) { setOkMap((m) => ({ ...m, [id]: false })); return }
      const load = (u, retry) => {
        const im = new Image()
        im.onload = () => { LOADED_URLS.add(url); if (!dead) setOkMap((m) => ({ ...m, [id]: true })) }
        im.onerror = () => {
          if (dead) return
          if (retry) setTimeout(() => load(url + (url.includes('?') ? '&' : '?') + '_r=1', false), 900)
          else setOkMap((m) => ({ ...m, [id]: false }))
        }
        im.src = u
      }
      load(url, true)
    })
    return () => { dead = true; clearTimeout(t) }
  }, [key]) // eslint-disable-line react-hooks/exhaustive-deps

  const good = list.filter((s) => okMap[s.id] === true)
  const pending = list.some((s) => okMap[s.id] === undefined)
  const settled = list.length > 0 && (good.length > 0 || !pending || timedOut)
  useEffect(() => { if (settled && onSettled) onSettled() }, [settled]) // eslint-disable-line react-hooks/exhaustive-deps
  return { good, settled: settled || list.length === 0 }
}

export default function HomeHero({ items, loading, onSettled }) {
  const [index, setIndex] = useState(0)
  const [drag, setDrag] = useState(0)      // live finger offset in px
  const [dragging, setDragging] = useState(false)
  const timerRef = useRef(null)
  const touch = useRef(null)
  const boxRef = useRef(null)

  const { good: list, settled } = useLoadedSlides(items || [], onSettled)
  const count = list.length

  // Reset to the first slide whenever the set of visible slides changes.
  const sig = list.map((s) => s.id).join(',')
  useEffect(() => { setIndex(0) }, [sig])

  const startAuto = () => {
    clearInterval(timerRef.current)
    if (count < 2) return
    timerRef.current = setInterval(() => setIndex((i) => (i + 1) % count), ROTATE_MS)
  }
  useEffect(() => { startAuto(); return () => clearInterval(timerRef.current) }, [count]) // eslint-disable-line react-hooks/exhaustive-deps

  function go(i) { setIndex(((i % count) + count) % count); startAuto() }

  // Finger swipe: the banner follows the finger, then snaps to the next/previous slide.
  function onTouchStart(e) {
    if (count < 2) return
    const t = e.touches[0]
    touch.current = { x: t.clientX, y: t.clientY, lock: null }
    clearInterval(timerRef.current)
  }
  function onTouchMove(e) {
    const st = touch.current
    if (!st) return
    const t = e.touches[0]
    const dx = t.clientX - st.x, dy = t.clientY - st.y
    if (!st.lock) {
      if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return
      st.lock = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y'
    }
    if (st.lock !== 'x') return
    e.stopPropagation() // keep Home's pull-to-refresh out of a sideways swipe
    setDragging(true)
    const atEdge = (index === 0 && dx > 0) || (index === count - 1 && dx < 0)
    setDrag(atEdge ? dx * 0.35 : dx)
  }
  function onTouchEnd() {
    const st = touch.current
    touch.current = null
    if (!st || st.lock !== 'x') { startAuto(); return }
    const w = boxRef.current ? boxRef.current.offsetWidth : 360
    const dx = drag
    setDragging(false)
    setDrag(0)
    if (dx < -w * 0.18 && index < count - 1) go(index + 1)
    else if (dx > w * 0.18 && index > 0) go(index - 1)
    else startAuto()
  }

  if (loading || !settled) {
    return (
      <div className="relative w-full aspect-[16/11] sm:aspect-[21/9] max-h-[280px] bg-reel-surface2 overflow-hidden rounded-2xl mx-auto max-w-6xl">
        <div className="absolute inset-0 -translate-x-full animate-[shimmer_1.6s_infinite] bg-gradient-to-r from-transparent via-white/[0.06] to-transparent" />
      </div>
    )
  }

  if (!count) return null

  const item = list[Math.min(index, count - 1)]
  const textFade = dragging ? Math.max(0.15, 1 - Math.abs(drag) / 140) : 1

  return (
    <div
      ref={boxRef}
      className="relative w-full aspect-[16/11] sm:aspect-[21/9] max-h-[280px] overflow-hidden bg-reel-bg"
      style={{ touchAction: 'pan-y' }}
      onTouchStart={onTouchStart}
      onTouchMove={onTouchMove}
      onTouchEnd={onTouchEnd}
      onTouchCancel={onTouchEnd}
    >
      {/* Slides sit side by side on a track that slides with the finger / on auto-rotate. */}
      <div
        className="absolute inset-0 flex"
        style={{
          transform: `translate3d(calc(${-index * 100}% + ${drag}px), 0, 0)`,
          transition: dragging ? 'none' : 'transform 450ms cubic-bezier(.22,.7,.2,1)',
          willChange: 'transform',
        }}
      >
        {list.map((slide, i) => {
          const slideBackdrop = slide.background || slide.poster
          return (
            <div key={slide.id || i} className="relative h-full w-full shrink-0 overflow-hidden">
              <img src={slideBackdrop} alt="" draggable={false} className="absolute inset-0 w-full h-full object-cover" />
            </div>
          )
        })}
      </div>

      {/* Bottom-up fade into the page background, plus a side fade on wide
          screens so the text panel stays legible over busy artwork. */}
      <div className="absolute inset-0 z-[2] bg-gradient-to-t from-reel-bg via-reel-bg/55 to-reel-bg/10" />
      <div className="absolute inset-0 z-[2] bg-gradient-to-r from-reel-bg/80 via-transparent to-transparent sm:from-reel-bg/85 sm:via-reel-bg/10" />

      <div key={item.id || index} style={{ opacity: textFade, transition: dragging ? 'none' : 'opacity 200ms' }} className="relative z-[3] h-full flex flex-col justify-end px-4 sm:px-6 pb-4 sm:pb-6 max-w-6xl mx-auto page-fade-in">
        <span className="inline-flex w-fit items-center gap-1.5 text-[9px] font-bold tracking-[0.1em] uppercase text-reel-gold bg-reel-gold/10 ring-1 ring-reel-gold/25 px-2 py-0.5 rounded-full mb-2">
          <svg width="9" height="9" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l2.9 6.6L22 9.3l-5 4.9 1.2 7-6.2-3.4L5.8 21.2 7 14.2 2 9.3l7.1-.7L12 2z"/></svg>
          Featured
        </span>
        <h1 className="font-display text-lg sm:text-3xl font-bold text-reel-ink drop-shadow-[0_2px_14px_rgba(0,0,0,0.7)] max-w-md sm:max-w-xl line-clamp-1">
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
            className="chip flex items-center gap-1.5 text-reel-ink font-medium text-xs sm:text-sm px-4 py-2 rounded-full active:scale-95 transition-transform"
            style={{ background: 'color-mix(in srgb, var(--reel-bg) 35%, transparent)', borderColor: 'color-mix(in srgb, var(--reel-ink) 20%, transparent)' }}
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
                onClick={() => go(i)}
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
