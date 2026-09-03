// Theme registry for the app's theme-mode switcher.
// FEATURE (user ask: "search icon ke aage ek aur icon add karo jo theme
// mode change karega, admin/owner panel wale theme mode use kar lena"):
// this mirrors backend/Backend/fastapi/themes.py's THEMES dict (the same
// palettes the owner/admin panel already offers), remapped from that
// panel's {primary, secondary, accent, background, card, border, text,
// text_secondary} color roles onto this app's existing Tailwind tokens
// (reel-bg/surface/surface2/gold/rust/ink/muted — see tailwind.config.js).
//
// 'classic_reel' (first entry, default) is NOT from the admin panel — it's
// the app's original hand-tuned palette, kept as-is so nothing changes
// visually until the user actively picks a different mode.
export const DEFAULT_THEME = 'classic_reel'

export const THEMES = [
  {
    id: 'classic_reel',
    name: 'Classic Reel',
    isDark: true,
    colors: {
      bg: '#0B0B12',
      surface: '#16151F',
      surface2: '#1E1D2A',
      gold: '#E8A33D',
      rust: '#C1443C',
      ink: '#F2EFE6',
      muted: '#8B8798',
    },
  },
  {
    id: 'graphite_amber',
    name: 'Graphite Amber',
    isDark: true,
    colors: {
      bg: '#0C0C0D',
      surface: '#18181B',
      surface2: '#2B2B30',
      gold: '#F59E0B',
      rust: '#D97706',
      ink: '#FAFAF9',
      muted: '#A9A8A4',
    },
  },
  {
    id: 'amoled_midnight',
    name: 'AMOLED Midnight',
    isDark: true,
    colors: {
      bg: '#000000',
      surface: '#0B0B0F',
      surface2: '#1E2430',
      gold: '#38BDF8',
      rust: '#0EA5E9',
      ink: '#F4F7FB',
      muted: '#9AA6B8',
    },
  },
  {
    id: 'obsidian_emerald',
    name: 'Obsidian Emerald',
    isDark: true,
    colors: {
      bg: '#08100C',
      surface: '#111C16',
      surface2: '#203029',
      gold: '#10B981',
      rust: '#059669',
      ink: '#ECFDF5',
      muted: '#92B6A4',
    },
  },
  {
    id: 'royal_violet',
    name: 'Royal Violet',
    isDark: true,
    colors: {
      bg: '#0B0712',
      surface: '#171022',
      surface2: '#2C2142',
      gold: '#8B5CF6',
      rust: '#7C3AED',
      ink: '#F5F3FF',
      muted: '#B4A8CF',
    },
  },
  {
    id: 'slate_ocean',
    name: 'Slate Ocean',
    isDark: true,
    colors: {
      bg: '#0A0F1A',
      surface: '#121C2B',
      surface2: '#23324B',
      gold: '#0EA5E9',
      rust: '#0284C7',
      ink: '#EFF6FF',
      muted: '#93A7C4',
    },
  },
  {
    id: 'charcoal_violet',
    name: 'Charcoal Violet',
    isDark: true,
    colors: {
      bg: '#160B1C',
      surface: '#241130',
      surface2: '#3C1A47',
      gold: '#B6FF00',
      rust: '#93CC00',
      ink: '#F4EAF8',
      muted: '#B79CC4',
    },
  },
  {
    id: 'fresh_canopy',
    name: 'Fresh Canopy',
    isDark: true,
    colors: {
      bg: '#141B12',
      surface: '#2D3E2C',
      surface2: '#3E5139',
      gold: '#E4FD97',
      rust: '#C3E86B',
      ink: '#EFF6DD',
      muted: '#A9BE9B',
    },
  },
  {
    id: 'tiffany_noir',
    name: 'Tiffany Noir',
    isDark: true,
    colors: {
      bg: '#0D0D0D',
      surface: '#171717',
      surface2: '#282828',
      gold: '#21F1A8',
      rust: '#12C88A',
      ink: '#EFFFF9',
      muted: '#8FA89E',
    },
  },
  {
    id: 'bridal_blush',
    name: 'Bridal Blush',
    isDark: true,
    colors: {
      bg: '#1B080F',
      surface: '#38131E',
      surface2: '#741A2F',
      gold: '#FFC6A8',
      rust: '#E8A98A',
      ink: '#FFEDE3',
      muted: '#D2A093',
    },
  },
  {
    id: 'rose_quartz',
    name: 'Rose Quartz',
    isDark: false,
    colors: {
      bg: '#FFF1F2',
      surface: '#FFFFFF',
      surface2: '#FBD5DB',
      gold: '#E11D48',
      rust: '#BE123C',
      ink: '#4C0519',
      muted: '#8A2B3E',
    },
  },
  {
    id: 'daylight_sky',
    name: 'Daylight Sky',
    isDark: false,
    colors: {
      bg: '#F8FAFC',
      surface: '#FFFFFF',
      surface2: '#E2E8F0',
      gold: '#2563EB',
      rust: '#1D4ED8',
      ink: '#0F172A',
      muted: '#475569',
    },
  },
  {
    id: 'sage_linen',
    name: 'Sage Linen',
    isDark: false,
    colors: {
      bg: '#F5F8F6',
      surface: '#FFFFFF',
      surface2: '#DCE7E3',
      gold: '#0F766E',
      rust: '#0D9488',
      ink: '#11271F',
      muted: '#4B5D58',
    },
  },
  {
    id: 'golden_hour',
    name: 'Golden Hour',
    isDark: false,
    colors: {
      bg: '#FFFBF2',
      surface: '#FFFFFF',
      surface2: '#F0E4CC',
      gold: '#B45309',
      rust: '#92400E',
      ink: '#3F2D12',
      muted: '#7A5A2E',
    },
  },
  // -------------------------------------------------------------------
  // FEATURE (user ask: "theme mein aur achhe-achhe color combine add karo
  // — pink aur red bhi, total 10 aur"): ten more hand-picked palettes,
  // several built specifically around pink/red/crimson (as asked), plus a
  // spread of other combos (teal-pink, magenta, coral, wine) so the
  // picker doesn't lean on just one hue family.
  // -------------------------------------------------------------------
  {
    id: 'crimson_noir',
    name: 'Crimson Noir',
    isDark: true,
    colors: {
      bg: '#150608',
      surface: '#230D10',
      surface2: '#3B141A',
      gold: '#FF4757',
      rust: '#C1121F',
      ink: '#FCE9EA',
      muted: '#C98F94',
    },
  },
  {
    id: 'neon_flamingo',
    name: 'Neon Flamingo',
    isDark: true,
    colors: {
      bg: '#0D0710',
      surface: '#1B0F22',
      surface2: '#2E1638',
      gold: '#FF2E9F',
      rust: '#C6008C',
      ink: '#FDEBF6',
      muted: '#C79BC0',
    },
  },
  {
    id: 'coral_blush',
    name: 'Coral Blush',
    isDark: true,
    colors: {
      bg: '#170A08',
      surface: '#291310',
      surface2: '#3F1D18',
      gold: '#FF6F61',
      rust: '#E8503F',
      ink: '#FDEEE9',
      muted: '#C99C90',
    },
  },
  {
    id: 'cherry_blossom',
    name: 'Cherry Blossom',
    isDark: false,
    colors: {
      bg: '#FFF5F7',
      surface: '#FFFFFF',
      surface2: '#FFE1E8',
      gold: '#DB2777',
      rust: '#BE185D',
      ink: '#4A0E24',
      muted: '#9C5470',
    },
  },
  {
    id: 'ruby_wine',
    name: 'Ruby Wine',
    isDark: true,
    colors: {
      bg: '#0F0508',
      surface: '#1D0A10',
      surface2: '#33121C',
      gold: '#E11D48',
      rust: '#9F1239',
      ink: '#FCE7EC',
      muted: '#B98894',
    },
  },
  {
    id: 'magenta_dusk',
    name: 'Magenta Dusk',
    isDark: true,
    colors: {
      bg: '#0C0710',
      surface: '#180E22',
      surface2: '#2A1638',
      gold: '#D946EF',
      rust: '#A21CAF',
      ink: '#FAEBFC',
      muted: '#BB9BC7',
    },
  },
  {
    id: 'blood_moon',
    name: 'Blood Moon',
    isDark: true,
    colors: {
      bg: '#0A0303',
      surface: '#170707',
      surface2: '#2B0C0C',
      gold: '#DC2626',
      rust: '#7F1D1D',
      ink: '#FDECEC',
      muted: '#B98686',
    },
  },
  {
    id: 'bubblegum_pop',
    name: 'Bubblegum Pop',
    isDark: true,
    colors: {
      bg: '#0E0812',
      surface: '#1C1024',
      surface2: '#2E1A3A',
      gold: '#FF6EC7',
      rust: '#A855F7',
      ink: '#FDF0FA',
      muted: '#C79FD1',
    },
  },
  {
    id: 'scarlet_ember',
    name: 'Scarlet Ember',
    isDark: true,
    colors: {
      bg: '#140705',
      surface: '#25100A',
      surface2: '#3C1B10',
      gold: '#F4511E',
      rust: '#D62828',
      ink: '#FCEDE6',
      muted: '#C99A85',
    },
  },
  {
    id: 'flamingo_tropic',
    name: 'Flamingo Tropic',
    isDark: true,
    colors: {
      bg: '#070F10',
      surface: '#0F1E1E',
      surface2: '#183131',
      gold: '#FF5DA2',
      rust: '#14B8A6',
      ink: '#EAFBF7',
      muted: '#8FBDB5',
    },
  },
]

export function getTheme(id) {
  return THEMES.find((t) => t.id === id) || THEMES.find((t) => t.id === DEFAULT_THEME)
}
