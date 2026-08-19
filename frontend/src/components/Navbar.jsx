import { Link } from 'react-router-dom'
import { useState } from 'react'
import SearchOverlay from './SearchOverlay'

export default function Navbar() {
  const [searchOpen, setSearchOpen] = useState(false)

  return (
    <div className="sticky top-0 z-30">
      <header className="relative bg-reel-surface/95 backdrop-blur-md border-b border-white/[0.06] shadow-[0_8px_24px_-16px_rgba(0,0,0,0.7)]">
        {/* Soft ambient glow behind the logo — gives the header some depth
            instead of a flat solid bar. */}
        <div
          aria-hidden="true"
          className="absolute top-0 left-0 w-40 h-full -z-10 pointer-events-none"
          style={{ background: 'radial-gradient(120px 60px at 15% 50%, rgba(232,163,61,0.14), transparent)' }}
        />
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-3.5 flex items-center justify-between gap-3">
          <Link to="/" className="flex items-baseline gap-1.5 shrink-0 group">
            <span className="font-display text-[1.65rem] font-bold tracking-tight bg-gradient-to-b from-[#F6CE87] to-reel-gold bg-clip-text text-transparent transition-transform duration-300 group-hover:-translate-y-0.5">
              Huka
            </span>
            <span className="font-display text-[1.65rem] italic text-reel-ink">Tube</span>
          </Link>

          <div className="flex-1" />

          {/* Big, round, YouTube-style search button — opens a dedicated
              fullscreen search screen (history + live suggestions) instead
              of typing inline here. */}
          <button
            onClick={() => setSearchOpen(true)}
            aria-label="Search"
            className="shrink-0 w-11 h-11 sm:w-12 sm:h-12 rounded-full flex items-center justify-center bg-white/[0.06] ring-1 ring-white/10 text-reel-ink hover:ring-reel-gold/50 hover:text-reel-gold hover:bg-white/[0.09] active:scale-90 transition-all duration-200 shadow-[0_2px_10px_-4px_rgba(0,0,0,0.5)]"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
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
