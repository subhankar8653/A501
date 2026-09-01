import { useEffect, useRef, useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext'

// FEATURE (user ask: "YouTube ka comment section dekho, mera bekar dikh
// raha hai — button pe click karne par khule, aur sahi se band bhi ho"):
// pehle poore comments list Player page mein hi inline expand ho jaati thi
// — 50+ comments hote hi player ka page bahut lamba/bhara-bhara lagta tha.
// Ab sirf ek chhota "Comments · N" bar dikhta hai (dekho Player.jsx); usko
// tap karne par yeh bottom sheet upar slide hoti hai (YouTube jaisa),
// backdrop tap / X button / neeche-swipe teeno se band hoti hai.
export default function CommentsSheet({ open, onClose, title, children }) {
  const [dragY, setDragY] = useState(0)
  const dragging = useRef(false)
  const startY = useRef(0)

  useEffect(() => {
    if (!open) return
    setDragY(0)
    // Lock background scroll while the sheet is open, like a real modal.
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = prevOverflow
    }
  }, [open])

  if (!open) return null

  function onPointerDown(e) {
    dragging.current = true
    startY.current = e.touches ? e.touches[0].clientY : e.clientY
  }
  function onPointerMove(e) {
    if (!dragging.current) return
    const y = e.touches ? e.touches[0].clientY : e.clientY
    setDragY(Math.max(0, y - startY.current))
  }
  function onPointerUp() {
    if (!dragging.current) return
    dragging.current = false
    if (dragY > 90) onClose()
    else setDragY(0)
  }

  return (
    <div className="fixed inset-0 z-[100] flex items-end justify-center">
      <div className="absolute inset-0 bg-black/60" onClick={onClose} />
      <div
        onTouchStart={onPointerDown}
        onTouchMove={onPointerMove}
        onTouchEnd={onPointerUp}
        style={{
          transform: `translateY(${dragY}px)`,
          transition: dragging.current ? 'none' : 'transform 220ms cubic-bezier(.2,.8,.2,1)',
        }}
        className="relative w-full max-w-2xl bg-reel-bg rounded-t-2xl ring-1 ring-reel-ink/10 shadow-[0_-8px_32px_rgba(0,0,0,0.7)] flex flex-col"
      >
        {/* Drag handle — same swipe-down-to-close affordance as YouTube's sheet */}
        <div className="pt-3 pb-2 shrink-0 cursor-grab active:cursor-grabbing">
          <div className="w-10 h-1 rounded-full bg-reel-ink/15 mx-auto" />
        </div>

        <div className="flex items-center justify-between px-4 pb-3 border-b border-reel-ink/5 shrink-0">
          <p className="font-display font-semibold text-reel-ink">{title}</p>
          <button
            onClick={onClose}
            aria-label="Close comments"
            className="w-8 h-8 rounded-full bg-reel-surface2 flex items-center justify-center text-reel-muted active:scale-90 transition"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></svg>
          </button>
        </div>

        <div className="overflow-y-auto px-4 pt-4 pb-[calc(1rem+env(safe-area-inset-bottom))]" style={{ maxHeight: '70vh' }}>
          {children}
        </div>
      </div>
    </div>
  )
}
