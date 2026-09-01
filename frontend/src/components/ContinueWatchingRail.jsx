import { Link } from 'react-router-dom'
import { memo } from 'react'
import { useLanguage } from '../i18n/LanguageContext'

// FEATURE (user ask: "Watch history / Continue Watching"): YouTube-style
// resume row on Home — backend already returns items newest-first, capped
// at 40 (dekho api.js getContinueWatching + database.py). Each item's `k`
// is the resume-lookup key (episode id for series, media id for movies),
// which is exactly what /watch/{type}/{id} expects.
function ContinueWatchingRail({ items, onRemove }) {
  const { t } = useLanguage()
  if (!items || !items.length) return null

  return (
    <section className="mb-8">
      <h2 className="font-display text-xl font-semibold mb-3 px-4 sm:px-0">{t('continue_watching')}</h2>
      <div className="flex gap-3 overflow-x-auto no-scrollbar px-4 sm:px-0 pb-1">
        {items.map((it) => {
          const pct = it.dur > 0 ? Math.min(100, Math.round((it.pos / it.dur) * 100)) : 0
          return (
            <div key={it.k} className="relative group shrink-0 w-[calc(60vw-24px)] sm:w-[240px]">
              <Link to={`/watch/${it.media_type}/${encodeURIComponent(it.k)}`} className="block active:scale-95 transition-transform">
                <div className="relative aspect-video rounded-lg overflow-hidden bg-reel-surface2 ring-1 ring-reel-ink/[0.06]">
                  {it.poster ? (
                    <img src={it.poster} alt={it.title} loading="lazy" className="w-full h-full object-cover" />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center text-reel-muted text-xs px-2 text-center">
                      {it.title}
                    </div>
                  )}
                  <div className="absolute inset-0 bg-black/25 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
                    <div className="w-9 h-9 rounded-full bg-reel-gold/90 text-reel-bg flex items-center justify-center">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
                    </div>
                  </div>
                  {/* Resume progress bar */}
                  <div className="absolute bottom-0 left-0 right-0 h-1 bg-reel-ink/20">
                    <div className="h-full bg-reel-gold" style={{ width: `${pct}%` }} />
                  </div>
                </div>
                <p className="mt-1.5 text-[13px] font-medium text-reel-ink line-clamp-1">{it.title}</p>
              </Link>
              <button
                onClick={(e) => {
                  e.preventDefault()
                  onRemove?.(it)
                }}
                aria-label="Remove from Continue Watching"
                className="absolute top-1.5 right-1.5 w-6 h-6 rounded-full bg-black/70 backdrop-blur flex items-center justify-center text-reel-ink active:scale-90 transition opacity-0 group-hover:opacity-100"
              >
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round"><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></svg>
              </button>
            </div>
          )
        })}
      </div>
    </section>
  )
}

export default memo(ContinueWatchingRail)
