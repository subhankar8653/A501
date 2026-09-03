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
  // -------------------------------------------------------------------
  // FEATURE (user ask: "pattern mein bhi aur 10 add karo"): ten more
  // shapes alongside the original nine, same "inline markup at 24x24,
  // recolored at runtime" approach as everything above.
  // -------------------------------------------------------------------
  {
    id: 'sun',
    name: 'Sun',
    markup: (c) =>
      `<circle cx="12" cy="12" r="4.3" fill="${c}"/>` +
      `<rect x="11" y="1" width="2" height="4" rx="1" fill="${c}"/>` +
      `<rect x="11" y="1" width="2" height="4" rx="1" fill="${c}" transform="rotate(45 12 12)"/>` +
      `<rect x="11" y="1" width="2" height="4" rx="1" fill="${c}" transform="rotate(90 12 12)"/>` +
      `<rect x="11" y="1" width="2" height="4" rx="1" fill="${c}" transform="rotate(135 12 12)"/>` +
      `<rect x="11" y="1" width="2" height="4" rx="1" fill="${c}" transform="rotate(180 12 12)"/>` +
      `<rect x="11" y="1" width="2" height="4" rx="1" fill="${c}" transform="rotate(225 12 12)"/>` +
      `<rect x="11" y="1" width="2" height="4" rx="1" fill="${c}" transform="rotate(270 12 12)"/>` +
      `<rect x="11" y="1" width="2" height="4" rx="1" fill="${c}" transform="rotate(315 12 12)"/>`,
  },
  {
    id: 'snowflake',
    name: 'Snowflake',
    markup: (c) =>
      `<rect x="11" y="2" width="2" height="20" rx="1" fill="${c}"/>` +
      `<rect x="2" y="11" width="20" height="2" rx="1" fill="${c}"/>` +
      `<rect x="11" y="2" width="2" height="20" rx="1" fill="${c}" transform="rotate(45 12 12)"/>` +
      `<rect x="11" y="2" width="2" height="20" rx="1" fill="${c}" transform="rotate(-45 12 12)"/>`,
  },
  {
    id: 'crown',
    name: 'Crown',
    markup: (c) => `<path d="M3 18h18l1-10-5 4-5-8-5 8-5-4 1 10z" fill="${c}"/>`,
  },
  {
    id: 'anchor',
    name: 'Anchor',
    markup: (c) =>
      `<circle cx="12" cy="5" r="2.2" fill="${c}"/>` +
      `<rect x="11" y="6.5" width="2" height="13.5" fill="${c}"/>` +
      `<rect x="7" y="9" width="10" height="2" fill="${c}"/>` +
      `<path d="M4 13a8 8 0 0 0 16 0h-2.3a5.7 5.7 0 0 1-11.4 0z" fill="${c}"/>`,
  },
  {
    id: 'music',
    name: 'Music Note',
    markup: (c) =>
      `<circle cx="8" cy="18" r="3.2" fill="${c}"/>` +
      `<rect x="10.4" y="3" width="2" height="15" fill="${c}"/>` +
      `<path d="M12.4 3l7 2v4l-7-2z" fill="${c}"/>`,
  },
  {
    id: 'flame',
    name: 'Flame',
    markup: (c) =>
      `<path d="M12 2c1 4-3 5-3 9a3 3 0 0 0 6 0c0-1-.5-2-1-3 2 1 4 3 4 6a6 6 0 0 1-12 0c0-5 3-7 6-12z" fill="${c}"/>`,
  },
  {
    id: 'leaf',
    name: 'Leaf',
    markup: (c) =>
      `<ellipse cx="12" cy="12" rx="8" ry="4.5" fill="${c}" transform="rotate(45 12 12)"/>` +
      `<rect x="11.4" y="14" width="1.2" height="7" rx="0.6" fill="${c}" transform="rotate(45 12 17.5)"/>`,
  },
  {
    id: 'feather',
    name: 'Feather',
    markup: (c) =>
      `<path d="M19 2c-9 1-15 7-16 16l-1 3 3-1c9-1 15-7 16-16z" fill="${c}"/>` +
      `<rect x="10.5" y="9" width="1" height="12" fill="${c}" transform="rotate(45 11 15)"/>`,
  },
  {
    id: 'butterfly',
    name: 'Butterfly',
    markup: (c) =>
      `<ellipse cx="7" cy="9" rx="5" ry="6" fill="${c}" transform="rotate(-20 7 9)"/>` +
      `<ellipse cx="17" cy="9" rx="5" ry="6" fill="${c}" transform="rotate(20 17 9)"/>` +
      `<ellipse cx="8" cy="17" rx="3.2" ry="4" fill="${c}" transform="rotate(-10 8 17)"/>` +
      `<ellipse cx="16" cy="17" rx="3.2" ry="4" fill="${c}" transform="rotate(10 16 17)"/>` +
      `<rect x="11.3" y="4" width="1.4" height="17" rx="0.7" fill="${c}"/>`,
  },
  {
    id: 'puzzle',
    name: 'Puzzle',
    markup: (c) =>
      `<path d="M4 4h6c0-1.4 1-2.4 2-2.4s2 1 2 2.4h6v6c1.4 0 2.4 1 2.4 2s-1 2-2.4 2v6h-6c0 1.4-1 2.4-2 2.4s-2-1-2-2.4H4v-6c-1.4 0-2.4-1-2.4-2s1-2 2.4-2z" fill="${c}"/>`,
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
//
// SIZE/SUBTLETY TUNING (user feedback: "aur chota, aur theme ke sath blend
// hona chahiye" — shapes were reading as bold foreground hearts instead of
// a quiet wallpaper texture): tile shrunk 96->56px and per-shape scale cut
// roughly in half, so many more, much smaller copies repeat instead of a
// few large ones; opacity dropped substantially (0.55/0.35 -> 0.16/0.10)
// so it sits as a faint texture behind content rather than competing with
// it, closer to how a premium app's subtle wallpaper pattern reads.
const TILE = 56

export function buildPatternDataUri(pattern, color) {
  if (!pattern || pattern.id === 'none' || !pattern.markup) return null
  const shape = pattern.markup(color)
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${TILE}" height="${TILE}" viewBox="0 0 ${TILE} ${TILE}">` +
    `<g opacity="0.16"><g transform="translate(2,2) rotate(-14) scale(0.46)">${shape}</g></g>` +
    `<g opacity="0.10"><g transform="translate(${TILE / 2 + 3},${TILE / 2 + 1}) rotate(18) scale(0.3)">${shape}</g></g>` +
    `</svg>`
  return `data:image/svg+xml,${encodeURIComponent(svg)}`
}

export const PATTERN_TILE_SIZE = TILE
