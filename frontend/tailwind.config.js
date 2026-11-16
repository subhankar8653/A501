/** @type {import('tailwindcss').Config} */
// FIX: reel-* colors CSS variables hain, isliye Tailwind ke opacity modifiers
// (ring-reel-ink/5, bg-reel-gold/15 ...) pehle kaam hi nahi karte the — ring
// default NEELE rang ka aata tha, bg gayab. Ab har color alpha ke saath
// color-mix() se sahi theme-colour banata hai.
const reelColor = (name) => ({ opacityValue }) => {
  if (opacityValue === undefined || String(opacityValue).startsWith('var(')) return `var(--reel-${name})`
  const a = parseFloat(opacityValue)
  if (!(a < 1)) return `var(--reel-${name})`
  return `color-mix(in srgb, var(--reel-${name}) ${Math.round(a * 100)}%, transparent)`
}

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
          bg: reelColor('bg'),
          surface: reelColor('surface'),
          surface2: reelColor('surface2'),
          gold: reelColor('gold'),
          rust: reelColor('rust'),
          ink: reelColor('ink'),
          muted: reelColor('muted'),
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
