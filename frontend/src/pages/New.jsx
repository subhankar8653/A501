import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { getManifest, getCatalog, groupCatalogsByTab, loadNewToYou, isVerified } from '../api'
import { useOnlineStatus } from '../lib/connectivity'
import MediaCard from '../components/MediaCard'
import VerifyGate from '../components/VerifyGate'
import { useLanguage } from '../i18n/LanguageContext'

// FEATURE (user ask: "footer mein New tab add karo — jo main recently
// database mein add karunga vah sab udhar dikhega"): this is the new
// bottom-nav "New" tab. It deliberately does NOT reimplement "recently
// added" from scratch — it reuses the exact same data path already built
// for Home's own "New to You" tab-pill (see groupCatalogsByTab() +
// loadNewToYou() in api.js): every catalog (movies, series, anime,
// k-drama, short drama) gets merged into one pool and sorted by each
// title's real `addedAt` timestamp (set server-side the moment an admin
// adds it — see convert_to_stremio_meta in stremio_routes.py), newest
// first. So whatever gets added to the database shows up here
// automatically, same as it already did inside Home — just promoted to
// its own dedicated tab instead of being buried under Home's tab-pills.
export default function HukaTubePage() {
  const isOnline = useOnlineStatus()
  const { t } = useLanguage()
  if (!isOnline) return <Navigate to="/downloads" replace />
  if (!isVerified()) {
    return <VerifyGate message={t('home_verify_message')} />
  }
  return <NewContent t={t} />
}

// HukaTube tab: anime first (biggest section), then movies, k-drama, series and
// short drama. Each section = newest uploads of that category, with a
// "Load more" button that keeps going until the category is exhausted.
const SECTIONS = [
  { key: 'anime', title: 'Anime', first: 18, step: 12 },
  { key: 'movie', title: 'Movies', first: 9, step: 9 },
  { key: 'kdrama', title: 'K-Drama', first: 9, step: 9 },
  { key: 'series', title: 'Series', first: 6, step: 9 },
  { key: 'shortdrama', title: 'Short Drama', first: 6, step: 9 },
]
const PAGE = 15 // backend catalog page size
const PAGES_ALREADY = 3 // loadNewToYou() already scanned pages 0-2 of every catalog

function NewContent({ t }) {
  const [tabs, setTabs] = useState(null) // catalogs per tab
  const [sec, setSec] = useState(null) // { key: { items, shown, page, more, busy } }
  const [failed, setFailed] = useState(false)
  const [retryKey, setRetryKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    setSec(null)
    setFailed(false)
    getManifest()
      .then(async (manifest) => {
        const g = groupCatalogsByTab(manifest.catalogs)
        const out = {}
        await Promise.all(
          SECTIONS.map(async (s) => {
            try {
              const groups = await loadNewToYou(g[s.key] || [])
              const items = groups[0]?.items || []
              out[s.key] = { items, shown: s.first, page: PAGES_ALREADY, more: (g[s.key] || []).length > 0, busy: false }
            } catch {
              out[s.key] = { items: [], shown: s.first, page: PAGES_ALREADY, more: false, busy: false }
            }
          })
        )
        return [g, out]
      })
      .then(([g, out]) => {
        if (cancelled) return
        setTabs(g)
        setSec(out)
      })
      .catch(() => {
        if (!cancelled) {
          setSec({})
          setFailed(true)
        }
      })
    return () => {
      cancelled = true
    }
  }, [retryKey])

  function patch(key, p) {
    setSec((prev) => ({ ...prev, [key]: { ...prev[key], ...p } }))
  }

  async function loadMore(s) {
    const cur = sec[s.key]
    if (cur.busy) return
    // 1) still have unshown items in memory -> just reveal them
    if (cur.shown < cur.items.length) {
      patch(s.key, { shown: cur.shown + s.step })
      return
    }
    // 2) fetch the next page of every catalog in this category
    patch(s.key, { busy: true })
    const known = new Set(cur.items.map((i) => i.id))
    const fresh = []
    let anyFull = false
    await Promise.all(
      ((tabs && tabs[s.key]) || []).map(async (cat) => {
        try {
          const metas = await getCatalog(cat.type, cat.id, { skip: cur.page * PAGE })
          if (metas.length >= PAGE) anyFull = true
          for (const m of metas) {
            if (m && m.id && !known.has(m.id)) {
              known.add(m.id)
              fresh.push(m)
            }
          }
        } catch {
          /* skip this catalog */
        }
      })
    )
    fresh.sort((a, b) => new Date(b.addedAt || 0) - new Date(a.addedAt || 0))
    patch(s.key, {
      items: [...cur.items, ...fresh],
      shown: cur.shown + s.step,
      page: cur.page + 1,
      more: anyFull,
      busy: false,
    })
  }

  const nothing = sec && SECTIONS.every((s) => !(sec[s.key]?.items || []).length)

  return (
    <div className="max-w-6xl mx-auto py-5 sm:py-6 px-4 sm:px-6">
      <h1 className="font-display text-2xl font-semibold mb-6">{t('nav_new')}</h1>

      {sec === null ? (
        <div className="grid grid-cols-3 sm:grid-cols-5 md:grid-cols-6 gap-4">
          {Array.from({ length: 12 }).map((_, i) => (
            <div key={i} className="aspect-[2/3] rounded-md bg-reel-surface2 animate-pulse" />
          ))}
        </div>
      ) : failed || nothing ? (
        <div>
          <p className="text-reel-rust mb-3">{failed ? t('home_load_failed') : t('home_no_content')}</p>
          <button
            onClick={() => setRetryKey((k) => k + 1)}
            className="text-sm px-4 py-2 rounded-full bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-95 transition"
          >
            {t('retry')}
          </button>
        </div>
      ) : (
        SECTIONS.map((s) => {
          const st = sec[s.key]
          if (!st || !st.items.length) return null
          const visible = st.items.slice(0, st.shown)
          const canMore = st.shown < st.items.length || st.more
          return (
            <section key={s.key} className="mb-8">
              <h2 className={`font-display font-semibold mb-3 ${s.key === 'anime' ? 'text-xl text-reel-gold' : 'text-lg'}`}>
                {s.title}
              </h2>
              <div className="grid grid-cols-3 sm:grid-cols-5 md:grid-cols-6 gap-x-3 gap-y-6">
                {visible.map((item, i) => (
                  <MediaCard key={item.id} item={item} index={i} />
                ))}
              </div>
              {canMore ? (
                <div className="flex justify-center mt-5">
                  <button
                    onClick={() => loadMore(s)}
                    disabled={st.busy}
                    className="px-6 py-2.5 rounded-full text-sm font-semibold bg-reel-surface2 text-reel-gold border border-reel-gold/40 active:scale-95 transition disabled:opacity-60"
                  >
                    {st.busy ? 'Loading…' : `Load more ${s.title}`}
                  </button>
                </div>
              ) : null}
            </section>
          )
        })
      )}
    </div>
  )
}
