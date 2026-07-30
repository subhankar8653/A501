import { useState, useEffect } from 'react'
import { getManifest, groupCatalogsByTab, loadTabByLanguage } from '../api'
import HeroBanner from '../components/HeroBanner'
import ContentRail from '../components/ContentRail'
import SkeletonRail from '../components/SkeletonRail'

export default function Home() {
  const [manifest, setManifest] = useState(null)
  const [tabData, setTabData] = useState({})
  const [activeTab, setActiveTab] = useState('movie')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false

    async function init() {
      try {
        const m = await getManifest()
        if (cancelled) return
        setManifest(m)

        const grouped = groupCatalogsByTab(m.catalogs || [])
        const tabs = ['movie', 'series', 'anime', 'kdrama', 'shortdrama']

        const loaded = {}
        for (const key of tabs) {
          if (grouped[key]?.length) {
            loaded[key] = await loadTabByLanguage(grouped[key])
          }
        }
        if (!cancelled) setTabData(loaded)
      } catch (err) {
        if (!cancelled) setError(err.message)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    init()
    return () => { cancelled = true }
  }, [])

  const tabs = [
    { key: 'movie', label: 'Movies', icon: '🎬' },
    { key: 'series', label: 'Web Series', icon: '📺' },
    { key: 'anime', label: 'Anime', icon: '✦' },
    { key: 'kdrama', label: 'K-Drama', icon: '💫' },
    { key: 'shortdrama', label: 'Short Drama', icon: '⚡' },
  ]

  const currentData = tabData[activeTab] || []

  // Get featured items for hero
  const allItems = Object.values(tabData).flatMap(langGroups => 
    langGroups.flatMap(g => g.items)
  )
  const featured = allItems.filter(i => i.background || i.poster).slice(0, 5)

  if (error) {
    return (
      <div className="flex items-center justify-center min-h-[60vh] text-red-400">
        <p>Failed to load: {error}</p>
      </div>
    )
  }

  return (
    <div className="animate-fade-in">
      {/* Hero Banner */}
      {!loading && featured.length > 0 && <HeroBanner items={featured} />}

      {/* Tab Navigation */}
      <div className="sticky top-16 z-40 bg-netflix-black/95 nav-blur border-b border-white/5">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex items-center gap-1 overflow-x-auto no-scrollbar py-3">
            {tabs.map(tab => (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key)}
                className={`flex items-center gap-2 px-4 py-2 rounded-full text-sm font-medium whitespace-nowrap transition-all ${
                  activeTab === tab.key
                    ? 'bg-white text-netflix-black'
                    : 'text-netflix-lightgray hover:text-white hover:bg-white/10'
                }`}
              >
                <span>{tab.icon}</span>
                {tab.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="pb-12">
        {loading ? (
          <>
            <SkeletonRail count={6} />
            <SkeletonRail count={6} />
            <SkeletonRail count={6} />
          </>
        ) : currentData.length === 0 ? (
          <div className="flex items-center justify-center min-h-[40vh] text-netflix-gray">
            <p>No content found in this category</p>
          </div>
        ) : (
          currentData.map((group, idx) => (
            <ContentRail
              key={group.language}
              title={group.language === 'Other' ? 'Mixed Content' : group.language}
              items={group.items}
              icon={idx === 0 ? '🔥' : idx === 1 ? '⭐' : '📌'}
            />
          ))
        )}
      </div>
    </div>
  )
}
