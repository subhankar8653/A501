import { useLocation, useNavigate } from 'react-router-dom'
import { useOnlineStatus, useBackendHealth } from '../lib/connectivity'
import { useLanguage } from '../i18n/LanguageContext'

// Routes that work fully from local data (IndexedDB / localStorage) and
// therefore should stay usable even with no internet / a dead backend.
// The overlay never covers these — that's the whole point of "offline
// downloads should still play like a YouTube-offline download".
const OFFLINE_SAFE_PATHS = ['/downloads', '/saved', '/profile', '/setup']

function isOfflineSafe(pathname) {
  return OFFLINE_SAFE_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`))
}

export default function ConnectionOverlay() {
  const isOnline = useOnlineStatus()
  const isServerUp = useBackendHealth(isOnline)
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const { t } = useLanguage()

  const problem = !isOnline ? 'offline' : !isServerUp ? 'server-down' : null
  if (!problem || isOfflineSafe(pathname)) return null

  const copy =
    problem === 'offline'
      ? {
          title: t('overlay_offline_title'),
          body: t('overlay_offline_body'),
        }
      : {
          title: t('overlay_server_title'),
          body: t('overlay_server_body'),
        }

  return (
    <div className="fixed inset-0 z-[70] flex flex-col items-center justify-center gap-5 bg-reel-bg px-6 text-center">
      <div className="w-16 h-16 rounded-full bg-reel-surface2 ring-1 ring-reel-gold/25 flex items-center justify-center">
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-reel-gold">
          {problem === 'offline' ? (
            <>
              <line x1="2" y1="2" x2="22" y2="22" />
              <path d="M8.5 16.5a5 5 0 0 1 7 0" />
              <path d="M5 12.9a10 10 0 0 1 3.5-2.4" />
              <path d="M19 12.9a10 10 0 0 0 -2.2-1.8" />
              <path d="M1.5 8.5A16 16 0 0 1 6 5.5" />
              <path d="M22.5 8.5a16 16 0 0 0 -6-3.4" />
              <line x1="12" y1="20" x2="12.01" y2="20" />
            </>
          ) : (
            <>
              <path d="M12 9v4" />
              <path d="M12 17h.01" />
              <path d="M10.3 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.7 3.86a2 2 0 0 0-3.4 0Z" />
            </>
          )}
        </svg>
      </div>

      <div>
        <h2 className="font-display text-xl text-reel-ink mb-2">{copy.title}</h2>
        <p className="text-sm text-reel-muted max-w-xs">{copy.body}</p>
      </div>

      <button
        onClick={() => navigate('/downloads')}
        className="px-5 py-2.5 rounded-full text-sm font-semibold bg-gradient-to-b from-[color-mix(in_srgb,var(--reel-gold)_65%,white)] to-reel-gold text-reel-bg active:scale-95 transition"
      >
        {t('overlay_view_downloads')}
      </button>
    </div>
  )
}
