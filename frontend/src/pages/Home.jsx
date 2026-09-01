import { useEffect, useMemo, useRef, useState } from 'react'
import { Navigate } from 'react-router-dom'
import {
  getManifest,
  groupCatalogsByTab,
  loadTabByLanguage,
  HOME_TABS,
  loadHomeCache,
  saveHomeCache,
  isVerified,
  getContinueWatching,
  removeWatchProgress,
} from '../api'
import { useOnlineStatus } from '../lib/connectivity'
import LanguageRail from '../components/LanguageRail'
import Rail from '../components/Rail'
import HomeHero from '../components/HomeHero'
import ContinueWatchingRail from '../components/ContinueWatchingRail'
import VerifyGate from '../components/VerifyGate'
import { useLanguage } from '../i18n/LanguageContext'

export default function Home() {
  // FEATURE (user ask: "jab offline ho toh sidha download page mein open
  // hona chahiye, aur home page pe lock ho jana chahiye"): Home needs live
  // data (manifest + catalogs), so it's useless offline — before this it
  // still tried to render, hit a failed fetch, and (combined with other
  // offline-render edge cases) could end up as a blank/black screen instead
  // of something useful. Now Home simply never renders while offline — it
  // bounces straight to Downloads (the one tab that's fully usable offline),
  // and BottomNav shows the "internet on karo" message + a locked icon on
  // the Home tab so it's clear *why*.
  const isOnline = useOnlineStatus()
  if (!isOnline) return <Navigate to="/downloads" replace />
  const { t } = useLanguage()

  // FEATURE (user ask: "app bina login khule, par Home library-locked rahe
  // jab tak Profile se verify na ho"): pehle isVerified() check kiye bina
  // hi catalog fetch shuru ho jaata (aur token na hone par fail ho jaata).
  // Ab verify na hone par catalog fetch try hi nahi hota — seedha locked
  // popup card dikha dete hain.
  if (!isVerified()) {
    return <VerifyGate message={t('home_verify_message')} />
  }

  return <HomeContent t={t} />
}

const PULL_THRESHOLD = 70 // px of drag before letting go triggers a refresh
const PULL_MAX = 96 // visual cap so the indicator doesn't drag forever

function HomeContent({ t }) {
  // Hydrate straight from the persisted cache (if any) so returning to Home
  // — from Saved/Downloads/Profile, or after fully closing and reopening the
  // app — shows the last-loaded content INSTANTLY, no "Loading…" flash.
  const cacheRef = useRef(loadHomeCache())
  const [tabCatalogs, setTabCatalogs] = useState(cacheRef.current?.tabCatalogs || null)
  const [active, setActive] = useState('anime')
  const [groupsByTab, setGroupsByTab] = useState(cacheRef.current?.groupsByTab || {})
  const [loadingTab, setLoadingTab] = useState(false)
  const [error, setError] = useState('')
  const [retryKey, setRetryKey] = useState(0)
  // Only ever shown for a manual pull-to-refresh — the automatic
  // background catch-up (see effects below) stays silent on purpose.
  const [pullRefreshing, setPullRefreshing] = useState(false)
  // FEATURE (user ask: "Watch history / Continue Watching")
  const [continueWatching, setContinueWatching] = useState(null)

  useEffect(() => {
    let cancelled = false
    getContinueWatching()
      .then((items) => {
        if (!cancelled) setContinueWatching(items)
      })
      .catch(() => {
        if (!cancelled) setContinueWatching([])
      })
    return () => {
      cancelled = true
    }
  }, [retryKey])

  function removeContinueWatchingItem(item) {
    setContinueWatching((prev) => (prev || []).filter((it) => it.k !== item.k))
    removeWatchProgress(item.media_id, item.episode_id).catch(() => {})
  }

  // Keep the cache in sync with whatever's on screen, so the NEXT visit
  // (tab switch or app reopen) starts from here. Old entry is simply
  // overwritten — nothing stacks up.
  useEffect(() => {
    if (!tabCatalogs) return
    saveHomeCache({ tabCatalogs, groupsByTab })
  }, [tabCatalogs, groupsByTab])

  // Load the manifest once (and again on Retry). If cached content is
  // already on screen this runs quietly in the background — the user sees
  // nothing change until fresher data is actually ready, at which point it
  // just quietly swaps in (see the tab-loading effect below).
  useEffect(() => {
    let cancelled = false
    const hadCache = !!tabCatalogs
    if (!hadCache) setError('')
    getManifest()
      .then((manifest) => {
        if (cancelled) return
        setTabCatalogs(groupCatalogsByTab(manifest.catalogs))
      })
      .catch(() => {
        if (!cancelled && !hadCache) setError(t('home_load_failed'))
      })
    return () => {
      cancelled = true
    }
  }, [retryKey])

  // Whenever the active tab changes (or the manifest refreshes underneath
  // it), load that tab's content:
  //  - not cached yet -> normal loading spinner, same as before.
  //  - already cached -> cached content stays on screen as-is, and a
  //    silent background fetch replaces it once done (this is also what
  //    picks up brand-new content someone just added on the server,
  //    without the user having to do anything).
  useEffect(() => {
    if (!tabCatalogs) return
    const hasCache = !!groupsByTab[active]

    let cancelled = false
    if (hasCache) {
      loadTabByLanguage(tabCatalogs[active])
        .then((groups) => {
          if (!cancelled) setGroupsByTab((prev) => ({ ...prev, [active]: groups }))
        })
        .catch(() => {
          // keep showing the cached groups — a failed silent refresh
          // shouldn't disturb what's already on screen
        })
    } else {
      setLoadingTab(true)
      loadTabByLanguage(tabCatalogs[active])
        .then((groups) => {
          if (cancelled) return
          setGroupsByTab((prev) => ({ ...prev, [active]: groups }))
        })
        .finally(() => {
          if (!cancelled) setLoadingTab(false)
        })
    }
    return () => {
      cancelled = true
    }
  }, [active, tabCatalogs])

  // Manual pull-to-refresh: re-fetches the manifest + the active tab and
  // overwrites whatever was cached for them. Nothing else is touched, so
  // other tabs keep their own cache until you pull-to-refresh there too.
  async function forceRefresh() {
    setPullRefreshing(true)
    try {
      const manifest = await getManifest()
      const nextTabCatalogs = groupCatalogsByTab(manifest.catalogs)
      setTabCatalogs(nextTabCatalogs)
      const groups = await loadTabByLanguage(nextTabCatalogs[active])
      setGroupsByTab((prev) => ({ ...prev, [active]: groups }))
    } catch {
      // keep whatever was already on screen — a failed refresh shouldn't blank it
    } finally {
      setPullRefreshing(false)
    }
  }

  // --- pull-to-refresh gesture (only arms when already scrolled to top) ---
  const [pullY, setPullY] = useState(0)
  const touchStartY = useRef(null)
  const pulling = useRef(false)

  function onTouchStart(e) {
    if (window.scrollY > 0) return
    touchStartY.current = e.touches[0].clientY
    pulling.current = true
  }
  function onTouchMove(e) {
    if (!pulling.current || touchStartY.current == null) return
    const delta = e.touches[0].clientY - touchStartY.current
    if (delta > 0 && window.scrollY <= 0) {
      setPullY(Math.min(delta * 0.5, PULL_MAX))
    } else {
      pulling.current = false
      setPullY(0)
    }
  }
  function onTouchEnd() {
    if (pulling.current && pullY > PULL_THRESHOLD && !pullRefreshing) {
      forceRefresh()
    }
    pulling.current = false
    touchStartY.current = null
    setPullY(0)
  }

  if (error) {
    return (
      <div className="text-center mt-10 px-4">
        <p className="text-reel-rust mb-3">{error}</p>
        <button
          onClick={() => setRetryKey((k) => k + 1)}
          className="text-sm px-4 py-2 rounded-full bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-95 transition"
        >
          {t('retry')}
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
    <div
      className="pb-4"
      onTouchStart={onTouchStart}
      onTouchMove={onTouchMove}
      onTouchEnd={onTouchEnd}
    >
      {/* Pull-to-refresh indicator — only visible while actively dragging
          down from the top, or while a pull-triggered refresh is in flight. */}
      {(pullY > 0 || pullRefreshing) && (
        <div
          className="flex items-center justify-center overflow-hidden transition-[height] duration-150"
          style={{ height: pullRefreshing ? 40 : pullY }}
        >
          <div
            className={`w-5 h-5 rounded-full border-2 border-reel-gold border-t-transparent ${
              pullRefreshing || pullY > PULL_THRESHOLD ? 'animate-spin' : ''
            }`}
          />
        </div>
      )}

      {/* Category pills sit right under the header, above the hero, so
          they're the first thing you see and always in the same spot
          regardless of which hero slide is showing. */}
      <div className="max-w-6xl mx-auto px-4 sm:px-6 pt-4 pb-3 relative z-10">
        <div className="flex gap-1.5 overflow-x-auto no-scrollbar py-1">
          {HOME_TABS.map((tab) => (
            <button
              key={tab.key}
              onClick={() => setActive(tab.key)}
              disabled={!tabCatalogs}
              className={`shrink-0 px-3.5 py-1.5 rounded-full text-[13px] font-medium tracking-wide transition-all duration-200 active:scale-95 disabled:opacity-50 ${
                active === tab.key
                  ? 'animate-tab-pop-in bg-gradient-to-b from-[color-mix(in_srgb,var(--reel-gold)_65%,white)] to-reel-gold text-reel-bg shadow-[0_6px_20px_-4px_rgba(232,163,61,0.5)]'
                  : 'bg-reel-surface/90 backdrop-blur-sm text-reel-muted ring-1 ring-reel-ink/[0.08] hover:ring-reel-gold/40 hover:text-reel-ink'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      <HomeHero items={heroItems} loading={heroLoading} />

      <div className="max-w-6xl mx-auto px-0">
        <ContinueWatchingRail items={continueWatching} onRemove={removeContinueWatchingItem} />
      </div>

      <div className="max-w-6xl mx-auto px-0 mt-6">
        <div key={active} className="page-fade-in">
          {!tabCatalogs ? (
            <Rail title={t('loading')} loading items={[]} />
          ) : !hasCatalogsForTab ? (
            <p className="text-center text-reel-muted mt-10 px-4">
              {t('home_no_content')}
            </p>
          ) : loadingTab && !groups ? (
            <Rail title={t('loading')} loading items={[]} />
          ) : groups && groups.length > 0 ? (
            groups.map(({ language, items }) => (
              <LanguageRail key={language} language={language} items={items} />
            ))
          ) : (
            <p className="text-center text-reel-muted mt-10 px-4">
              {t('home_no_content')}
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
