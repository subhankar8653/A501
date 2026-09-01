import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { DEFAULT_THEME, THEMES, getTheme } from './themes'
import { DEFAULT_PATTERN, PATTERNS, getPattern, buildPatternDataUri, PATTERN_TILE_SIZE } from './patterns'

const STORAGE_KEY = 'hukatube-theme-mode'
// FEATURE (user ask: "theme ke sath ek pattern-icon selector bhi add karo —
// love/star/... shape choose karo aur pura app us shape se chhote-chhote
// tile hoke bhar jaaye, theme ke color ke sath"): pattern choice is stored
// separately from the color theme (its own localStorage key) so the two
// stay independent — user free to mix any shape with any color theme, and
// each persists across reloads on its own.
const PATTERN_STORAGE_KEY = 'hukatube-theme-pattern'
const ThemeModeContext = createContext(null)

function readSavedTheme() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved && THEMES.some((t) => t.id === saved)) return saved
  } catch {
    // localStorage unavailable (private mode, older WebView) — fall back
    // to the default, same as language handling elsewhere in this app.
  }
  return DEFAULT_THEME
}

function readSavedPattern() {
  try {
    const saved = localStorage.getItem(PATTERN_STORAGE_KEY)
    if (saved && PATTERNS.some((p) => p.id === saved)) return saved
  } catch {
    // Same private-mode / old-WebView fallback as readSavedTheme() above.
  }
  return DEFAULT_PATTERN
}

// Writes one theme's palette onto <html> as the CSS custom properties that
// tailwind.config.js's `reel.*` colors resolve to (`var(--reel-gold)` etc).
// Since every component already uses `bg-reel-surface`/`text-reel-gold`/...
// classes, this single DOM write re-colors the ENTIRE app instantly — no
// per-component edits needed.
function applyThemeToDocument(theme) {
  const root = document.documentElement
  root.style.setProperty('--reel-bg', theme.colors.bg)
  root.style.setProperty('--reel-surface', theme.colors.surface)
  root.style.setProperty('--reel-surface2', theme.colors.surface2)
  root.style.setProperty('--reel-gold', theme.colors.gold)
  root.style.setProperty('--reel-rust', theme.colors.rust)
  root.style.setProperty('--reel-ink', theme.colors.ink)
  root.style.setProperty('--reel-muted', theme.colors.muted)
  // Lets form controls, scrollbars, etc. flip between light/dark UA
  // chrome to match a light theme mode (e.g. Daylight Sky, Rose Quartz).
  root.style.colorScheme = theme.isDark ? 'dark' : 'light'
}

// Paints the chosen pattern shape onto `body`'s background-image, tinted
// with the CURRENT theme's gold/accent color — so switching theme mode
// re-colors the pattern automatically (same gold value every other themed
// element already uses), and switching pattern shape doesn't touch colors
// at all. Layered on top of body's own bg-reel-bg background-color class,
// so it shows through wherever a page/component doesn't paint its own
// opaque surface over it.
function applyPatternToDocument(pattern, theme) {
  const body = document.body
  const uri = buildPatternDataUri(pattern, theme.colors.gold)
  if (!uri) {
    body.style.backgroundImage = ''
    return
  }
  body.style.backgroundImage = `url("${uri}")`
  body.style.backgroundRepeat = 'repeat'
  body.style.backgroundSize = `${PATTERN_TILE_SIZE}px ${PATTERN_TILE_SIZE}px`
  // 'fixed' keeps the wallpaper anchored to the viewport (doesn't drift
  // while a page scrolls), matching how the theme's own bg color behaves.
  body.style.backgroundAttachment = 'fixed'
}

export function ThemeModeProvider({ children }) {
  const [themeId, setThemeId] = useState(readSavedTheme)
  const [patternId, setPatternId] = useState(readSavedPattern)

  useEffect(() => {
    applyThemeToDocument(getTheme(themeId))
    try {
      localStorage.setItem(STORAGE_KEY, themeId)
    } catch {
      // Non-fatal — theme still applies for this session even if it
      // can't persist across reloads.
    }
  }, [themeId])

  useEffect(() => {
    applyPatternToDocument(getPattern(patternId), getTheme(themeId))
    try {
      localStorage.setItem(PATTERN_STORAGE_KEY, patternId)
    } catch {
      // Non-fatal — pattern still applies for this session even if it
      // can't persist across reloads.
    }
    // Re-run on themeId change too (not just patternId) — the pattern's
    // color comes from the theme, so switching theme mode must re-tint an
    // already-selected pattern without the user having to reselect it.
  }, [patternId, themeId])

  const changeTheme = useCallback((id) => {
    if (THEMES.some((t) => t.id === id)) setThemeId(id)
  }, [])

  const changePattern = useCallback((id) => {
    if (PATTERNS.some((p) => p.id === id)) setPatternId(id)
  }, [])

  const value = useMemo(
    () => ({
      themeId,
      theme: getTheme(themeId),
      themes: THEMES,
      changeTheme,
      patternId,
      pattern: getPattern(patternId),
      patterns: PATTERNS,
      changePattern,
    }),
    [themeId, changeTheme, patternId, changePattern]
  )

  return <ThemeModeContext.Provider value={value}>{children}</ThemeModeContext.Provider>
}

// Components use this to get: { themeId, theme, themes, changeTheme }
export function useThemeMode() {
  return useContext(ThemeModeContext)
}
