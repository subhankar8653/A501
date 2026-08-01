import { Link, useNavigate } from 'react-router-dom'
import { clearConfig } from '../api'

export default function Navbar() {
  const navigate = useNavigate()

  function handleReset() {
    clearConfig()
    navigate('/setup')
  }

  return (
    <div className="sticky top-0 z-30">
      <header className="bg-reel-surface/95 backdrop-blur border-b border-white/5">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-3 flex items-center justify-between gap-4">
          <Link to="/" className="flex items-baseline gap-2 shrink-0">
            <span className="font-display text-2xl font-semibold text-reel-gold">Suhani</span>
            <span className="font-display text-2xl italic text-reel-ink">Screen</span>
          </Link>
          <form
            className="flex-1 max-w-md"
            onSubmit={(e) => {
              e.preventDefault()
              const q = e.target.elements.q.value.trim()
              if (q) navigate(`/search?q=${encodeURIComponent(q)}`)
            }}
          >
            <input
              name="q"
              type="text"
              aria-label="Search titles"
              placeholder="Search titles…"
              className="w-full bg-reel-surface2 text-reel-ink placeholder-reel-muted rounded-full px-4 py-2 text-sm border border-white/5 focus:outline-none focus:ring-2 focus:ring-reel-gold/60"
            />
          </form>
          <button
            onClick={handleReset}
            className="text-xs text-reel-muted hover:text-reel-ink active:scale-95 transition-colors shrink-0"
          >
            Change source
          </button>
        </div>
      </header>
      <div className="sprocket-rail-thin opacity-70" />
    </div>
  )
}
