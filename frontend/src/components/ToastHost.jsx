import { createPortal } from 'react-dom'
import { useToasts } from '../lib/toastStore'

// Toasts appear as slim single-line pills at the TOP of the screen (below the status bar),
// so they never collide with the bottom nav, the download progress pill or player controls.
// Portalled to document.body so no ancestor stacking context can hide them.
function Icon({ kind }) {
  const common = { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2.4, strokeLinecap: 'round', strokeLinejoin: 'round' }
  if (kind === 'error') return <svg {...common}><circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16.5v.01" /></svg>
  return <svg {...common}><circle cx="12" cy="12" r="9" /><path d="m8 12.5 2.8 2.8L16 9.5" /></svg>
}

export default function ToastHost() {
  const toasts = useToasts()
  if (!toasts.length) return null

  return createPortal(
    <div
      className="fixed left-0 right-0 z-[70] flex flex-col gap-2 items-center px-4 pointer-events-none"
      style={{ top: 'calc(10px + env(safe-area-inset-top))' }}
    >
      {toasts.slice(-2).map((toast) => (
        <div
          key={toast.id}
          className="animate-toast-in max-w-sm w-auto bg-reel-surface2/95 backdrop-blur ring-1 ring-reel-ink/10 rounded-full pl-3 pr-4 h-10 flex items-center gap-2 shadow-[0_8px_24px_-8px_rgba(0,0,0,0.7)] pointer-events-auto"
        >
          <span className={`shrink-0 ${toast.kind === 'error' ? 'text-reel-rust' : 'text-reel-gold'}`}>
            <Icon kind={toast.kind} />
          </span>
          <span className="text-[12.5px] font-medium text-reel-ink truncate min-w-0">{toast.message}</span>
        </div>
      ))}
    </div>,
    document.body
  )
}
