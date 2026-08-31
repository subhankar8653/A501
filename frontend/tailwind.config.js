/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // FEATURE (theme mode switcher — mirrors admin/owner panel's theme
        // registry, see backend/Backend/fastapi/themes.py + frontend
        // src/theme/themes.js): every "reel-*" color used across the app
        // now resolves to a CSS custom property instead of a fixed hex.
        // ThemeContext writes these vars onto <html> when the user picks a
        // theme, so the WHOLE app (every existing bg-reel-*/text-reel-*
        // class, no per-component changes needed) re-colors instantly.
        // Defaults for each var live in src/index.css so nothing breaks if
        // JS hasn't set them yet (first paint / no-JS fallback).
        reel: {
          bg: 'var(--reel-bg)',
          surface: 'var(--reel-surface)',
          surface2: 'var(--reel-surface2)',
          gold: 'var(--reel-gold)',
          rust: 'var(--reel-rust)',
          ink: 'var(--reel-ink)',
          muted: 'var(--reel-muted)',
        },
      },
      fontFamily: {
        display: ['"Fraunces"', 'serif'],
        body: ['"Inter"', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
