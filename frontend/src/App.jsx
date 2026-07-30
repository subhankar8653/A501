import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { getConfig } from './api'
import Navbar from './components/Navbar'
import Setup from './pages/Setup'
import Home from './pages/Home'
import Search from './pages/Search'
import Detail from './pages/Detail'
import Player from './pages/Player'

function RequireConfig({ children }) {
  const cfg = getConfig()
  if (!cfg) return <Navigate to="/setup" replace />
  return children
}

function Layout({ children }) {
  const { pathname } = useLocation()
  const isFullscreen = pathname.startsWith('/watch/') || pathname.startsWith('/title/')

  return (
    <div className="min-h-screen bg-netflix-black">
      {!isFullscreen && <Navbar />}
      <main className={!isFullscreen ? 'pt-16' : ''}>
        {children}
      </main>
    </div>
  )
}

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/setup" element={<Setup />} />
        <Route path="/" element={<RequireConfig><Home /></RequireConfig>} />
        <Route path="/search" element={<RequireConfig><Search /></RequireConfig>} />
        <Route path="/title/:type/:id" element={<RequireConfig><Detail /></RequireConfig>} />
        <Route path="/watch/:type/:id" element={<RequireConfig><Player /></RequireConfig>} />
      </Routes>
    </Layout>
  )
}
