import { NavLink } from 'react-router-dom'
import { useDownloadsList } from '../lib/downloadsStore'

const TABS = [
  {
    to: '/',
    label: 'Home',
    icon: (active) => (
      <svg width="22" height="22" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round">
        <path d="m3 11 9-8 9 8" />
        <path d="M5 10v10a1 1 0 0 0 1 1h3v-6h6v6h3a1 1 0 0 0 1-1V10" />
      </svg>
    ),
  },
  {
    to: '/saved',
    label: 'Saved',
    icon: (active) => (
      <svg width="22" height="22" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round">
        <path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" />
      </svg>
    ),
  },
  {
    to: '/downloads',
    label: 'Downloads',
    icon: (active) => (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" fill={active ? 'currentColor' : 'none'} />
        <polyline points="7 10 12 15 17 10" />
        <line x1="12" y1="15" x2="12" y2="3" />
      </svg>
    ),
  },
  {
    to: '/profile',
    label: 'Profile',
    icon: (active) => (
      <svg width="22" height="22" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="8" r="4" />
        <path d="M4 20c0-3.9 3.6-7 8-7s8 3.1 8 7" />
      </svg>
    ),
  },
]

export default function BottomNav() {
  const downloads = useDownloadsList()
  const activeCount = downloads.filter((d) => d.status === 'downloading').length

  return (
    <nav className="fixed bottom-0 inset-x-0 z-40 bg-gradient-to-t from-reel-surface to-reel-surface/97 backdrop-blur-md border-t border-reel-gold/[0.14] shadow-[0_-8px_24px_-14px_rgba(0,0,0,0.8)] pb-[env(safe-area-inset-bottom)]">
      <div className="max-w-6xl mx-auto grid grid-cols-4 px-2 py-1.5">
        {TABS.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            end={tab.to === '/'}
            className="relative flex justify-center py-1"
          >
            {({ isActive }) => (
              <span
                className={`relative flex flex-col items-center justify-center gap-0.5 w-full py-1.5 rounded-2xl text-[10.5px] font-semibold tracking-wide active:scale-95 transition-all duration-200 ${
                  isActive
                    ? 'bg-gradient-to-b from-[#F3C067]/15 to-reel-gold/10 text-reel-gold ring-1 ring-reel-gold/25'
                    : 'text-reel-muted hover:text-reel-ink'
                }`}
              >
                {tab.icon(isActive)}
                {tab.label}
                {tab.to === '/downloads' && activeCount > 0 ? (
                  <span className="absolute top-0.5 right-[22%] w-4 h-4 rounded-full bg-reel-gold text-reel-bg text-[9px] font-bold flex items-center justify-center shadow-[0_2px_6px_-1px_rgba(232,163,61,0.7)]">
                    {activeCount}
                  </span>
                ) : null}
              </span>
            )}
          </NavLink>
        ))}
      </div>
    </nav>
  )
}
