import { useEffect, useState } from 'react'
import { getManifest, getCatalog, groupCatalogs } from '../api'
import Rail from '../components/Rail'

export default function Home() {
  const [rails, setRails] = useState(null)
  const [data, setData] = useState({})
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false

    async function load() {
      try {
        const manifest = await getManifest()
        const grouped = groupCatalogs(manifest.catalogs)
        if (cancelled) return
        setRails(grouped)

        const allCatalogs = [
          ...grouped.anime,
          ...grouped.kdrama,
          ...grouped.movie,
          ...grouped.series,
          ...grouped.custom,
        ]

        for (const cat of allCatalogs) {
          getCatalog(cat.type, cat.id)
            .then((metas) => {
              if (cancelled) return
              setData((prev) => ({ ...prev, [`${cat.type}:${cat.id}`]: metas }))
            })
            .catch(() => {})
        }
      } catch {
        if (!cancelled) setError('Library load nahi ho payi. Setup check karo.')
      }
    }

    load()
    return () => {
      cancelled = true
    }
  }, [])

  if (error) {
    return <p className="text-center text-reel-rust mt-10">{error}</p>
  }

  if (!rails) {
    return (
      <div className="max-w-6xl mx-auto px-0 sm:px-6 py-8">
        <Rail title="Loading…" loading items={[]} />
      </div>
    )
  }

  const sections = [
    ...rails.anime.map((c) => ({ ...c, sectionTitle: c.name || 'Anime' })),
    ...rails.kdrama.map((c) => ({ ...c, sectionTitle: c.name || 'K-Drama' })),
    ...rails.movie.map((c) => ({ ...c, sectionTitle: `Movies · ${c.name}` })),
    ...rails.series.map((c) => ({ ...c, sectionTitle: `Series · ${c.name}` })),
    ...rails.custom.map((c) => ({ ...c, sectionTitle: c.name })),
  ]

  const hasAnyData = Object.keys(data).length > 0

  return (
    <div className="max-w-6xl mx-auto py-8">
      {sections.map((cat) => (
        <Rail
          key={`${cat.type}:${cat.id}`}
          title={cat.sectionTitle}
          items={data[`${cat.type}:${cat.id}`]}
          loading={!data[`${cat.type}:${cat.id}`]}
        />
      ))}
      {!hasAnyData && sections.length === 0 ? (
        <p className="text-center text-reel-muted mt-10 px-4">
          Catalog abhi khali hai — apne AUTH channel mein files forward karo, catalogs yahan aa jayenge.
        </p>
      ) : null}
    </div>
  )
}
