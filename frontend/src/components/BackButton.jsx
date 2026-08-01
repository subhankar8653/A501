import { useNavigate } from 'react-router-dom'

// Detail (/title/...) and Player (/watch/...) hide the site Navbar for a
// fullscreen layout, which also silently removed the only way to leave
// those pages — no back arrow, no breadcrumb, nothing. On a PWA / installed
// Android shell there's no browser chrome back button either, so this was a
// dead end. This gives every one of those pages an explicit way back.
//
// Falls back to Home instead of navigate(-1) when there's no in-app history
// to go back to (e.g. someone opened a /title or /watch link directly from
// a share or a fresh tab) — otherwise navigate(-1) would leave the app.
export default function BackButton({ className = '', variant = 'floating' }) {
  const navigate = useNavigate()

  function goBack() {
    if (window.history.length > 1) navigate(-1)
    else navigate('/')
  }

  if (variant === 'inline') {
    return (
      <button
        onClick={goBack}
        aria-label="Go back"
        className={`flex items-center gap-1 text-sm text-reel-muted hover:text-reel-ink active:scale-95 transition ${className}`}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round">
          <path d="m15 18-6-6 6-6" />
        </svg>
        Back
      </button>
    )
  }

  return (
    <button
      onClick={goBack}
      aria-label="Go back"
      className={`w-10 h-10 rounded-full bg-black/50 backdrop-blur ring-1 ring-white/10 flex items-center justify-center text-reel-ink hover:bg-black/70 active:scale-90 transition ${className}`}
    >
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round">
        <path d="m15 18-6-6 6-6" />
      </svg>
    </button>
  )
}
