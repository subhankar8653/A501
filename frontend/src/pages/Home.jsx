import { useEffect, useMemo, useRef, useState } from 'react'
import { Navigate } from 'react-router-dom'
import {
  getManifest,
  groupCatalogsByTab,
  loadNewToYou,
  createTabLoadState,
  loadTabPage,
  INITIAL_PAGES_PER_CATALOG,
  LOAD_MORE_PAGES_PER_CATALOG,
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
  const [active, setActive] = useState('all')
  const [groupsByTab, setGroupsByTab] = useState(cacheRef.current?.groupsByTab || {})
  const [loadingTab, setLoadingTab] = useState(false)
  // PERF FIX (user report: "All" tab hang on open — too much loaded at
  // once): each language-grouped tab now loads in small incremental
  // batches instead of everything at once. `tabExhausted` tracks whether
  // a tab has nothing more to load (so the scroll-loader can stop
  // trying); `loadingMore` drives the small spinner at the bottom of the
  // list while a scroll-triggered batch is in flight. `loadStatesRef`
  // holds each tab's own fetch-progress tracker (see createTabLoadState
  // in api.js) — a ref because it's bookkeeping the scroll-loader reads
  // and mutates, not something that should trigger a re-render itself.
  const [tabExhausted, setTabExhausted] = useState({})
  const [loadingMore, setLoadingMore] = useState(false)
  const loadStatesRef = useRef({})
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
  // "New to You" is sorted by upload recency (already capped at 60 items
  // total, so it doesn't need incremental loading) — everything else is
  // grouped by language and now loads incrementally via loadTabPage(),
  // starting from a fresh loadState each time this effect (re)runs. A
  // fresh start on every background refresh is intentional: it keeps
  // that first paint light, and any extra depth the user had scrolled to
  // gets rebuilt on demand as they scroll again.
  useEffect(() => {
    if (!tabCatalogs) return
    const hasCache = !!groupsByTab[active]
    let cancelled = false

    if (active === 'new') {
      const apply = (groups) => {
        if (!cancelled) setGroupsByTab((prev) => ({ ...prev, [active]: groups }))
      }
      if (hasCache) {
        loadNewToYou(tabCatalogs[active]).then(apply).catch(() => {})
      } else {
        setLoadingTab(true)
        loadNewToYou(tabCatalogs[active])
          .then(apply)
          .finally(() => {
            if (!cancelled) setLoadingTab(false)
          })
      }
      return () => {
        cancelled = true
      }
    }

    const loadState = createTabLoadState()
    loadStatesRef.current[active] = loadState
    const run = () => loadTabPage(tabCatalogs[active], loadState, INITIAL_PAGES_PER_CATALOG)
    const apply = ({ groups, exhausted }) => {
      if (cancelled) return
      setGroupsByTab((prev) => ({ ...prev, [active]: groups }))
      setTabExhausted((prev) => ({ ...prev, [active]: exhausted }))
    }

    if (hasCache) {
      run()
        .then(apply)
        .catch(() => {
          // keep showing the cached groups — a failed silent refresh
          // shouldn't disturb what's already on screen
        })
    } else {
      setLoadingTab(true)
      run()
        .then(apply)
        .finally(() => {
          if (!cancelled) setLoadingTab(false)
        })
    }
    return () => {
      cancelled = true
    }
  }, [active, tabCatalogs])

  // Scroll-triggered "load more" for the active language-grouped tab —
  // pulls the next small batch of pages per catalog and merges it into
  // whatever's already on screen (see the IntersectionObserver sentinel
  // near the bottom of the list below).
  async function loadMoreActiveTab() {
    if (active === 'new') return
    const loadState = loadStatesRef.current[active]
    if (!loadState || loadingMore || tabExhausted[active]) return
    setLoadingMore(true)
    try {
      const { groups, exhausted } = await loadTabPage(
        tabCatalogs[active],
        loadState,
        LOAD_MORE_PAGES_PER_CATALOG
      )
      setGroupsByTab((prev) => ({ ...prev, [active]: groups }))
      setTabExhausted((prev) => ({ ...prev, [active]: exhausted }))
    } catch {
      // a failed batch shouldn't break scrolling — the next scroll near
      // the bottom will just try again
    } finally {
      setLoadingMore(false)
    }
  }

  // Sentinel div near the bottom of the list — once it's on/near screen,
  // load the next batch. rootMargin gives it a head start so more content
  // is usually ready just before the user actually reaches the bottom.
  const sentinelRef = useRef(null)
  useEffect(() => {
    const el = sentinelRef.current
    if (!el) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) loadMoreActiveTab()
      },
      { rootMargin: '600px' }
    )
    observer.observe(el)
    return () => observer.disconnect()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, tabCatalogs, groupsByTab, tabExhausted, loadingMore])

  // Manual pull-to-refresh: re-fetches the manifest + the active tab and
  // overwrites whatever was cached for them. Nothing else is touched, so
  // other tabs keep their own cache until you pull-to-refresh there too.
  async function forceRefresh() {
    setPullRefreshing(true)
    try {
      const manifest = await getManifest()
      const nextTabCatalogs = groupCatalogsByTab(manifest.catalogs)
      setTabCatalogs(nextTabCatalogs)
      if (active === 'new') {
        const groups = await loadNewToYou(nextTabCatalogs[active])
        setGroupsByTab((prev) => ({ ...prev, [active]: groups }))
      } else {
        const loadState = createTabLoadState()
        loadStatesRef.current[active] = loadState
        const { groups, exhausted } = await loadTabPage(
          nextTabCatalogs[active],
          loadState,
          INITIAL_PAGES_PER_CATALOG
        )
        setGroupsByTab((prev) => ({ ...prev, [active]: groups }))
        setTabExhausted((prev) => ({ ...prev, [active]: exhausted }))
      }
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
          regardless of which hero slide is showing.
          REDESIGN (user ask: professional/modern polish, inspired by the
          reference YouTube screenshot's tab row): swapped the old full-pill
          gold-gradient-with-glow treatment for calmer rectangular chips
          (.chip/.chip-active — see index.css) — a single confident solid
          fill on the active chip instead of a glowing pill among several. */}
      <div className="max-w-6xl mx-auto px-4 sm:px-6 pt-3 pb-3 relative z-10">
        <div className="flex gap-2 overflow-x-auto no-scrollbar py-1">
          {HOME_TABS.map((tab) => (
            <button
              key={tab.key}
              onClick={() => setActive(tab.key)}
              disabled={!tabCatalogs}
              className={`shrink-0 px-3.5 py-1.5 rounded-lg text-[13px] font-medium tracking-wide transition-colors duration-200 active:scale-95 disabled:opacity-50 ${
                active === tab.key ? 'chip-active animate-tab-pop-in' : 'chip'
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
            <>
              {groups.map(({ language, items }) => (
                <LanguageRail key={language} language={language} items={items} />
              ))}
              {/* Infinite-scroll trigger for this tab — invisible, just marks
                  "getting close to the bottom" so the next small batch loads
                  before the user actually hits the end of the list. Hidden
                  once the tab has nothing left to load. */}
              {active !== 'new' && !tabExhausted[active] && (
                <div ref={sentinelRef} className="h-1" />
              )}
              {loadingMore && (
                <div className="flex justify-center py-4">
                  <div className="w-5 h-5 rounded-full border-2 border-reel-gold border-t-transparent animate-spin" />
                </div>
              )}
            </>
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
