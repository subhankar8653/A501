import { useState, useEffect, useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import { getManifest, groupCatalogsByTab, getCatalog } from '../api'
import MediaCard from '../components/MediaCard'
import { Search, Filter, X } from 'lucide-react'

export default function SearchPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const initialQuery = searchParams.get('q') || ''

  const [query, setQuery] = useState(initialQuery)
  const [results, setResults] = useState([])
  const [loading, setLoading] = useState(false)
  const [filters, setFilters] = useState({ type: 'all', year: '' })
  const [showFilters, setShowFilters] = useState(false)

  const performSearch = useCallback(async (searchQuery) => {
    if (!searchQuery.trim()) {
      setResults([])
      return
    }

    setLoading(true)
    try {
      const manifest = await getManifest()
      const grouped = groupCatalogsByTab(manifest.catalogs || [])
      const allCatalogs = Object.values(grouped).flat()

      const allItems = new Map()
      await Promise.all(
        allCatalogs.map(async (cat) => {
          try {
            const items = await getCatalog(cat.type, cat.id, { search: searchQuery })
            items.forEach(item => {
              if (!allItems.has(item.id)) allItems.set(item.id, item)
            })
          } catch {
            // ignore individual catalog failures
          }
        })
      )

      let filtered = [...allItems.values()]
      if (filters.type !== 'all') {
        filtered = filtered.filter(i => i.type === filters.type)
      }
      if (filters.year) {
        filtered = filtered.filter(i => String(i.year).includes(filters.year))
      }

      setResults(filtered)
    } catch (err) {
      console.error('Search failed:', err)
    } finally {
      setLoading(false)
    }
  }, [filters])

  useEffect(() => {
    const timeout = setTimeout(() => {
      performSearch(query)
      if (query) setSearchParams({ q: query })
    }, 400)
    return () => clearTimeout(timeout)
  }, [query, performSearch, setSearchParams])

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 animate-fade-in">
      {/* Search Header */}
      <div className="mb-8">
        <div className="relative max-w-2xl">
          <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-netflix-gray" />
          <input
            type="text"
            autoFocus
            placeholder="Search movies, series, anime..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="w-full bg-netflix-dark border border-white/10 rounded-xl pl-12 pr-12 py-4 text-lg text-white placeholder-netflix-gray focus:outline-none focus:border-netflix-red/60 focus:ring-1 focus:ring-netflix-red/60 transition-all"
          />
          {query && (
            <button
              onClick={() => setQuery('')}
              className="absolute right-4 top-1/2 -translate-y-1/2 p-1 hover:bg-white/10 rounded-full transition-colors"
            >
              <X className="w-5 h-5 text-netflix-gray" />
            </button>
          )}
        </div>

        {/* Filters */}
        <div className="flex items-center gap-3 mt-4">
          <button
            onClick={() => setShowFilters(!showFilters)}
            className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              showFilters ? 'bg-white text-netflix-black' : 'bg-white/10 text-white hover:bg-white/20'
            }`}
          >
            <Filter className="w-4 h-4" />
            Filters
          </button>

          {showFilters && (
            <div className="flex items-center gap-2 animate-fade-in">
              <select
                value={filters.type}
                onChange={(e) => setFilters(f => ({ ...f, type: e.target.value }))}
                className="bg-netflix-dark border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-netflix-red/60"
              >
                <option value="all">All Types</option>
                <option value="movie">Movies</option>
                <option value="series">Series</option>
              </select>
              <input
                type="text"
                placeholder="Year"
                value={filters.year}
                onChange={(e) => setFilters(f => ({ ...f, year: e.target.value }))}
                className="w-24 bg-netflix-dark border border-white/10 rounded-lg px-3 py-2 text-sm text-white placeholder-netflix-gray focus:outline-none focus:border-netflix-red/60"
              />
            </div>
          )}
        </div>
      </div>

      {/* Results */}
      {loading ? (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4">
          {Array.from({ length: 12 }).map((_, i) => (
            <div key={i} className="aspect-[2/3] shimmer-bg rounded-lg" />
          ))}
        </div>
      ) : query && results.length === 0 ? (
        <div className="text-center py-20">
          <p className="text-xl text-netflix-lightgray mb-2">No results found</p>
          <p className="text-sm text-netflix-gray">Try a different search term</p>
        </div>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4">
          {results.map((item, idx) => (
            <MediaCard key={item.id} item={item} index={idx} />
          ))}
        </div>
      )}
    </div>
  )
}
