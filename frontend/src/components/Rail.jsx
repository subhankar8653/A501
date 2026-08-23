import { memo } from 'react'
import MediaCard from './MediaCard'

// PERF FIX: memo() taaki Home tab-switch ya language-change jaisi cheezein
// jo sirf EK rail ka data badalti hain, baaki saare unrelated rails ko
// dobara render na karein.
function Rail({ title, items, loading }) {
  if (!loading && (!items || items.length === 0)) return null

  return (
    <section className="mb-8">
      <h2 className="font-display text-xl font-semibold mb-3 px-4 sm:px-0">{title}</h2>
      <div className="flex gap-3 overflow-x-auto no-scrollbar px-4 sm:px-0 pb-1">
        {loading
          ? Array.from({ length: 6 }).map((_, i) => (
              <div
                key={i}
                className="shrink-0 w-[calc(33.333vw-19px)] sm:w-[180px] aspect-[2/3] rounded-lg bg-reel-surface2 animate-pulse"
              />
            ))
          : items.map((item, i) => <MediaCard key={item.id} item={item} index={i} />)}
      </div>
    </section>
  )
}

export default memo(Rail)
