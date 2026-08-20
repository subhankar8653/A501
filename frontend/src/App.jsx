import { useEffect } from 'react'
import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { getConfig } from './api'
import { cleanupStaleDownloads } from './lib/downloadsStore'
import Navbar from './components/Navbar'
import BottomNav from './components/BottomNav'
import DownloadToast from './components/DownloadToast'
import Setup from './pages/Setup'
import Home from './pages/Home'
import Search from './pages/Search'
import Detail from './pages/Detail'
import Player from './pages/Player'
import Saved from './pages/Saved'
import Downloads from './pages/Downloads'
import Profile from './pages/Profile'

function RequireConfig({ children }) {
  const cfg = getConfig()
  if (!cfg) return <Navigate to="/setup" replace />
  return children
}

// The player and title-detail pages get a fullscreen layout — no site header.
function ChromeForRoute({ children }) {
  const { pathname } = useLocation()
  const hideChrome = pathname.startsWith('/watch/') || pathname.startsWith('/title/')

  // React Router doesn't reset scroll position on navigation by itself —
  // without this, going Home (scrolled halfway down a rail) -> a title's
  // Detail page landed you mid-page on the new page too, looking broken.
  useEffect(() => {
    window.scrollTo(0, 0)
  }, [pathname])

  return (
    <>
      {!hideChrome && <Navbar />}
      {/* key={pathname} remounts on every route change, which is what
          drives the page-fade-in entrance below — a plain className alone
          wouldn't replay since the div itself never actually unmounts.

          BUG FIX (user report: bottom sheets/modals — like the "Download
          Season" quality picker — were getting clipped short and appearing
          BEHIND the bottom nav bar instead of over it): `page-fade-in`'s
          keyframes end on `transform: translateY(0)`, and thanks to
          `animation-fill-mode: both` that computed transform value sticks
          around on this div even after the 260ms animation finishes. Per the
          CSS spec, ANY element with a transform value other than the literal
          keyword `none` — yes, even a zero translateY — becomes the
          containing block for its `position: fixed` descendants. So every
          fixed-position bottom sheet/modal rendered inside a page (which all
          live inside this div) was being positioned relative to THIS div's
          box instead of the real viewport, clipping it short and letting the
          separately-rendered BottomNav (a true sibling outside this div, so
          unaffected) sit visually in front of/below it. `onAnimationEnd`
          strips the class the instant the fade-in finishes, so the lingering
          transform disappears and every fixed element goes back to being
          viewport-relative like normal, exactly as on the website. */}
      <div
        key={pathname}
        className={`page-fade-in ${!hideChrome ? 'pb-20' : ''}`}
        onAnimationEnd={(e) => e.currentTarget.classList.remove('page-fade-in')}
      >
        {children}
      </div>
      {!hideChrome && <DownloadToast />}
      {!hideChrome && <BottomNav />}
    </>
  )
}

export default function App() {
  useEffect(() => {
    cleanupStaleDownloads()
  }, [])

  return (
    <Routes>
      <Route path="/setup" element={<Setup />} />
      <Route
        path="/*"
        element={
          <RequireConfig>
            <ChromeForRoute>
              <Routes>
                <Route path="/" element={<Home />} />
                <Route path="/search" element={<Search />} />
                <Route path="/saved" element={<Saved />} />
                <Route path="/downloads" element={<Downloads />} />
                <Route path="/profile" element={<Profile />} />
                <Route path="/title/:type/:id" element={<Detail />} />
                <Route path="/watch/:type/:id" element={<Player />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </ChromeForRoute>
          </RequireConfig>
        }
      />
    </Routes>
  )
}
