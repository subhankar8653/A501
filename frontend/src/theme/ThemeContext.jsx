import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { DEFAULT_THEME, THEMES, getTheme } from './themes'
import {
  DEFAULT_PATTERN,
  PATTERNS,
  getPattern,
  buildPatternDataUri,
  PATTERN_TILE_SIZE,
  DEFAULT_PATTERN_SCALE,
  MIN_PATTERN_SCALE,
  MAX_PATTERN_SCALE,
} from './patterns'

const STORAGE_KEY = 'hukatube-theme-mode'
// FEATURE (user ask: "theme ke sath ek pattern-icon selector bhi add karo —
// love/star/... shape choose karo aur pura app us shape se chhote-chhote
// tile hoke bhar jaaye, theme ke color ke sath"): pattern choice is stored
// separately from the color theme (its own localStorage key) so the two
// stay independent — user free to mix any shape with any color theme, and
// each persists across reloads on its own.
const PATTERN_STORAGE_KEY = 'hukatube-theme-pattern'
// FEATURE (user ask: "pattern ke neeche uska size set karne ka ek bar do"):
// separate key again, same reasoning — the chosen shape and its size are
// independent choices, each should persist on its own.
const PATTERN_SCALE_STORAGE_KEY = 'hukatube-theme-pattern-scale'
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

function readSavedPatternScale() {
  try {
    const saved = parseFloat(localStorage.getItem(PATTERN_SCALE_STORAGE_KEY))
    if (!Number.isNaN(saved) && saved >= MIN_PATTERN_SCALE && saved <= MAX_PATTERN_SCALE) return saved
  } catch {
    // Same private-mode / old-WebView fallback as readSavedTheme() above.
  }
  return DEFAULT_PATTERN_SCALE
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

// Paints the chosen pattern shape onto `body`'s background-image — the
// main scroll area only (NOT header/footer; see the .chrome-surface
// comment in index.css for why that combination was rolled back for
// performance). Tinted with the CURRENT theme's gold/accent color, so
// switching theme mode re-colors the pattern too — same gold value every
// other themed element uses.
function applyPatternToDocument(pattern, theme, scale) {
  const body = document.body
  const uri = buildPatternDataUri(pattern, theme.colors.gold)
  if (!uri) {
    body.style.backgroundImage = ''
    body.style.backgroundAttachment = ''
    return
  }
  const size = Math.round(PATTERN_TILE_SIZE * (scale || DEFAULT_PATTERN_SCALE))
  body.style.backgroundImage = `url("${uri}")`
  body.style.backgroundRepeat = 'repeat'
  body.style.backgroundSize = `${size}px ${size}px`
  // Explicitly reset to the default (not just "leave unset") so an earlier
  // 'fixed' value from a stale cached build can never linger.
  body.style.backgroundAttachment = 'scroll'
  // PERF FIX (user ask: "poora app aur smooth/makkhan jaisa chalna
  // chahiye"): this used to be 'fixed' so the wallpaper stayed anchored to
  // the viewport instead of drifting with the page. But `background-
  // attachment: fixed` is a well-known mobile WebView scroll-jank cause —
  // most mobile browser engines can't GPU-composite a fixed background the
  // way desktop Chrome can, so it forces a full-page repaint on EVERY
  // scroll frame, on EVERY screen, for as long as any pattern is selected.
  // For a small 56px repeating texture, plain 'scroll' (the default —
  // moves with the page like any other background) is visually almost
  // identical on a phone screen (you'd only ever notice the difference by
  // scrolling several screens and comparing), but composites on the GPU
  // like a normal background instead of repainting on the main thread —
  // so it's the right trade for a subtle decorative layer.
}

export function ThemeModeProvider({ children }) {
  const [themeId, setThemeId] = useState(readSavedTheme)
  const [patternId, setPatternId] = useState(readSavedPattern)
  const [patternScale, setPatternScale] = useState(readSavedPatternScale)

  useEffect(() => {
    applyThemeToDocument(getTheme(themeId))
    // BUG FIX (user report, with screenshot: "video player mein play button/
    // ring/quality-text/progress-bar hamesha yellow hi rehte hain, web side
    // pe alag theme select karne ke baad bhi"): those controls are the
    // ANDROID APP's native ExoPlayer view (inline_player_control_view.xml /
    // activity_player.xml / dialog_equalizer.xml — a completely separate
    // rendering layer from this WebView's CSS), so they have no way to know
    // the color theme changed unless we explicitly tell them. `window.
    // AndroidPlayer` is the existing native JS bridge (see MainActivity.kt's
    // WebAppInterface) — `setThemeColor` is a new method on it (native side:
    // MainActivity.setInlineThemeColor() / PlayerActivity's intent-extra
    // handoff) that retints the play button, its ring, the quality badge,
    // and the progress bar/scrubber to match. Guarded with `?.` since this
    // bridge object only exists inside the Android app's WebView — plain
    // browser visits (or iOS) simply skip the call, no error.
    try {
      window.AndroidPlayer?.setThemeColor?.(getTheme(themeId).colors.gold)
    } catch {
      // Non-fatal — native bridge call failing shouldn't break the web
      // theme switch itself.
    }
    try {
      localStorage.setItem(STORAGE_KEY, themeId)
    } catch {
      // Non-fatal — theme still applies for this session even if it
      // can't persist across reloads.
    }
  }, [themeId])

  useEffect(() => {
    applyPatternToDocument(getPattern(patternId), getTheme(themeId), patternScale)
    try {
      localStorage.setItem(PATTERN_STORAGE_KEY, patternId)
    } catch {
      // Non-fatal — pattern still applies for this session even if it
      // can't persist across reloads.
    }
    try {
      localStorage.setItem(PATTERN_SCALE_STORAGE_KEY, String(patternScale))
    } catch {
      // Non-fatal — same reasoning as above.
    }
    // Re-run on themeId change too (not just patternId) — the pattern's
    // color comes from the theme, so switching theme mode must re-tint an
    // already-selected pattern without the user having to reselect it.
  }, [patternId, themeId, patternScale])

  const changeTheme = useCallback((id) => {
    if (THEMES.some((t) => t.id === id)) setThemeId(id)
  }, [])

  const changePattern = useCallback((id) => {
    if (PATTERNS.some((p) => p.id === id)) setPatternId(id)
  }, [])

  const changePatternScale = useCallback((scale) => {
    const clamped = Math.min(MAX_PATTERN_SCALE, Math.max(MIN_PATTERN_SCALE, Number(scale) || DEFAULT_PATTERN_SCALE))
    setPatternScale(clamped)
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
      patternScale,
      changePatternScale,
      minPatternScale: MIN_PATTERN_SCALE,
      maxPatternScale: MAX_PATTERN_SCALE,
      defaultPatternScale: DEFAULT_PATTERN_SCALE,
    }),
    [themeId, changeTheme, patternId, changePattern, patternScale, changePatternScale]
  )

  return <ThemeModeContext.Provider value={value}>{children}</ThemeModeContext.Provider>
}

// Components use this to get: { themeId, theme, themes, changeTheme }
export function useThemeMode() {
  return useContext(ThemeModeContext)
}
