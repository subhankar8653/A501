import { useRef, useState } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import MediaCard from './MediaCard'

export default function ContentRail({ title, items, icon = '' }) {
  const scrollRef = useRef(null)
  const [canScrollLeft, setCanScrollLeft] = useState(false)
  const [canScrollRight, setCanScrollRight] = useState(true)

  const checkScroll = () => {
    const el = scrollRef.current
    if (!el) return
    setCanScrollLeft(el.scrollLeft > 0)
    setCanScrollRight(el.scrollLeft < el.scrollWidth - el.clientWidth - 10)
  }

  const scroll = (direction) => {
    const el = scrollRef.current
    if (!el) return
    const scrollAmount = el.clientWidth * 0.8
    el.scrollBy({ left: direction * scrollAmount, behavior: 'smooth' })
    setTimeout(checkScroll, 300)
  }

  if (!items?.length) return null

  return (
    <section className="py-6 relative group/rail">
      {/* Section Header */}
      <div className="px-4 sm:px-6 lg:px-8 mb-4 flex items-center gap-2">
        <h2 className="text-lg sm:text-xl font-bold text-white flex items-center gap-2">
          {icon && <span>{icon}</span>}
          {title}
        </h2>
        <span className="text-sm text-netflix-gray">({items.length})</span>
      </div>

      {/* Rail Container */}
      <div className="relative">
        {/* Left Arrow */}
        {canScrollLeft && (
          <button
            onClick={() => scroll(-1)}
            className="absolute left-0 top-0 bottom-0 z-20 w-16 bg-gradient-to-r from-netflix-black to-transparent flex items-center justify-start pl-2 opacity-0 group-hover/rail:opacity-100 transition-opacity"
          >
            <div className="w-10 h-10 rounded-full bg-black/50 flex items-center justify-center hover:bg-white/20 transition-colors">
              <ChevronLeft className="w-6 h-6 text-white" />
            </div>
          </button>
        )}

        {/* Scrollable Content */}
        <div
          ref={scrollRef}
          onScroll={checkScroll}
          className="flex gap-3 overflow-x-auto no-scrollbar px-4 sm:px-6 lg:px-8 scroll-smooth"
        >
          {items.map((item, idx) => (
            <MediaCard key={item.id} item={item} index={idx} />
          ))}
        </div>

        {/* Right Arrow */}
        {canScrollRight && (
          <button
            onClick={() => scroll(1)}
            className="absolute right-0 top-0 bottom-0 z-20 w-16 bg-gradient-to-l from-netflix-black to-transparent flex items-center justify-end pr-2 opacity-0 group-hover/rail:opacity-100 transition-opacity"
          >
            <div className="w-10 h-10 rounded-full bg-black/50 flex items-center justify-center hover:bg-white/20 transition-colors">
              <ChevronRight className="w-6 h-6 text-white" />
            </div>
          </button>
        )}
      </div>
    </section>
  )
}
