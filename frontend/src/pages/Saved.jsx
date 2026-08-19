import { Link } from 'react-router-dom'
import { useSavedList, removeSaved } from '../lib/savedStore'

export default function Saved() {
  const list = useSavedList()

  if (!list.length) {
    return (
      <div className="max-w-6xl mx-auto py-16 px-4 text-center">
        <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-reel-surface2 flex items-center justify-center text-reel-muted">
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" /></svg>
        </div>
        <p className="text-reel-ink font-medium">Kuch bhi saved nahi hai</p>
        <p className="text-reel-muted text-sm mt-1">
          Kisi bhi video ke player screen par bookmark icon dabaakar yahan save karo.
        </p>
      </div>
    )
  }

  return (
    <div className="max-w-6xl mx-auto py-6 px-4 sm:px-6">
      <h1 className="font-display text-2xl text-reel-ink mb-4">Saved</h1>
      <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-6 gap-3">
        {list.map((item) => (
          <div key={`${item.type}:${item.id}`} className="relative group">
            <Link to={`/title/${item.type}/${encodeURIComponent(item.id)}`} className="block active:scale-95 transition-transform">
              <div className="relative aspect-[2/3] rounded-md overflow-hidden bg-reel-surface2 ring-1 ring-white/5">
                {item.poster ? (
                  <img src={item.poster} alt={item.name} loading="lazy" className="w-full h-full object-cover" />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-reel-muted text-xs px-2 text-center">
                    {item.name}
                  </div>
                )}
              </div>
              <p className="mt-1.5 text-sm font-medium text-reel-ink line-clamp-1">{item.name}</p>
              <p className="text-xs text-reel-muted">{item.releaseInfo || ''}</p>
            </Link>
            <button
              onClick={() => removeSaved(item.type, item.id)}
              aria-label="Remove from saved"
              className="absolute top-1.5 right-1.5 w-7 h-7 rounded-full bg-black/70 backdrop-blur flex items-center justify-center text-reel-ink active:scale-90 transition"
            >
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></svg>
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}
