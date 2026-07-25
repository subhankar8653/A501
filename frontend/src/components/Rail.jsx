import MediaCard from './MediaCard'

export default function Rail({ title, items, loading }) {
  if (!loading && (!items || items.length === 0)) return null

  return (
    <section className="mb-8">
      <h2 className="font-display text-xl font-semibold mb-3 px-4 sm:px-0">{title}</h2>
      <div className="flex gap-3 overflow-x-auto no-scrollbar px-4 sm:px-0 pb-1">
        {loading
          ? Array.from({ length: 6 }).map((_, i) => (
              <div
                key={i}
                className="shrink-0 w-[140px] sm:w-[160px] aspect-[2/3] rounded-md bg-reel-surface2 animate-pulse"
              />
            ))
          : items.map((item) => <MediaCard key={item.id} item={item} />)}
      </div>
    </section>
  )
}
