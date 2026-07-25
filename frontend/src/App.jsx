import { Routes, Route, Navigate } from 'react-router-dom'
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

export default function App() {
  return (
    <Routes>
      <Route path="/setup" element={<Setup />} />
      <Route
        path="/*"
        element={
          <RequireConfig>
            <Navbar />
            <Routes>
              <Route path="/" element={<Home />} />
              <Route path="/search" element={<Search />} />
              <Route path="/title/:type/:id" element={<Detail />} />
              <Route path="/watch/:type/:id" element={<Player />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </RequireConfig>
        }
      />
    </Routes>
  )
}
