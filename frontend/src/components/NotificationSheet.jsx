import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { useEffect } from 'react'
import { useNotifications, markNotificationsSeen } from '../lib/notificationsStore'
import { useLanguage } from '../i18n/LanguageContext'

// FEATURE (user ask: "theme mode change bala icon hai uske pass main
// notification option vi add karo"): same bottom-sheet chrome/portal
// pattern as ThemeSheet (see that file's comment for why it's a portal),
// listing the latest uploads across the whole app — the exact same pool
// "New to You" is built from (see notificationsStore.js) — so opening this
// never needs its own backend endpoint.
export default function NotificationSheet({ open, onClose }) {
  const { items, loading } = useNotifications()
  const { t } = useLanguage()

  // Opening the sheet is the "I've seen these" moment — clears the bell's
  // unread badge, same idea as opening an inbox.
  useEffect(() => {
    if (open) markNotificationsSeen()
  }, [open])

  if (!open) return null

  return createPortal(
    <div className="fixed inset-0 z-[95] flex items-end justify-center" onClick={onClose}>
      <div className="absolute inset-0 bg-black/60" />
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-md bg-reel-bg rounded-t-2xl pt-3 pb-[calc(1.5rem+env(safe-area-inset-bottom))] px-5 ring-1 ring-reel-ink/10 shadow-[0_-8px_32px_rgba(0,0,0,0.7)] max-h-[80vh] overflow-y-auto"
      >
        <div className="w-10 h-1 rounded-full bg-reel-ink/15 mx-auto mb-4" />

        <p className="text-reel-ink font-semibold mb-3">{t('notifications_title')}</p>

        {loading ? (
          <div className="space-y-2.5 mb-1">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="h-14 rounded-xl bg-reel-surface2 animate-pulse" />
            ))}
          </div>
        ) : items.length === 0 ? (
          <p className="text-reel-muted text-sm py-6 text-center">{t('notifications_empty')}</p>
        ) : (
          <div className="space-y-2 mb-1">
            {items.map((item) => (
              <Link
                key={item.id}
                to={`/title/${item.type}/${encodeURIComponent(item.id)}`}
                onClick={onClose}
                className="flex items-center gap-3 px-2 py-2 rounded-xl hover:bg-reel-ink/[0.04] active:scale-[0.98] transition"
              >
                <span className="shrink-0 w-10 h-14 rounded-md overflow-hidden bg-reel-surface2 ring-1 ring-reel-ink/10">
                  {item.poster ? (
                    <img src={item.poster} alt={item.name} loading="lazy" className="w-full h-full object-cover" />
                  ) : null}
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-medium text-reel-ink truncate">{item.name}</span>
                  <span className="block text-[11px] text-reel-muted">
                    {item.type === 'series' ? 'Series' : 'Movie'}
                    {item.releaseInfo ? ` · ${item.releaseInfo}` : ''}
                  </span>
                </span>
              </Link>
            ))}
          </div>
        )}
      </div>
    </div>,
    document.body
  )
}
