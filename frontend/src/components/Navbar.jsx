import { Link } from 'react-router-dom'
import { useState } from 'react'
import SearchOverlay from './SearchOverlay'

export default function Navbar() {
  const [searchOpen, setSearchOpen] = useState(false)

  return (
    <div className="sticky top-0 z-30">
      <header className="relative bg-gradient-to-b from-reel-surface to-reel-surface/97 backdrop-blur-md border-b border-reel-gold/[0.14] shadow-[0_8px_24px_-14px_rgba(0,0,0,0.8)]">
        {/* Soft ambient glow behind the logo — gives the header some depth
            instead of a flat solid bar. */}
        <div
          aria-hidden="true"
          className="absolute top-0 left-0 w-48 h-full -z-10 pointer-events-none"
          style={{ background: 'radial-gradient(140px 70px at 12% 50%, rgba(232,163,61,0.16), transparent)' }}
        />
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-3 flex items-center justify-between gap-3">
          {/* Logomark: a rounded play-badge (readable at a glance as "watch/
              stream") next to a single clean wordmark — replaces the old
              plain-text "Huka" + italic "Tube" pairing that read as two
              unrelated words instead of one brand. */}
          <Link to="/" className="flex items-center gap-2.5 shrink-0 group">
            <span className="relative flex items-center justify-center w-9 h-9 rounded-xl bg-gradient-to-br from-[#F6CE87] to-reel-gold shadow-[0_4px_14px_-3px_rgba(232,163,61,0.6)] transition-transform duration-300 group-hover:scale-105">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="#1A1824" className="translate-x-[1px]">
                <path d="M8 5v14l11-7z" />
              </svg>
              {/* Tiny sprocket notches on the badge edge — keeps the film-reel
                  motif alive in one small, deliberate spot instead of a
                  loose dotted line running under the whole header. */}
              <span className="absolute -bottom-[3px] left-1/2 -translate-x-1/2 flex gap-[3px]">
                <span className="w-[3px] h-[3px] rounded-full bg-reel-bg/70" />
                <span className="w-[3px] h-[3px] rounded-full bg-reel-bg/70" />
                <span className="w-[3px] h-[3px] rounded-full bg-reel-bg/70" />
              </span>
            </span>
            <span className="font-display text-[1.4rem] sm:text-[1.55rem] font-bold tracking-tight leading-none">
              <span className="bg-gradient-to-b from-[#F6CE87] to-reel-gold bg-clip-text text-transparent">Huka</span><span className="text-reel-ink">Tube</span>
            </span>
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

      {searchOpen ? <SearchOverlay onClose={() => setSearchOpen(false)} /> : null}
    </div>
  )
}
