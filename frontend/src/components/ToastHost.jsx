import { createPortal } from 'react-dom'
import { useToasts } from '../lib/toastStore'

// Renders active toasts (see lib/toastStore.js) as a stack of pills sitting
// just above the bottom nav — same slot DownloadToast's progress bar uses,
// but portalled to document.body (see comment in ThemeSheet.jsx for why:
// anything `position: fixed` rendered inside Navbar's `sticky z-30` wrapper
// gets trapped in that wrapper's own stacking context and can end up
// rendering BEHIND BottomNav no matter how high its own z-index is).
export default function ToastHost() {
  const toasts = useToasts()
  if (!toasts.length) return null

  return createPortal(
    <div
      className="fixed left-2 right-2 z-[70] flex flex-col gap-2 items-center pointer-events-none"
      style={{ bottom: 'calc(126px + env(safe-area-inset-bottom))' }}
    >
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className="animate-toast-in max-w-sm w-full sm:w-auto bg-reel-surface2 ring-1 ring-reel-ink/10 rounded-xl px-3.5 py-2.5 flex items-center gap-2.5 shadow-[0_8px_24px_-8px_rgba(0,0,0,0.6)] pointer-events-auto"
        >
          <span
            className={`w-2 h-2 rounded-full shrink-0 ${
              toast.kind === 'error' ? 'bg-reel-rust' : 'bg-reel-gold'
            }`}
            aria-hidden="true"
          />
          <span className="text-xs text-reel-ink flex-1 min-w-0">{toast.message}</span>
        </div>
      ))}
    </div>,
    document.body
  )
}
