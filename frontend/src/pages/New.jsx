import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { getManifest, groupCatalogsByTab, loadNewToYou, isVerified } from '../api'
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
export default function New() {
  const isOnline = useOnlineStatus()
  const { t } = useLanguage()
  if (!isOnline) return <Navigate to="/downloads" replace />
  if (!isVerified()) {
    return <VerifyGate message={t('home_verify_message')} />
  }
  return <NewContent t={t} />
}

function NewContent({ t }) {
  const [items, setItems] = useState(null) // null = loading
  const [failed, setFailed] = useState(false)
  const [retryKey, setRetryKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    setItems(null)
    setFailed(false)
    getManifest()
      .then((manifest) => {
        const tabs = groupCatalogsByTab(manifest.catalogs)
        return loadNewToYou(tabs.new)
      })
      .then((groups) => {
        if (!cancelled) setItems(groups[0]?.items || [])
      })
      .catch(() => {
        if (!cancelled) {
          setItems([])
          setFailed(true)
        }
      })
    return () => {
      cancelled = true
    }
  }, [retryKey])

  return (
    <div className="max-w-6xl mx-auto py-5 sm:py-6 px-4 sm:px-6">
      <h1 className="font-display text-2xl font-semibold mb-6">{t('nav_new')}</h1>

      {items === null ? (
        <div className="grid grid-cols-3 sm:grid-cols-5 md:grid-cols-6 gap-4">
          {Array.from({ length: 12 }).map((_, i) => (
            <div key={i} className="aspect-[2/3] rounded-md bg-reel-surface2 animate-pulse" />
          ))}
        </div>
      ) : failed ? (
        <div>
          <p className="text-reel-rust mb-3">{t('home_load_failed')}</p>
          <button
            onClick={() => setRetryKey((k) => k + 1)}
            className="text-sm px-4 py-2 rounded-full bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-95 transition"
          >
            {t('retry')}
          </button>
        </div>
      ) : items.length === 0 ? (
        <p className="text-center text-reel-muted mt-10 px-4">{t('home_no_content')}</p>
      ) : (
        <div className="grid grid-cols-3 sm:grid-cols-5 md:grid-cols-6 gap-x-3 gap-y-6">
          {items.map((item, i) => (
            <MediaCard key={item.id} item={item} index={i} />
          ))}
        </div>
      )}
    </div>
  )
}
