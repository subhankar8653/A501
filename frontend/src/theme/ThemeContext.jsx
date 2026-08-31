import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { DEFAULT_THEME, THEMES, getTheme } from './themes'

const STORAGE_KEY = 'hukatube-theme-mode'
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

export function ThemeModeProvider({ children }) {
  const [themeId, setThemeId] = useState(readSavedTheme)

  useEffect(() => {
    applyThemeToDocument(getTheme(themeId))
    try {
      localStorage.setItem(STORAGE_KEY, themeId)
    } catch {
      // Non-fatal — theme still applies for this session even if it
      // can't persist across reloads.
    }
  }, [themeId])

  const changeTheme = useCallback((id) => {
    if (THEMES.some((t) => t.id === id)) setThemeId(id)
  }, [])

  const value = useMemo(
    () => ({ themeId, theme: getTheme(themeId), themes: THEMES, changeTheme }),
    [themeId, changeTheme]
  )

  return <ThemeModeContext.Provider value={value}>{children}</ThemeModeContext.Provider>
}

// Components use this to get: { themeId, theme, themes, changeTheme }
export function useThemeMode() {
  return useContext(ThemeModeContext)
}
