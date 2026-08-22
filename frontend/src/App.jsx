import { useEffect } from 'react'
import { Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { getConfig } from './api'
import { cleanupStaleDownloads } from './lib/downloadsStore'
import Navbar from './components/Navbar'
import BottomNav from './components/BottomNav'
import DownloadToast from './components/DownloadToast'
import ConnectionOverlay from './components/ConnectionOverlay'
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
  const navigate = useNavigate()

  useEffect(() => {
    cleanupStaleDownloads()
  }, [])

  // BUG FIX (user report: "PiP expand karne par watch page ki jagah Home par
  // khul jaata hai"): jab chhote inline player se native (real) PiP shuru hoti
  // hai, Android side is watch page ko WebView history mein PEECHE (Detail
  // page par) navigate kar deta hai — taaki PiP floating window ke peeche
  // wahi "clean" pichli page dikhe, is page ka khaali video-hole nahi. Expand
  // karte waqt native pehle sirf `webView.goForward()` (browser ki forward-
  // history) se wapas is watch page par aata tha.
  //
  // Root cause: PiP floating rehte waqt user WebView mein normally kahin bhi
  // ghoom sakta hai (Home tab, koi doosra title, waghera) — koi bhi aisi
  // navigation (React Router ka har naya `navigate()`/pushState) browser ki
  // "forward" history ko turant discard kar deta hai. Matlab agar PiP ke
  // dauraan user Home par gaya, is watch page ki forward-entry hamesha ke
  // liye gayab ho jaati — expand par `goForward()` ab kuch nahi karta (ya
  // kisi aur galat page par le jaata), aur user "Home par hi khula reh"
  // jaata, bilkul jaisa report hua.
  //
  // Fix: native ab exact watch-page path yaad rakhta hai aur seedha isi
  // bridge function ko call karta hai — koi bhi browser back/forward history
  // state par bharosa kiye bina, direct client-side navigate(). Isse expand
  // hamesha bilkul wahi jagah wapas le jaata hai jahan se PiP shuru hui thi,
  // chahe beech mein user ne WebView mein kuch bhi dekha/navigate kiya ho.
  useEffect(() => {
    window.__suhaniPipReturnTo = (path) => {
      if (typeof path === 'string' && path) navigate(path)
    }
    return () => {
      delete window.__suhaniPipReturnTo
    }
  }, [navigate])

  return (
    <>
      <ConnectionOverlay />
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
    </>
  )
}
