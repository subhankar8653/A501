/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        reel: {
          bg: '#0B0B12',
          surface: '#16151F',
          surface2: '#1E1D2A',
          gold: '#E8A33D',
          rust: '#C1443C',
          ink: '#F2EFE6',
          muted: '#8B8798',
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
