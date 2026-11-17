import { createPortal } from 'react-dom'
import { useState } from 'react'
import { useThemeMode } from '../theme/ThemeContext'
import { tr } from '../i18n/translations'
import { useLanguage } from '../i18n/LanguageContext'

// FEATURE (user ask: "search icon ke aage ek aur icon add karo jo theme
// mode change karega — admin/owner panel wale bohot saare theme mode use
// kar lena"): tap targets are small color-swatch tiles (theme ka apna
// gold/primary + bg color se bana chhota preview), current selection ek
// gold ring se highlight — same visual language as LanguagePicker's grid
// and DownloadQualitySheet's bottom-sheet chrome, so it feels native to
// the rest of the app rather than a bolted-on settings screen.
//
// BUG FIX (user report: "theme wala sheet pura scroll nahi ho raha, neeche
// wale theme click nahi ho pa rahe"): this sheet is opened from inside
// Navbar, which wraps itself in `<div className="sticky top-0 z-30">`. A
// positioned element with a z-index (like that sticky wrapper) creates its
// own stacking context — everything inside it, including this sheet's
// `position: fixed` + `z-[95]`, gets stacked WITHIN that z-30 context, not
// against the page as a whole. BottomNav is a separate sibling at z-40, so
// its entire (higher) stacking context painted on top of Navbar's — sheet
// included — no matter how high the sheet's own z-index was set. The list
// was visually cut off right where BottomNav sits, and taps there hit
// BottomNav instead of the theme tiles underneath. Rendering via a portal
// straight onto document.body sidesteps the whole ancestor-stacking-context
// problem — its z-index is now compared at the true top level, safely above
// BottomNav, and the full grid + its max-h-[80vh] scroll both work as
// intended.
// FEATURE (user ask: "theme select karne ke option ke sath ek aur option
// hona chahiye theme icon select karne ka — love shape aur baaki achhe-achhe
// shapes, jo shape chunoge wahi shape chhote-chhote poore theme (uske color
// ke sath) pe lag jaayega"): second grid below the existing color-theme
// grid, same tile/active-ring visual language so it reads as a natural
// extension of this sheet rather than a separate screen. Each tile previews
// the shape pre-tinted in the CURRENTLY selected theme's gold color (not a
// fixed color) — so the preview always matches what picking it will
// actually look like right now, even before tapping it.
function PatternGrid() {
  const { patternId, patterns, changePattern, theme } = useThemeMode()

  return (
    <>
      <p className="text-reel-ink font-semibold text-sm mb-0.5 mt-4">Pattern</p>
      <p className="text-reel-muted text-[11px] mb-2.5">{tr('theme_pattern_hint')}</p>
      <div className="grid grid-cols-6 gap-2">
        {patterns.map((p) => {
          const active = p.id === patternId
          return (
            <button
              key={p.id}
              onClick={() => changePattern(p.id)}
              aria-label={p.name}
              title={p.name}
              className={`aspect-square rounded-xl grid place-items-center transition active:scale-90 ${
                active
                  ? 'bg-reel-gold/10 ring-2 ring-reel-gold'
                  : 'bg-reel-ink/[0.04] ring-1 ring-reel-ink/5 hover:bg-reel-ink/[0.07]'
              }`}
            >
              {p.id === 'none' ? (
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className="text-reel-muted">
                  <circle cx="12" cy="12" r="9" />
                  <line x1="5" y1="19" x2="19" y2="5" />
                </svg>
              ) : (
                <svg width="20" height="20" viewBox="0 0 24 24" dangerouslySetInnerHTML={{ __html: p.markup(theme.colors.gold) }} />
              )}
            </button>
          )
        })}
      </div>
    </>
  )
}

function PatternSizeSlider() {
  const { patternId, patternScale, changePatternScale, minPatternScale, maxPatternScale, defaultPatternScale } = useThemeMode()
  if (patternId === 'none') return null

  const pct = Math.round(((patternScale - minPatternScale) / (maxPatternScale - minPatternScale)) * 100)

  return (
    <div className="mt-4 bg-reel-ink/[0.04] ring-1 ring-reel-ink/5 rounded-xl px-3.5 py-3">
      <div className="flex items-center justify-between mb-2">
        <p className="text-reel-ink text-xs font-medium">Pattern Size</p>
        <button
          onClick={() => changePatternScale(defaultPatternScale)}
          className="text-[10px] font-medium text-reel-gold hover:opacity-80 active:scale-95 transition"
        >
          Reset
        </button>
      </div>
      <input
        type="range"
        min={minPatternScale}
        max={maxPatternScale}
        step="0.1"
        value={patternScale}
        onChange={(e) => changePatternScale(e.target.value)}
        className="w-full h-1.5 rounded-full appearance-none cursor-pointer accent-reel-gold"
        style={{
          background: `linear-gradient(to right, var(--reel-gold) ${pct}%, rgba(255,255,255,0.12) ${pct}%)`,
        }}
      />
      <div className="flex justify-between mt-1">
        <span className="text-[9px] text-reel-muted">Chota</span>
        <span className="text-[9px] text-reel-muted">Bada</span>
      </div>
    </div>
  )
}

export default function ThemeSheet({ open, onClose }) {
  const { themeId, themes, changeTheme } = useThemeMode()
  const { t } = useLanguage()
  const [mode, setMode] = useState('all')

  if (!open) return null

  const list = themes.filter((th) => mode === 'all' || (mode === 'dark' ? th.isDark : !th.isDark))
  const chips = [
    ['all', t('th_all')],
    ['dark', t('th_dark')],
    ['light', t('th_light')],
  ]

  return createPortal(
    <div className="fixed inset-0 z-[95] flex items-end justify-center" onClick={onClose}>
      <div className="absolute inset-0 bg-black/60" />
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-md bg-reel-bg rounded-t-2xl ring-1 ring-reel-ink/10 shadow-[0_-8px_32px_rgba(0,0,0,0.7)] h-[78vh] flex flex-col"
      >
        {/* Fixed header: title + Dark/Light filter stay put while the grid scrolls */}
        <div className="px-4 pt-3 pb-2">
          <div className="w-10 h-1 rounded-full bg-reel-ink/15 mx-auto mb-3" />
          <div className="flex items-center justify-between">
            <p className="text-reel-ink font-semibold">Theme</p>
            <div className="flex gap-1.5">
              {chips.map(([id, label]) => (
                <button
                  key={id}
                  onClick={() => setMode(id)}
                  className={`px-3 py-1 rounded-full text-[11px] font-medium transition active:scale-95 ${
                    mode === id ? 'bg-reel-gold text-reel-bg' : 'bg-reel-ink/[0.06] text-reel-ink/80'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto px-4 pb-[calc(1.25rem+env(safe-area-inset-bottom))]">
          <div className="grid grid-cols-4 gap-x-2 gap-y-3 pt-1">
            {list.map((th) => {
              const active = th.id === themeId
              return (
                <button
                  key={th.id}
                  onClick={() => changeTheme(th.id)}
                  className="flex flex-col items-center gap-1.5 active:scale-95 transition"
                >
                  <span
                    className={`relative w-full aspect-square rounded-2xl overflow-hidden grid grid-cols-2 grid-rows-2 ${
                      active ? 'ring-2 ring-reel-gold ring-offset-2 ring-offset-reel-bg' : 'ring-1 ring-reel-ink/15'
                    }`}
                    aria-hidden="true"
                  >
                    <span style={{ background: th.colors.bg }} />
                    <span style={{ background: th.colors.gold }} />
                    <span style={{ background: th.colors.surface2 }} />
                    <span style={{ background: th.colors.rust }} />
                    {active ? (
                      <span className="absolute inset-0 grid place-items-center bg-black/25">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 6 9 17l-5-5" /></svg>
                      </span>
                    ) : null}
                  </span>
                  <span className={`w-full text-center text-[10.5px] leading-tight line-clamp-2 ${active ? 'text-reel-ink font-semibold' : 'text-reel-ink/75'}`}>
                    {th.name}
                  </span>
                </button>
              )
            })}
          </div>

          <PatternGrid />
          <PatternSizeSlider />
        </div>
      </div>
    </div>,
    document.body
  )
}
