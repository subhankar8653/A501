import { useThemeMode } from '../theme/ThemeContext'

// FEATURE (user ask: "search icon ke aage ek aur icon add karo jo theme
// mode change karega — admin/owner panel wale bohot saare theme mode use
// kar lena"): tap targets are small color-swatch tiles (theme ka apna
// gold/primary + bg color se bana chhota preview), current selection ek
// gold ring se highlight — same visual language as LanguagePicker's grid
// and DownloadQualitySheet's bottom-sheet chrome, so it feels native to
// the rest of the app rather than a bolted-on settings screen.
export default function ThemeSheet({ open, onClose }) {
  const { themeId, themes, changeTheme } = useThemeMode()

  if (!open) return null

  return (
    <div className="fixed inset-0 z-[95] flex items-end justify-center" onClick={onClose}>
      <div className="absolute inset-0 bg-black/60" />
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-md bg-reel-bg rounded-t-2xl pt-3 pb-[calc(1.5rem+env(safe-area-inset-bottom))] px-5 ring-1 ring-reel-ink/10 shadow-[0_-8px_32px_rgba(0,0,0,0.7)] max-h-[80vh] overflow-y-auto"
      >
        <div className="w-10 h-1 rounded-full bg-reel-ink/15 mx-auto mb-4" />

        <p className="text-reel-ink font-semibold mb-1">Theme</p>
        <p className="text-reel-muted text-xs mb-4">Apna pasandida theme mode chuno</p>

        <div className="grid grid-cols-2 gap-2.5 mb-1">
          {themes.map((t) => {
            const active = t.id === themeId
            return (
              <button
                key={t.id}
                onClick={() => changeTheme(t.id)}
                className={`flex items-center gap-2.5 px-3 py-2.5 rounded-xl text-left transition active:scale-[0.98] ${
                  active
                    ? 'bg-reel-gold/10 ring-2 ring-reel-gold'
                    : 'bg-reel-ink/[0.04] ring-1 ring-reel-ink/5 hover:bg-reel-ink/[0.07]'
                }`}
              >
                <span
                  className="shrink-0 w-8 h-8 rounded-full ring-1 ring-reel-ink/10 overflow-hidden grid grid-cols-2 grid-rows-2"
                  aria-hidden="true"
                >
                  <span style={{ background: t.colors.bg }} />
                  <span style={{ background: t.colors.gold }} />
                  <span style={{ background: t.colors.surface2 }} />
                  <span style={{ background: t.colors.rust }} />
                </span>
                <span className="min-w-0">
                  <span className={`block text-sm font-medium truncate ${active ? 'text-reel-ink' : 'text-reel-ink/85'}`}>
                    {t.name}
                  </span>
                  <span className="block text-[10px] text-reel-muted">
                    {t.isDark ? 'Dark' : 'Light'}
                  </span>
                </span>
                {active ? (
                  <svg
                    width="16"
                    height="16"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="3"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    className="ml-auto shrink-0 text-reel-gold"
                  >
                    <path d="M20 6 9 17l-5-5" />
                  </svg>
                ) : null}
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}
