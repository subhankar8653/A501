import { Link } from 'react-router-dom'
import { useState, memo } from 'react'

// PERF FIX (user ask: "poora app fast/smooth banao"): yeh component Home/
// Search/Saved ke har rail-grid mein dono se best case 30-40 baar render
// hota hai. Do cheezein GPU/CPU par bhaari padti thi:
//  1. `backdrop-blur-sm` rating badge par — har card apna alag blur-layer
//     GPU compositor banwata tha; itne saare overlapping blur layers ek
//     saath scroll mein jank karte hain. Ab solid dark background (jo
//     visually almost same lagta hai) — koi blur compositing nahi.
//  2. `transition-all` — browser ko HAR animatable property (color, shadow,
//     border, sab) frame-by-frame watch karni padti thi sirf ek chhoti si
//     scale/opacity change ke liye. Ab sirf zaroori properties list ki hain.
// `memo()` add kiya taaki jab parent (Rail/HomeContent) re-render ho
// (naya tab select karne par, downloads update hone par, etc.) to jin
// cards ke props nahi badle unhe React dobara process hi na kare.
function MediaCard({ item, index = 0 }) {
  const [loaded, setLoaded] = useState(false)
  const year = item.releaseInfo || item.year || ''
  return (
    <Link
      to={`/title/${item.type}/${encodeURIComponent(item.id)}`}
      // Sized so 3 posters fill one row on a phone screen — big enough that
      // titles stay readable (4-per-row made names truncate too tight).
      className="group shrink-0 w-[calc(33.333vw-19px)] sm:w-[180px] animate-card-in active:scale-95 transition-transform duration-200 will-change-transform hover:-translate-y-1"
      style={{ animationDelay: `${Math.min(index, 12) * 35}ms` }}
    >
      <div className="relative aspect-[2/3] rounded-lg overflow-hidden bg-reel-surface2 ring-1 ring-white/[0.06] group-hover:ring-reel-gold/70 shadow-[inset_0_1px_0_rgba(255,255,255,0.09),0_2px_10px_-4px_rgba(0,0,0,0.5)] group-hover:shadow-[inset_0_1px_0_rgba(255,255,255,0.12),0_18px_36px_-14px_rgba(232,163,61,0.35)] transition-[box-shadow,ring] duration-300">
        {item.poster ? (
          <img
            src={item.poster}
            alt={item.name}
            loading="lazy"
            decoding="async"
            onLoad={() => setLoaded(true)}
            className={`w-full h-full object-cover group-hover:scale-[1.07] transition-[opacity,transform] duration-500 ease-out ${
              loaded ? 'opacity-100' : 'opacity-0'
            }`}
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center text-reel-muted text-[10px] px-1.5 text-center">
            {item.name}
          </div>
        )}
        {!loaded && item.poster ? (
          <div className="absolute inset-0 bg-reel-surface2 overflow-hidden">
            <div className="absolute inset-0 -translate-x-full animate-[shimmer_1.4s_infinite] bg-gradient-to-r from-transparent via-white/[0.06] to-transparent" />
          </div>
        ) : null}
        {/* Bottom scrim: keeps rating chip legible on bright posters, and
            reads as a premium "hover reveal" edge even when nothing else
            sits on it. */}
        <div className="absolute inset-x-0 bottom-0 h-10 bg-gradient-to-t from-black/70 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-none" />
        {/* Top sheen: a thin light streak across the top edge so the poster
            reads as a raised, lacquered card rather than a flat printed
            image — same "glossy" language as the rest of the app. */}
        <div className="absolute inset-x-0 top-0 h-8 bg-gradient-to-b from-white/[0.12] to-transparent pointer-events-none" />
        {item.imdbRating ? (
          <span className="absolute top-1.5 right-1.5 bg-black/85 text-reel-gold text-[10px] font-semibold px-1.5 py-0.5 rounded">
            ★ {item.imdbRating}
          </span>
        ) : null}
      </div>
      <p className="mt-1.5 text-[13px] font-medium text-reel-ink line-clamp-1 group-hover:text-reel-gold transition-colors duration-200">
        {item.name}
      </p>
      <p className="text-[11px] text-reel-muted">{year}</p>
    </Link>
  )
}

export default memo(MediaCard)
