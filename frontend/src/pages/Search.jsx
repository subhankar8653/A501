import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { getCatalogAllPages, isVerified } from '../api'
import MediaCard from '../components/MediaCard'
import VerifyGate from '../components/VerifyGate'
import { useLanguage } from '../i18n/LanguageContext'

export default function Search() {
  const [params] = useSearchParams()
  const q = params.get('q') || ''
  const [results, setResults] = useState(null)
  const [failed, setFailed] = useState(false)
  const [retryKey, setRetryKey] = useState(0)
  const verified = isVerified()
  const { t } = useLanguage()

  useEffect(() => {
    if (!verified) return
    if (!q) {
      setResults([])
      setFailed(false)
      return
    }
    setResults(null)
    setFailed(false)
    Promise.all([
      getCatalogAllPages('movie', 'top_movies', { search: q }),
      getCatalogAllPages('series', 'top_series', { search: q }),
    ])
      .then(([movies, series]) => setResults([...movies, ...series]))
      .catch(() => {
        setResults([])
        setFailed(true)
      })
  }, [q, retryKey, verified])

  if (!verified) {
    return <VerifyGate message={t('search_verify_message')} />
  }

  return (
    <div className="max-w-6xl mx-auto py-8 px-4 sm:px-0">
      <h1 className="font-display text-2xl font-semibold mb-6">
        {q ? (
          <>
            {t('search_results_for')} <span className="text-reel-gold">“{q}”</span>
          </>
        ) : (
          t('search_title')
        )}
      </h1>

      {results === null ? (
        <div className="grid grid-cols-3 sm:grid-cols-5 md:grid-cols-6 gap-4">
          {Array.from({ length: 12 }).map((_, i) => (
            <div key={i} className="aspect-[2/3] rounded-md bg-reel-surface2 animate-pulse" />
          ))}
        </div>
      ) : failed ? (
        <div>
          <p className="text-reel-rust mb-3">{t('search_failed')}</p>
          <button
            onClick={() => setRetryKey((k) => k + 1)}
            className="text-sm px-4 py-2 rounded-full bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-95 transition"
          >
            {t('retry')}
          </button>
        </div>
      ) : results.length === 0 ? (
        <p className="text-reel-muted">{t('search_no_results')}</p>
      ) : (
        <div className="grid grid-cols-3 sm:grid-cols-5 md:grid-cols-6 gap-x-3 gap-y-6">
          {results.map((item, i) => (
            <MediaCard key={item.id} item={item} index={i} />
          ))}
        </div>
      )}
    </div>
  )
}
