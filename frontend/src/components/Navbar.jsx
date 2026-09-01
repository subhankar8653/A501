import { Link } from 'react-router-dom'
import { useState } from 'react'
import SearchOverlay from './SearchOverlay'
import ThemeSheet from './ThemeSheet'
import NotificationSheet from './NotificationSheet'
import { useNotifications } from '../lib/notificationsStore'
import logo from '../assets/logo.png'

// REDESIGN (user ask: "Home ko or jada professional or stylist... header or
// footer ko aur achcha karo, ek frontend designer ke tor pe"): the header
// used to be a fully "glossy plastic" bar — top sheen gradient + a hard
// gold border. That reads as a toy-app chrome rather than a premium
// streaming app, and the gold border in particular fights for attention
// with the gold logo and gold icons sitting right on top of it. This
// version is quieter: a translucent, blurred surface (content behind it
// stays faintly visible, like iOS/YouTube headers) with a hairline edge
// instead of a colored border, and icon buttons that only pick up color
// on interaction instead of being gold circles by default — so gold reads
// as "this is the important/active thing" everywhere in the header,
// not just "this is a button".
export default function Navbar() {
  const [searchOpen, setSearchOpen] = useState(false)
  const [themeOpen, setThemeOpen] = useState(false)
  const [notificationsOpen, setNotificationsOpen] = useState(false)
  const { unreadCount } = useNotifications()

  return (
    <div className="sticky top-0 z-30">
      <header className="chrome-surface chrome-edge-b relative">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 h-14 sm:h-16 flex items-center justify-between gap-2">
          {/* Brand mark: logo + wordmark, tightened up so it reads as one
              cohesive lockup instead of two separate elements. */}
          <Link to="/" className="flex items-center gap-2 shrink-0 group">
            <img
              src={logo}
              alt="HukaTube"
              className="w-7 h-7 rounded-lg shadow-[0_3px_10px_-3px_rgba(232,163,61,0.5)] transition-transform duration-300 group-hover:scale-105"
            />
            <span className="font-display text-[1.1rem] sm:text-[1.25rem] font-bold tracking-tight leading-none">
              <span className="bg-gradient-to-b from-[#F6CE87] to-reel-gold bg-clip-text text-transparent">Huka</span>
              <span className="text-reel-ink">Tube</span>
            </span>
          </Link>

          <div className="flex-1" />

          <div className="flex items-center gap-1 sm:gap-1.5">
            {/* Theme picker */}
            <button
              onClick={() => setThemeOpen(true)}
              aria-label="Change theme"
              className="icon-btn w-9 h-9 sm:w-10 sm:h-10"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="8.5" />
                <path d="M12 3.5a8.5 8.5 0 0 0 0 17 4.5 4.5 0 0 0 0-9 4.5 4.5 0 0 1 0-8Z" fill="currentColor" stroke="none" opacity="0.85" />
              </svg>
            </button>

            {/* Notifications — small dot badge while there are unseen
                latest-upload notifications (see notificationsStore.js). */}
            <button
              onClick={() => setNotificationsOpen(true)}
              aria-label="Notifications"
              className="icon-btn relative w-9 h-9 sm:w-10 sm:h-10"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
                <path d="M13.73 21a2 2 0 0 1-3.46 0" />
              </svg>
              {unreadCount > 0 ? (
                <span
                  className="absolute top-1.5 right-1.5 w-2 h-2 rounded-full bg-reel-gold ring-2 ring-reel-surface"
                  aria-hidden="true"
                />
              ) : null}
            </button>

            {/* Search stays the one gold, "primary action" button in the
                header — everything else is quiet by comparison. */}
            <button
              onClick={() => setSearchOpen(true)}
              aria-label="Search"
              className="glossy-btn ml-1 shrink-0 w-9 h-9 sm:w-10 sm:h-10 rounded-full flex items-center justify-center active:scale-90 transition-transform duration-200"
              style={{ '--glossy-btn-base': 'var(--reel-gold)' }}
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className="relative z-10 text-reel-bg">
                <circle cx="11" cy="11" r="7" />
                <path d="m21 21-4.3-4.3" />
              </svg>
            </button>
          </div>
        </div>
      </header>

      {searchOpen ? <SearchOverlay onClose={() => setSearchOpen(false)} /> : null}
      {themeOpen ? <ThemeSheet open={themeOpen} onClose={() => setThemeOpen(false)} /> : null}
      {notificationsOpen ? (
        <NotificationSheet open={notificationsOpen} onClose={() => setNotificationsOpen(false)} />
      ) : null}
    </div>
  )
}
