import { useEffect, useState } from 'react'
import { getManifest, groupCatalogsByTab, loadTabByLanguage, HOME_TABS } from '../api'
import LanguageRail from '../components/LanguageRail'
import Rail from '../components/Rail'

export default function Home() {
  const [tabCatalogs, setTabCatalogs] = useState(null) // { anime: [...], movie: [...], ... }
  const [active, setActive] = useState('anime')
  const [groupsByTab, setGroupsByTab] = useState({}) // cache: { [tab]: [{language, items}] }
  const [loadingTab, setLoadingTab] = useState(false)
  const [error, setError] = useState('')
  const [retryKey, setRetryKey] = useState(0)

  // Load the manifest once, sort catalogs into tabs.
  useEffect(() => {
    let cancelled = false
    setError('')
    getManifest()
      .then((manifest) => {
        if (cancelled) return
        setTabCatalogs(groupCatalogsByTab(manifest.catalogs))
      })
      .catch(() => {
        if (!cancelled) setError('Library load nahi ho payi. Setup check karo.')
      })
    return () => {
      cancelled = true
    }
  }, [retryKey])

  // Whenever the active tab changes, load (and cache) that tab's content.
  useEffect(() => {
    if (!tabCatalogs) return
    if (groupsByTab[active]) return // already cached

    let cancelled = false
    setLoadingTab(true)
    loadTabByLanguage(tabCatalogs[active])
      .then((groups) => {
        if (cancelled) return
        setGroupsByTab((prev) => ({ ...prev, [active]: groups }))
      })
      .finally(() => {
        if (!cancelled) setLoadingTab(false)
      })
    return () => {
      cancelled = true
    }
  }, [active, tabCatalogs])

  if (error) {
    return (
      <div className="text-center mt-10 px-4">
        <p className="text-reel-rust mb-3">{error}</p>
        <button
          onClick={() => setRetryKey((k) => k + 1)}
          className="text-sm px-4 py-2 rounded-full bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-95 transition"
        >
          Retry
        </button>
      </div>
    )
  }

  if (!tabCatalogs) {
    return (
      <div className="max-w-6xl mx-auto px-0 sm:px-6 py-8">
        <Rail title="Loading…" loading items={[]} />
      </div>
    )
  }

  const groups = groupsByTab[active]
  const hasCatalogsForTab = (tabCatalogs[active] || []).length > 0

  return (
    <div className="max-w-6xl mx-auto py-8">
      <div className="bg-reel-surface/95 border-b border-white/5 mb-6">
        <div className="flex gap-2 overflow-x-auto no-scrollbar px-4 sm:px-0 py-3">
          {HOME_TABS.map((tab) => (
            <button
              key={tab.key}
              onClick={() => setActive(tab.key)}
              className={`shrink-0 px-4 py-1.5 rounded-full text-sm font-medium transition active:scale-95 border ${
                active === tab.key
                  ? 'bg-reel-gold text-black border-reel-gold'
                  : 'bg-reel-surface2 text-reel-ink border-white/5 hover:border-reel-gold/50'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      <div key={active} className="page-fade-in">
        {!hasCatalogsForTab ? (
          <p className="text-center text-reel-muted mt-10 px-4">
            Is category mein abhi content nahi hai.
          </p>
        ) : loadingTab && !groups ? (
          <Rail title="Loading…" loading items={[]} />
        ) : groups && groups.length > 0 ? (
          groups.map(({ language, items }) => (
            <LanguageRail key={language} language={language} items={items} />
          ))
        ) : (
          <p className="text-center text-reel-muted mt-10 px-4">
            Is category mein abhi content nahi hai.
          </p>
        )}
      </div>
    </div>
  )
}
