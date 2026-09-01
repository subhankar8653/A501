import { Link } from 'react-router-dom'
import { useState } from 'react'
import SearchOverlay from './SearchOverlay'
import ThemeSheet from './ThemeSheet'
import logo from '../assets/logo.png'

export default function Navbar() {
  const [searchOpen, setSearchOpen] = useState(false)
  const [themeOpen, setThemeOpen] = useState(false)

  return (
    <div className="sticky top-0 z-30">
      <header
        className="glossy-surface relative border-b border-reel-gold/[0.14] shadow-[0_8px_24px_-14px_rgba(0,0,0,0.9)]"
      >
        {/* Soft ambient glow behind the logo — gives the header some depth
            instead of a flat solid bar. Sits on the OPAQUE surface color
            (not on a blurred/see-through layer), so it never picks up
            whatever poster art happens to be scrolling underneath. */}
        <div
          aria-hidden="true"
          className="absolute top-0 left-0 w-48 h-full -z-10 pointer-events-none"
          style={{ background: 'radial-gradient(140px 70px at 12% 50%, rgba(232,163,61,0.14), transparent)' }}
        />
        <div className="max-w-6xl mx-auto px-3 sm:px-5 py-2 flex items-center justify-between gap-2.5">
          {/* Brand mark: the actual HukaTube logo (yellow badge, H + play
              glyph) supplied by the user — swapped in for the earlier
              generic SVG play-badge placeholder. */}
          <Link to="/" className="flex items-center gap-2.5 shrink-0 group">
            <img
              src={logo}
              alt="HukaTube"
              className="w-7 h-7 rounded-lg shadow-[0_4px_14px_-3px_rgba(232,163,61,0.6)] transition-transform duration-300 group-hover:scale-105"
            />
            <span className="font-display text-[1.15rem] sm:text-[1.3rem] font-bold tracking-tight leading-none">
              <span className="bg-gradient-to-b from-[#F6CE87] to-reel-gold bg-clip-text text-transparent">Huka</span><span className="text-reel-ink">Tube</span>
            </span>
          </Link>

          <div className="flex-1" />

          {/* FEATURE (user ask: "search icon ke aage ek aur icon add karo
              jo theme mode change karne ka") — same glossy-btn treatment
              as the search button so it reads as an equal, first-class
              header action rather than a bolted-on extra. Opens ThemeSheet,
              which lists every mode from the admin/owner panel's theme
              registry (see src/theme/themes.js). */}
          <button
            onClick={() => setThemeOpen(true)}
            aria-label="Change theme"
            className="glossy-btn group/theme shrink-0 w-9 h-9 sm:w-10 sm:h-10 rounded-full flex items-center justify-center active:scale-90 transition-transform duration-200"
            style={{ '--glossy-btn-base': 'var(--reel-surface2)' }}
          >
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" className="relative z-10 text-reel-ink group-hover/theme:text-reel-gold transition-colors duration-200">
              <circle cx="12" cy="12" r="9" />
              <path d="M12 3a9 9 0 0 0 0 18 5 5 0 0 0 0-10 5 5 0 0 1 0-8Z" fill="currentColor" stroke="none" opacity="0.9" />
            </svg>
          </button>

          {/* Premium search button: gold gradient ring + glow (echoes the
              logo badge) instead of a flat grey circle, so it reads as a
              primary action rather than a generic icon button. */}
          <button
            onClick={() => setSearchOpen(true)}
            aria-label="Search"
            className="glossy-btn group/search shrink-0 w-9 h-9 sm:w-10 sm:h-10 rounded-full flex items-center justify-center active:scale-90 transition-transform duration-200"
            style={{ '--glossy-btn-base': 'var(--reel-surface2)' }}
          >
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" className="relative z-10 text-reel-ink group-hover/search:text-reel-gold transition-colors duration-200">
              <circle cx="11" cy="11" r="7" />
              <path d="m21 21-4.3-4.3" />
            </svg>
          </button>
        </div>
      </header>

      {searchOpen ? <SearchOverlay onClose={() => setSearchOpen(false)} /> : null}
      {themeOpen ? <ThemeSheet open={themeOpen} onClose={() => setThemeOpen(false)} /> : null}
    </div>
  )
}
