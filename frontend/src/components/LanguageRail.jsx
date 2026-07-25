import MediaCard from './MediaCard'

// Two-row rail for one language within a content-type tab. Splitting
// into two rows (instead of one long scroll) surfaces more titles for
// languages that have a lot of content.
export default function LanguageRail({ language, items }) {
  if (!items || items.length === 0) return null

  const mid = Math.ceil(items.length / 2)
  const row1 = items.slice(0, mid)
  const row2 = items.slice(mid)

  return (
    <section className="mb-8">
      <h2 className="font-display text-xl font-semibold mb-3 px-4 sm:px-0">{language}</h2>
      <div className="flex gap-3 overflow-x-auto no-scrollbar px-4 sm:px-0 pb-1">
        {row1.map((item) => (
          <MediaCard key={item.id} item={item} />
        ))}
      </div>
      {row2.length > 0 ? (
        <div className="flex gap-3 overflow-x-auto no-scrollbar px-4 sm:px-0 pb-1 mt-3">
          {row2.map((item) => (
            <MediaCard key={`${item.id}-row2`} item={item} />
          ))}
        </div>
      ) : null}
    </section>
  )
}
