import { Link } from 'react-router-dom'
import { useState } from 'react'
import SearchOverlay from './SearchOverlay'

export default function Navbar() {
  const [searchOpen, setSearchOpen] = useState(false)

  return (
    <div className="sticky top-0 z-30">
      <header className="bg-reel-surface/95 backdrop-blur border-b border-white/5 shadow-[0_8px_24px_-16px_rgba(0,0,0,0.7)]">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-3 flex items-center justify-between gap-3">
          <Link to="/" className="flex items-baseline gap-2 shrink-0 group">
            <span className="font-display text-2xl font-semibold text-reel-gold transition-transform duration-300 group-hover:-translate-y-0.5">
              Huka
            </span>
            <span className="font-display text-2xl italic text-reel-ink">Tube</span>
          </Link>

          <div className="flex-1" />

          {/* Big, round, YouTube-style search button — opens a dedicated
              fullscreen search screen (history + live suggestions) instead
              of typing inline here. */}
          <button
            onClick={() => setSearchOpen(true)}
            aria-label="Search"
            className="shrink-0 w-11 h-11 sm:w-12 sm:h-12 rounded-full flex items-center justify-center bg-reel-surface2 border border-white/5 text-reel-ink hover:border-reel-gold/60 hover:text-reel-gold active:scale-90 transition-all duration-200"
          >
            <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="7" />
              <path d="m21 21-4.3-4.3" />
            </svg>
          </button>
        </div>
      </header>
      <div className="sprocket-rail-thin opacity-70" />

      {searchOpen ? <SearchOverlay onClose={() => setSearchOpen(false)} /> : null}
    </div>
  )
}
