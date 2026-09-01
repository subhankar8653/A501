// FEATURE (user ask: "theme select karne ka option ke sath ek aur option
// hona chahiye theme icon select karne ka — love shape aur baaki achhe-achhe
// shapes — jo shape choose karenge wahi shape pura theme (color ke sath)
// pe lag jaayega, home aur baaki saari jagah chhote-chhote us shape se bhar
// jaani chahiye"): this is the shape registry for that feature. Each entry
// is small inline SVG markup (no external assets) so it can be embedded
// directly into a data-URI tiled background (see PatternBackground.jsx) and
// recolored on the fly to match whatever theme's gold/accent color is
// currently active — same "pick once, applies everywhere" idea as themes.js.
export const DEFAULT_PATTERN = 'none'

// `markup(color)` returns the shape's inner SVG markup (paths/circles) at a
// 24x24 viewBox, filled with the given color. Kept as plain markup strings
// (not React) because PatternBackground needs to embed these inside a CSS
// background-image data: URI, not the live DOM.
export const PATTERNS = [
  {
    id: 'none',
    name: 'Bina Pattern',
    markup: null,
  },
  {
    id: 'heart',
    name: 'Heart',
    markup: (c) =>
      `<path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z" fill="${c}"/>`,
  },
  {
    id: 'star',
    name: 'Star',
    markup: (c) =>
      `<path d="M12 2l2.9 6.26L22 9.27l-5 4.87L18.18 21 12 17.27 5.82 21 7 14.14l-5-4.87 7.1-1.01L12 2z" fill="${c}"/>`,
  },
  {
    id: 'sparkle',
    name: 'Sparkle',
    markup: (c) =>
      `<path d="M12 2c1.2 5.5 4.3 8.6 9.8 9.8-5.5 1.2-8.6 4.3-9.8 9.8-1.2-5.5-4.3-8.6-9.8-9.8C7.7 10.6 10.8 7.5 12 2z" fill="${c}"/>`,
  },
  {
    id: 'flower',
    name: 'Flower',
    markup: (c) =>
      `<circle cx="12" cy="6" r="3" fill="${c}"/>` +
      `<circle cx="12" cy="18" r="3" fill="${c}"/>` +
      `<circle cx="6" cy="12" r="3" fill="${c}"/>` +
      `<circle cx="18" cy="12" r="3" fill="${c}"/>` +
      `<circle cx="8.5" cy="8.5" r="3" fill="${c}"/>` +
      `<circle cx="15.5" cy="15.5" r="3" fill="${c}"/>` +
      `<circle cx="8.5" cy="15.5" r="3" fill="${c}"/>` +
      `<circle cx="15.5" cy="8.5" r="3" fill="${c}"/>` +
      `<circle cx="12" cy="12" r="3.2" fill="${c}"/>`,
  },
  {
    id: 'diamond',
    name: 'Diamond',
    markup: (c) => `<path d="M12 2l7 10-7 10-7-10z" fill="${c}"/>`,
  },
  {
    id: 'moon',
    name: 'Moon',
    markup: (c) =>
      `<path d="M12.3 3a9 9 0 1 0 8.7 11.5A7 7 0 0 1 12.3 3z" fill="${c}"/>`,
  },
  {
    id: 'cloud',
    name: 'Cloud',
    markup: (c) =>
      `<path d="M6.5 19a4.5 4.5 0 0 1-.4-8.98A6 6 0 0 1 17.6 8.1 4.5 4.5 0 0 1 17 19H6.5z" fill="${c}"/>`,
  },
  {
    id: 'bolt',
    name: 'Bolt',
    markup: (c) => `<path d="M13 2 4 14h6l-1 8 9-12h-6l1-8z" fill="${c}"/>`,
  },
  {
    id: 'paw',
    name: 'Paw',
    markup: (c) =>
      `<circle cx="7" cy="7" r="2.3" fill="${c}"/>` +
      `<circle cx="12" cy="5.3" r="2.3" fill="${c}"/>` +
      `<circle cx="17" cy="7" r="2.3" fill="${c}"/>` +
      `<circle cx="19" cy="12" r="2.3" fill="${c}"/>` +
      `<path d="M12 11c3.5 0 6.5 2.6 6.5 5.6 0 2.2-1.8 3.4-4 3.1-1.6-.2-1.7-1-2.5-1s-.9.8-2.5 1c-2.2.3-4-.9-4-3.1C5.5 13.6 8.5 11 12 11z" fill="${c}"/>`,
  },
]

export function getPattern(id) {
  return PATTERNS.find((p) => p.id === id) || PATTERNS.find((p) => p.id === DEFAULT_PATTERN)
}

// Builds a small repeating SVG tile (two staggered copies of the shape, at
// different sizes/rotations/opacities so it reads as a scattered pattern
// rather than a rigid grid) and returns it as a CSS-ready data: URI. Used
// as `body`'s background-image so the shape shows through everywhere the
// app's normal surface colors don't fully cover (page margins, gaps
// between rail cards, empty states, hero backdrops) — same idea as a chat
// app's wallpaper layer, sitting BEHIND every screen's own content.
const TILE = 96

export function buildPatternDataUri(pattern, color) {
  if (!pattern || pattern.id === 'none' || !pattern.markup) return null
  const shape = pattern.markup(color)
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${TILE}" height="${TILE}" viewBox="0 0 ${TILE} ${TILE}">` +
    `<g opacity="0.55"><g transform="translate(4,4) rotate(-14) scale(0.9)">${shape}</g></g>` +
    `<g opacity="0.35"><g transform="translate(${TILE / 2 + 6},${TILE / 2 + 2}) rotate(18) scale(0.6)">${shape}</g></g>` +
    `</svg>`
  return `data:image/svg+xml,${encodeURIComponent(svg)}`
}

export const PATTERN_TILE_SIZE = TILE
