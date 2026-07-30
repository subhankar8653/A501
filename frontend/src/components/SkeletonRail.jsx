import SkeletonCard from './SkeletonCard'

export default function SkeletonRail({ count = 6 }) {
  return (
    <div className="py-6">
      <div className="px-4 sm:px-6 lg:px-8 mb-4">
        <div className="h-6 w-48 shimmer-bg rounded" />
      </div>
      <div className="flex gap-3 px-4 sm:px-6 lg:px-8 overflow-hidden">
        {Array.from({ length: count }).map((_, i) => (
          <SkeletonCard key={i} />
        ))}
      </div>
    </div>
  )
}
