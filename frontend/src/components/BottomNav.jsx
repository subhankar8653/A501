import { useState } from 'react'
import { NavLink } from 'react-router-dom'
import { useDownloadsList } from '../lib/downloadsStore'
import { useOnlineStatus } from '../lib/connectivity'
import { useLanguage } from '../i18n/LanguageContext'

const TABS = [
  {
    to: '/',
    labelKey: 'nav_home',
    icon: (active) => (
      <svg width="20" height="20" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round">
        <path d="m3 11 9-8 9 8" />
        <path d="M5 10v10a1 1 0 0 0 1 1h3v-6h6v6h3a1 1 0 0 0 1-1V10" />
      </svg>
    ),
  },
  {
    to: '/saved',
    labelKey: 'nav_saved',
    icon: (active) => (
      <svg width="20" height="20" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round">
        <path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" />
      </svg>
    ),
  },
  {
    to: '/downloads',
    labelKey: 'nav_downloads',
    icon: (active) => (
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" fill={active ? 'currentColor' : 'none'} />
        <polyline points="7 10 12 15 17 10" />
        <line x1="12" y1="15" x2="12" y2="3" />
      </svg>
    ),
  },
  {
    to: '/profile',
    labelKey: 'nav_profile',
    icon: (active) => (
      <svg width="20" height="20" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="8" r="4" />
        <path d="M4 20c0-3.9 3.6-7 8-7s8 3.1 8 7" />
      </svg>
    ),
  },
]

// Small icon shown on the Home tab in place of the house icon while
// offline, so it visually reads as "locked" rather than just another tab.
function LockIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round">
      <rect x="4" y="10" width="16" height="10" rx="2" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </svg>
  )
}

// REDESIGN (user ask: "header or footer ko aur achcha karo... professional
// or modern stylish"): the active tab used to get a full glossy pill
// background behind icon+label — busy against four tabs sitting this close
// together. Swapped for a small indicator bar above the active icon (the
// same language YouTube Music / Spotify's bottom nav use) plus a solid
// icon+label color change — calmer, and the eye still finds the active
// tab instantly. Surface treatment now shares .chrome-surface/.elevate-up
// with the header so both bars read as the same material.
export default function BottomNav() {
  const downloads = useDownloadsList()
  const isOnline = useOnlineStatus()
  const { t } = useLanguage()
  const activeCount = downloads.filter((d) => d.status === 'downloading').length
  // FEATURE (user ask: Home tab "lock" ho jaana chahiye jab offline ho, aur
  // "internet on karo" bolna chahiye) — tapping the locked Home tab doesn't
  // navigate, it just flashes this message for a couple seconds.
  const [showOfflineHint, setShowOfflineHint] = useState(false)

  function handleHomeTap(e) {
    if (isOnline) return
    e.preventDefault()
    setShowOfflineHint(true)
    setTimeout(() => setShowOfflineHint(false), 2200)
  }

  return (
    <nav className="chrome-surface chrome-edge-t elevate-up fixed bottom-0 inset-x-0 z-40 pb-[env(safe-area-inset-bottom)]">
      {showOfflineHint ? (
        <div className="chip absolute left-1/2 -translate-x-1/2 -top-11 px-3.5 py-2 rounded-full text-xs text-reel-ink whitespace-nowrap elevate page-fade-in">
          {t('nav_home_locked_hint')}
        </div>
      ) : null}
      <div className="max-w-6xl mx-auto grid grid-cols-4 px-2">
        {TABS.map((tab) => {
          const isHome = tab.to === '/'
          const locked = isHome && !isOnline
          return (
            <NavLink
              key={tab.to}
              to={tab.to}
              end={isHome}
              onClick={isHome ? handleHomeTap : undefined}
              aria-disabled={locked}
              className="relative flex justify-center"
            >
              {({ isActive }) => (
                <span
                  className={`relative flex flex-col items-center justify-center gap-1 w-full pt-2.5 pb-2 text-[10px] font-semibold tracking-wide transition-colors duration-200 active:scale-95 ${
                    locked ? 'text-reel-muted/50' : isActive ? 'text-reel-gold' : 'text-reel-muted hover:text-reel-ink'
                  }`}
                >
                  {/* Active indicator bar — replaces the old full-pill fill. */}
                  <span
                    className={`absolute top-0 h-[3px] rounded-full bg-reel-gold transition-all duration-200 ${
                      isActive && !locked ? 'w-6 opacity-100' : 'w-0 opacity-0'
                    }`}
                    aria-hidden="true"
                  />
                  {locked ? <LockIcon /> : tab.icon(isActive)}
                  {t(tab.labelKey)}
                  {tab.to === '/downloads' && activeCount > 0 ? (
                    <span className="glossy-btn absolute top-0.5 right-[22%] w-4 h-4 rounded-full text-reel-bg text-[9px] font-bold flex items-center justify-center">
                      <span className="relative z-10">{activeCount}</span>
                    </span>
                  ) : null}
                </span>
              )}
            </NavLink>
          )
        })}
      </div>
    </nav>
  )
}
