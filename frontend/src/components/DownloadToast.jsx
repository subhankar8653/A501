import { useNavigate } from 'react-router-dom'
import { useDownloadsList } from '../lib/downloadsStore'
import { useLanguage } from '../i18n/LanguageContext'

// Compact download progress pill, docked right above the bottom nav (visible from anywhere
// while a download runs; tap opens the Downloads tab). One slim row: spinner, file name,
// percent — the progress is a hairline along the pill's bottom edge instead of a second row.
export default function DownloadToast() {
  const navigate = useNavigate()
  const downloads = useDownloadsList()
  const { t } = useLanguage()
  const active = downloads.filter((d) => d.status === 'downloading')

  if (!active.length) return null

  const first = active[0]
  const pct = Math.max(0, Math.min(100, Math.round(first.progress || 0)))
  const label = active.length === 1 ? first.filename : `${active.length} ${t('more')}`

  return (
    <button
      onClick={() => navigate('/downloads')}
      // z-50 > BottomNav z-40, so it never slips under the nav.
      className="fixed left-3 right-3 z-50 mx-auto max-w-md overflow-hidden bg-reel-surface2/95 backdrop-blur ring-1 ring-reel-ink/10 rounded-2xl pl-3 pr-3.5 h-11 flex items-center gap-2.5 text-left shadow-[0_6px_20px_-8px_rgba(0,0,0,0.65)] active:scale-[0.98] transition"
      style={{ bottom: 'calc(76px + env(safe-area-inset-bottom))' }}
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className="shrink-0 text-reel-gold" aria-hidden="true">
        <path d="M12 3v12m0 0 4-4m-4 4-4-4M5 20h14" />
      </svg>
      <span className="min-w-0 flex-1 text-[12px] font-medium text-reel-ink truncate">{label}</span>
      <span className="text-[12px] font-semibold text-reel-gold tabular-nums shrink-0">{pct}%</span>
      <span className="absolute left-0 right-0 bottom-0 h-[3px] bg-reel-ink/10">
        <span className="block h-full bg-reel-gold transition-[width] duration-300" style={{ width: `${pct}%` }} />
      </span>
    </button>
  )
}
