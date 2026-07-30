import { useState, useEffect } from 'react'
import { useParams, Link } from 'react-router-dom'
import { getMeta } from '../api'
import { Play, ArrowLeft, Star, Clock, Calendar, Globe, ChevronDown } from 'lucide-react'

export default function Detail() {
  const { type, id } = useParams()
  const [meta, setMeta] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [expandedSeason, setExpandedSeason] = useState(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const data = await getMeta(type, id)
        if (!cancelled) setMeta(data)
      } catch (err) {
        if (!cancelled) setError(err.message)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => { cancelled = true }
  }, [type, id])

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="w-12 h-12 border-4 border-white/10 border-t-netflix-red rounded-full animate-spin" />
      </div>
    )
  }

  if (error || !meta) {
    return (
      <div className="min-h-screen flex items-center justify-center text-red-400">
        <p>Failed to load title details</p>
      </div>
    )
  }

  const isSeries = type === 'series' || meta.type === 'series'
  const seasons = meta.videos?.reduce((acc, video) => {
    const s = video.season || 1
    if (!acc[s]) acc[s] = []
    acc[s].push(video)
    return acc
  }, {}) || {}

  return (
    <div className="min-h-screen bg-netflix-black animate-fade-in">
      {/* Backdrop */}
      <div className="relative aspect-[21/9] max-h-[50vh]">
        <img
          src={meta.background || meta.poster}
          alt={meta.name}
          className="w-full h-full object-cover"
        />
        <div className="absolute inset-0 bg-gradient-to-t from-netflix-black via-netflix-black/50 to-transparent" />
        <div className="absolute inset-0 bg-gradient-to-r from-netflix-black/80 to-transparent" />

        <Link
          to="/"
          className="absolute top-4 left-4 p-2 bg-black/40 hover:bg-black/60 rounded-full backdrop-blur-sm transition-colors"
        >
          <ArrowLeft className="w-5 h-5" />
        </Link>
      </div>

      {/* Content */}
      <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 -mt-32 relative z-10 pb-12">
        {/* Poster + Info */}
        <div className="flex flex-col md:flex-row gap-6 mb-8">
          <img
            src={meta.poster}
            alt={meta.name}
            className="w-32 sm:w-48 rounded-lg shadow-2xl shadow-black/50 flex-shrink-0"
          />
          <div className="flex-1">
            <h1 className="text-3xl sm:text-4xl font-bold text-white mb-3">{meta.name}</h1>

            <div className="flex flex-wrap items-center gap-3 mb-4 text-sm">
              {meta.year && (
                <span className="flex items-center gap-1 text-netflix-lightgray">
                  <Calendar className="w-4 h-4" />
                  {meta.year}
                </span>
              )}
              {meta.runtime && (
                <span className="flex items-center gap-1 text-netflix-lightgray">
                  <Clock className="w-4 h-4" />
                  {meta.runtime}
                </span>
              )}
              {meta.imdbRating && (
                <span className="flex items-center gap-1 text-yellow-400">
                  <Star className="w-4 h-4 fill-yellow-400" />
                  {meta.imdbRating}
                </span>
              )}
              {meta.language && (
                <span className="flex items-center gap-1 text-netflix-lightgray">
                  <Globe className="w-4 h-4" />
                  {meta.language}
                </span>
              )}
            </div>

            <p className="text-netflix-lightgray leading-relaxed mb-6 max-w-2xl">
              {meta.description || 'No description available.'}
            </p>

            <div className="flex items-center gap-3">
              <Link
                to={`/watch/${type}/${encodeURIComponent(id)}`}
                className="flex items-center gap-2 bg-netflix-red hover:bg-red-700 text-white font-semibold px-6 py-3 rounded-lg transition-colors"
              >
                <Play className="w-5 h-5 fill-white" />
                Play Now
              </Link>
            </div>

            {meta.genres?.length > 0 && (
              <div className="flex flex-wrap gap-2 mt-4">
                {meta.genres.map(g => (
                  <span key={g} className="px-3 py-1 bg-white/10 rounded-full text-xs text-netflix-lightgray">
                    {g}
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Episodes / Seasons */}
        {isSeries && Object.keys(seasons).length > 0 && (
          <div className="space-y-4">
            <h2 className="text-xl font-bold text-white mb-4">Episodes</h2>
            {Object.entries(seasons).map(([seasonNum, episodes]) => (
              <div key={seasonNum} className="bg-netflix-dark rounded-xl overflow-hidden">
                <button
                  onClick={() => setExpandedSeason(expandedSeason === seasonNum ? null : seasonNum)}
                  className="w-full flex items-center justify-between p-4 hover:bg-white/5 transition-colors"
                >
                  <span className="font-semibold text-white">Season {seasonNum}</span>
                  <ChevronDown className={`w-5 h-5 text-netflix-gray transition-transform ${
                    expandedSeason === seasonNum ? 'rotate-180' : ''
                  }`} />
                </button>

                {expandedSeason === seasonNum && (
                  <div className="border-t border-white/5 divide-y divide-white/5">
                    {episodes.map((ep, idx) => (
                      <Link
                        key={ep.id || idx}
                        to={`/watch/${type}/${encodeURIComponent(ep.id || id)}`}
                        className="flex items-center gap-4 p-4 hover:bg-white/5 transition-colors group"
                      >
                        <div className="w-12 h-12 rounded-lg bg-white/10 flex items-center justify-center flex-shrink-0 group-hover:bg-netflix-red/20 transition-colors">
                          <Play className="w-5 h-5 text-white opacity-60 group-hover:opacity-100" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <p className="text-sm font-medium text-white truncate">
                            {ep.name || `Episode ${ep.episode || idx + 1}`}
                          </p>
                          {ep.description && (
                            <p className="text-xs text-netflix-gray line-clamp-1 mt-0.5">
                              {ep.description}
                            </p>
                          )}
                        </div>
                        {ep.thumbnail && (
                          <img
                            src={ep.thumbnail}
                            alt=""
                            className="w-20 h-12 rounded object-cover flex-shrink-0"
                          />
                        )}
                      </Link>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
