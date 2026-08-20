import { useNavigate } from 'react-router-dom'
import { useDownloadsList } from '../lib/downloadsStore'

// Sticky bar just above the bottom nav — visible from anywhere in the app
// while a download is running, with a tap-through to the Downloads tab
// (user ka ask: "download karte time niche notification aa jaega, jis per
// click karne se download wale category per chale jaenge").
export default function DownloadToast() {
  const navigate = useNavigate()
  const downloads = useDownloadsList()
  const active = downloads.filter((d) => d.status === 'downloading')

  if (!active.length) return null

  const first = active[0]
  const label =
    active.length === 1
      ? first.filename
      : `${first.filename} +${active.length - 1} aur`

  return (
    <button
      onClick={() => navigate('/downloads')}
      // BUG FIX (user report: "download wali notification footer ke niche
      // chhup ja rahi hai"): yeh aur BottomNav dono same z-40 par the — jab
      // dono same z-index share karte hain, DOM order tay karta hai kaun
      // upar dikhega, aur BottomNav baad mein render hota hai isliye ussi ka
      // paint upar (is toast ke upar) chala jaata tha jahan bhi thoda-bahut
      // overlap hota. z-45 se ab yeh hamesha nav ke upar hi dikhega.
      className="fixed left-2 right-2 z-50 bg-reel-surface2 ring-1 ring-white/10 rounded-xl px-3 py-2 flex items-center gap-3 text-left shadow-[0_8px_24px_-8px_rgba(0,0,0,0.6)] active:scale-[0.98] transition"
      style={{ bottom: 'calc(72px + env(safe-area-inset-bottom))' }}
    >
      <span className="w-3.5 h-3.5 border-2 border-reel-muted/30 border-t-reel-gold rounded-full animate-spin shrink-0" />
      <span className="min-w-0 flex-1">
        <span className="block text-xs text-reel-ink truncate">{label}</span>
        <span className="block h-1 mt-1 rounded-full bg-reel-bg overflow-hidden">
          <span
            className="block h-full bg-reel-gold transition-all"
            style={{ width: `${first.progress || 0}%` }}
          />
        </span>
      </span>
      <span className="text-xs text-reel-gold font-semibold shrink-0">{first.progress || 0}%</span>
    </button>
  )
}
