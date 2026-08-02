import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useRef, useState } from 'react'
import { clearConfig } from '../api'

export default function Navbar() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [focused, setFocused] = useState(false)
  const [value, setValue] = useState(params.get('q') || '')
  const inputRef = useRef(null)

  function handleReset() {
    clearConfig()
    navigate('/setup')
  }

  function submit(e) {
    e?.preventDefault()
    const q = value.trim()
    if (q) navigate(`/search?q=${encodeURIComponent(q)}`)
  }

  return (
    <div className="sticky top-0 z-30">
      <header className="bg-reel-surface/95 backdrop-blur border-b border-white/5 shadow-[0_8px_24px_-16px_rgba(0,0,0,0.7)]">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-3 flex items-center justify-between gap-4">
          <Link to="/" className="flex items-baseline gap-2 shrink-0 group">
            <span className="font-display text-2xl font-semibold text-reel-gold transition-transform duration-300 group-hover:-translate-y-0.5">
              Suhani
            </span>
            <span className="font-display text-2xl italic text-reel-ink">Screen</span>
          </Link>

          <form onSubmit={submit} className="flex-1 max-w-md">
            <div
              className={`relative flex items-center rounded-full border transition-all duration-200 ${
                focused
                  ? 'border-reel-gold/70 bg-reel-surface2 shadow-[0_0_0_4px_rgba(232,163,61,0.12)]'
                  : 'border-white/5 bg-reel-surface2 hover:border-white/15'
              }`}
            >
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.3"
                strokeLinecap="round"
                strokeLinejoin="round"
                className={`ml-3.5 shrink-0 transition-colors ${focused ? 'text-reel-gold' : 'text-reel-muted'}`}
              >
                <circle cx="11" cy="11" r="7" />
                <path d="m21 21-4.3-4.3" />
              </svg>
              <input
                ref={inputRef}
                name="q"
                type="text"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                onFocus={() => setFocused(true)}
                onBlur={() => setFocused(false)}
                aria-label="Search titles"
                placeholder="Search titles…"
                className="w-full bg-transparent text-reel-ink placeholder-reel-muted px-3 py-2 text-sm focus:outline-none"
              />
              {value ? (
                <button
                  type="button"
                  aria-label="Clear search"
                  onClick={() => {
                    setValue('')
                    inputRef.current?.focus()
                  }}
                  className="mr-1 shrink-0 w-6 h-6 rounded-full flex items-center justify-center text-reel-muted hover:text-reel-ink hover:bg-white/5 transition active:scale-90"
                >
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                    <path d="M18 6 6 18M6 6l12 12" />
                  </svg>
                </button>
              ) : null}
              <button
                type="submit"
                aria-label="Search"
                className="mr-1 shrink-0 w-8 h-8 rounded-full flex items-center justify-center bg-reel-gold text-black hover:brightness-110 active:scale-90 transition disabled:opacity-40 disabled:pointer-events-none"
                disabled={!value.trim()}
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M5 12h14M13 6l6 6-6 6" />
                </svg>
              </button>
            </div>
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
