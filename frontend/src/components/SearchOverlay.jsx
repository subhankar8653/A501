import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  getCatalog,
  getRecentSearches,
  addRecentSearch,
  removeRecentSearch,
  clearRecentSearches,
} from '../api'
import { useLanguage } from '../i18n/LanguageContext'

// Debounce delay for live suggestions while typing.
const SUGGEST_DEBOUNCE_MS = 300

export default function SearchOverlay({ initialQuery = '', onClose }) {
  const navigate = useNavigate()
  const { t } = useLanguage()
  const inputRef = useRef(null)
  const [query, setQuery] = useState(initialQuery)
  const [recent, setRecent] = useState(() => getRecentSearches())
  const [suggestions, setSuggestions] = useState([])
  const [loadingSuggestions, setLoadingSuggestions] = useState(false)

  // Autofocus the moment the overlay mounts, like YouTube's search screen.
  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  // Close on Escape (useful on devices with a hardware/back-acting key).
  useEffect(() => {
    function onKey(e) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // Live "video ka suggestion" list below the input, debounced while typing.
  // Deliberately a single (unpaginated) fetch per type — this is a fast
  // preview list, not the full results grid (that's what Enter/submit is for).
  useEffect(() => {
    const q = query.trim()
    if (!q) {
      setSuggestions([])
      setLoadingSuggestions(false)
      return
    }
    let cancelled = false
    setLoadingSuggestions(true)
    const timer = setTimeout(() => {
      Promise.all([
        getCatalog('movie', 'top_movies', { search: q }),
        getCatalog('series', 'top_series', { search: q }),
      ])
        .then(([movies, series]) => {
          if (cancelled) return
          setSuggestions([...movies, ...series].slice(0, 8))
        })
        .catch(() => {
          if (!cancelled) setSuggestions([])
        })
        .finally(() => {
          if (!cancelled) setLoadingSuggestions(false)
        })
    }, SUGGEST_DEBOUNCE_MS)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [query])

  function runSearch(term) {
    const clean = term.trim()
    if (!clean) return
    setRecent(addRecentSearch(clean))
    onClose()
    navigate(`/search?q=${encodeURIComponent(clean)}`)
  }

  function openTitle(item) {
    setRecent(addRecentSearch(item.name || query))
    onClose()
    navigate(`/title/${item.type}/${encodeURIComponent(item.id)}`)
  }

  return (
    <div className="fixed inset-0 z-50 bg-reel-bg flex flex-col page-fade-in">
      {/* Header: back + big input, YouTube jaisa — no separate content above it */}
      <div className="flex items-center gap-2 px-3 py-2.5 border-b border-white/5 shrink-0">
        <button
          onClick={onClose}
          aria-label="Close search"
          className="shrink-0 w-10 h-10 rounded-full flex items-center justify-center text-reel-ink hover:bg-white/5 active:scale-90 transition"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round">
            <path d="m15 18-6-6 6-6" />
          </svg>
        </button>

        <form
          className="flex-1"
          onSubmit={(e) => {
            e.preventDefault()
            runSearch(query)
          }}
        >
          <div className="flex items-center rounded-full bg-reel-surface2 border border-white/5 focus-within:border-reel-gold/70 focus-within:shadow-[0_0_0_4px_rgba(232,163,61,0.12)] transition-all duration-200">
            <input
              ref={inputRef}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              type="text"
              inputMode="search"
              enterKeyHint="search"
              aria-label="Search titles"
              placeholder={t('search_placeholder')}
              className="w-full bg-transparent text-reel-ink placeholder-reel-muted px-4 py-2.5 text-base focus:outline-none"
            />
            {query ? (
              <button
                type="button"
                aria-label="Clear search"
                onClick={() => {
                  setQuery('')
                  inputRef.current?.focus()
                }}
                className="mr-1.5 shrink-0 w-7 h-7 rounded-full flex items-center justify-center text-reel-muted hover:text-reel-ink hover:bg-white/5 transition active:scale-90"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            ) : null}
          </div>
        </form>

        {/* Big, prominent, YouTube-style search button — separate from the
            input's own clear button, always visible, submits the raw query. */}
        <button
          onClick={() => runSearch(query)}
          disabled={!query.trim()}
          aria-label="Search"
          className="shrink-0 w-11 h-11 rounded-full flex items-center justify-center bg-reel-gold text-black hover:brightness-110 active:scale-90 transition disabled:opacity-30 disabled:pointer-events-none"
        >
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="11" cy="11" r="7" />
            <path d="m21 21-4.3-4.3" />
          </svg>
        </button>
      </div>

      {/* Body: recent searches (empty query) or live suggestions (typing) */}
      <div className="flex-1 overflow-y-auto">
        {!query.trim() ? (
          <div className="max-w-2xl mx-auto py-2">
            {recent.length > 0 ? (
              <>
                <div className="flex items-center justify-between px-4 py-2">
                  <h2 className="text-xs font-semibold text-reel-muted uppercase tracking-wide">{t('search_recent')}</h2>
                  <button
                    onClick={() => {
                      clearRecentSearches()
                      setRecent([])
                    }}
                    className="text-xs text-reel-gold hover:underline active:scale-95 transition"
                  >
                    {t('search_clear_all')}
                  </button>
                </div>
                {recent.map((term) => (
                  <div
                    key={term}
                    className="group flex items-center gap-3 px-4 py-2.5 hover:bg-reel-surface2 active:bg-reel-surface2 transition-colors cursor-pointer"
                    onClick={() => runSearch(term)}
                  >
                    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="shrink-0 text-reel-muted">
                      <circle cx="12" cy="12" r="9" />
                      <path d="M12 7v5l3 3" />
                    </svg>
                    <span className="flex-1 text-sm text-reel-ink truncate">{term}</span>
                    <button
                      aria-label={`Remove ${term}`}
                      onClick={(e) => {
                        e.stopPropagation()
                        setRecent(removeRecentSearch(term))
                      }}
                      className="shrink-0 w-7 h-7 rounded-full flex items-center justify-center text-reel-muted hover:text-reel-ink hover:bg-white/5 opacity-0 group-hover:opacity-100 transition active:scale-90"
                    >
                      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                        <path d="M18 6 6 18M6 6l12 12" />
                      </svg>
                    </button>
                  </div>
                ))}
              </>
            ) : (
              <p className="text-center text-reel-muted text-sm mt-10 px-4">
                {t('search_empty_hint')}
              </p>
            )}
          </div>
        ) : (
          <div className="max-w-2xl mx-auto py-2">
            {/* Pinned "search for exact term" row — classic YouTube pattern:
                always available even before any suggestion has loaded. */}
            <div
              className="flex items-center gap-3 px-4 py-2.5 hover:bg-reel-surface2 active:bg-reel-surface2 transition-colors cursor-pointer"
              onClick={() => runSearch(query)}
            >
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className="shrink-0 text-reel-muted">
                <circle cx="11" cy="11" r="7" />
                <path d="m21 21-4.3-4.3" />
              </svg>
              <span className="text-sm text-reel-ink">
                {t('search_for')} <span className="font-medium">“{query.trim()}”</span>
              </span>
            </div>

            {loadingSuggestions ? (
              <div className="px-4 py-2 space-y-1">
                {Array.from({ length: 4 }).map((_, i) => (
                  <div key={i} className="flex items-center gap-3 py-2">
                    <div className="w-10 h-14 rounded bg-reel-surface2 animate-pulse shrink-0" />
                    <div className="h-3.5 w-2/3 rounded bg-reel-surface2 animate-pulse" />
                  </div>
                ))}
              </div>
            ) : suggestions.length > 0 ? (
              <div className="mt-1 border-t border-white/5">
                {suggestions.map((item) => (
                  <div
                    key={item.id}
                    className="flex items-center gap-3 px-4 py-2 hover:bg-reel-surface2 active:bg-reel-surface2 transition-colors cursor-pointer"
                    onClick={() => openTitle(item)}
                  >
                    <div className="w-10 h-14 rounded overflow-hidden bg-reel-surface2 shrink-0">
                      {item.poster ? (
                        <img src={item.poster} alt={item.name} className="w-full h-full object-cover" />
                      ) : null}
                    </div>
                    <div className="min-w-0">
                      <p className="text-sm text-reel-ink truncate">{item.name}</p>
                      <p className="text-xs text-reel-muted truncate">{item.releaseInfo || item.year || ''}</p>
                    </div>
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        )}
      </div>
    </div>
  )
}
