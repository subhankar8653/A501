import { useEffect, useMemo, useState } from 'react'
import { getManifest, groupCatalogsByTab, loadTabByLanguage, HOME_TABS } from '../api'
import LanguageRail from '../components/LanguageRail'
import Rail from '../components/Rail'
import HomeHero from '../components/HomeHero'

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

  const groups = tabCatalogs ? groupsByTab[active] : null
  const hasCatalogsForTab = tabCatalogs ? (tabCatalogs[active] || []).length > 0 : true

  // Spotlight picks for the hero banner carousel — top item from each
  // language group in the active tab (up to 5), so the hero auto-rotates
  // through a handful of picks instead of showing just one static item.
  const heroItems = useMemo(() => {
    const picks = (groups || [])
      .filter((g) => g.items && g.items.length)
      .map((g) => g.items[0])
    return picks.slice(0, 5)
  }, [groups])
  const heroLoading = !tabCatalogs || (loadingTab && !groups)

  return (
    <div className="pb-4">
      {/* Category pills sit right under the header, above the hero, so
          they're the first thing you see and always in the same spot
          regardless of which hero slide is showing. */}
      <div className="max-w-6xl mx-auto px-4 sm:px-6 pt-4 pb-3 relative z-10">
        <div className="flex gap-2.5 overflow-x-auto no-scrollbar py-1">
          {HOME_TABS.map((tab) => (
            <button
              key={tab.key}
              onClick={() => setActive(tab.key)}
              disabled={!tabCatalogs}
              className={`shrink-0 px-5 py-2 rounded-full text-sm font-semibold tracking-wide transition-all duration-200 active:scale-95 disabled:opacity-50 ${
                active === tab.key
                  ? 'animate-tab-pop-in bg-gradient-to-b from-[#F3C067] to-reel-gold text-reel-bg shadow-[0_6px_20px_-4px_rgba(232,163,61,0.5)]'
                  : 'bg-reel-surface/90 backdrop-blur-sm text-reel-muted ring-1 ring-white/[0.08] hover:ring-reel-gold/40 hover:text-reel-ink'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      <HomeHero items={heroItems} loading={heroLoading} />

      <div className="max-w-6xl mx-auto px-0 mt-6">
        <div key={active} className="page-fade-in">
          {!tabCatalogs ? (
            <Rail title="Loading…" loading items={[]} />
          ) : !hasCatalogsForTab ? (
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
    </div>
  )
}
