import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { getCatalog } from '../api'
import MediaCard from '../components/MediaCard'

export default function Search() {
  const [params] = useSearchParams()
  const q = params.get('q') || ''
  const [results, setResults] = useState(null)

  useEffect(() => {
    if (!q) {
      setResults([])
      return
    }
    setResults(null)
    Promise.all([
      getCatalog('movie', 'top_movies', { search: q }),
      getCatalog('series', 'top_series', { search: q }),
    ])
      .then(([movies, series]) => setResults([...movies, ...series]))
      .catch(() => setResults([]))
  }, [q])

  return (
    <div className="max-w-6xl mx-auto py-8 px-4 sm:px-0">
      <h1 className="font-display text-2xl font-semibold mb-6">
        {q ? (
          <>
            Results for <span className="text-reel-gold">“{q}”</span>
          </>
        ) : (
          'Search'
        )}
      </h1>

      {results === null ? (
        <div className="grid grid-cols-3 sm:grid-cols-5 md:grid-cols-6 gap-4">
          {Array.from({ length: 12 }).map((_, i) => (
            <div key={i} className="aspect-[2/3] rounded-md bg-reel-surface2 animate-pulse" />
          ))}
        </div>
      ) : results.length === 0 ? (
        <p className="text-reel-muted">Kuch nahi mila. Doosra naam try karo.</p>
      ) : (
        <div className="grid grid-cols-3 sm:grid-cols-5 md:grid-cols-6 gap-x-3 gap-y-6">
          {results.map((item) => (
            <MediaCard key={item.id} item={item} />
          ))}
        </div>
      )}
    </div>
  )
}
