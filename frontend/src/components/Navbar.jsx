import { useState, useEffect } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { getConfig, clearConfig } from '../api'
import { Search, LogOut, Menu, X } from 'lucide-react'

export default function Navbar() {
  const [scrolled, setScrolled] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()
  const cfg = getConfig()

  useEffect(() => {
    const handleScroll = () => setScrolled(window.scrollY > 50)
    window.addEventListener('scroll', handleScroll, { passive: true })
    return () => window.removeEventListener('scroll', handleScroll)
  }, [])

  const handleSearch = (e) => {
    e.preventDefault()
    if (searchQuery.trim()) {
      navigate(`/search?q=${encodeURIComponent(searchQuery.trim())}`)
      setSearchOpen(false)
      setSearchQuery('')
    }
  }

  const navLinks = [
    { to: '/', label: 'Home' },
    { to: '/search?q=anime', label: 'Anime' },
    { to: '/search?q=movies', label: 'Movies' },
    { to: '/search?q=series', label: 'Series' },
  ]

  return (
    <nav className={`fixed top-0 left-0 right-0 z-50 transition-all duration-500 ${
      scrolled ? 'bg-netflix-black/95 nav-blur' : 'bg-gradient-to-b from-black/80 to-transparent'
    }`}>
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-16">
          {/* Logo */}
          <Link to="/" className="flex items-center gap-2">
            <div className="w-8 h-8 bg-netflix-red rounded-md flex items-center justify-center">
              <span className="text-white font-bold text-lg">S</span>
            </div>
            <span className="text-xl font-bold tracking-tight text-white">
              Suhani<span className="text-netflix-red">Screen</span>
            </span>
          </Link>

          {/* Desktop Nav */}
          <div className="hidden md:flex items-center gap-6">
            {navLinks.map(link => (
              <Link
                key={link.to}
                to={link.to}
                className={`text-sm font-medium transition-colors ${
                  location.pathname === link.to 
                    ? 'text-white' 
                    : 'text-netflix-lightgray hover:text-white'
                }`}
              >
                {link.label}
              </Link>
            ))}
          </div>

          {/* Right Section */}
          <div className="flex items-center gap-4">
            {/* Search */}
            <div className={`flex items-center transition-all duration-300 ${searchOpen ? 'w-64' : 'w-auto'}`}>
              {searchOpen ? (
                <form onSubmit={handleSearch} className="relative w-full">
                  <input
                    autoFocus
                    type="text"
                    placeholder="Titles, people, genres"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className="w-full bg-black/60 border border-white/20 rounded-full py-2 pl-10 pr-4 text-sm text-white placeholder-gray-400 focus:outline-none focus:border-white/40"
                  />
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
                  <button 
                    type="button" 
                    onClick={() => { setSearchOpen(false); setSearchQuery('') }}
                    className="absolute right-3 top-1/2 -translate-y-1/2"
                  >
                    <X className="w-4 h-4 text-gray-400 hover:text-white" />
                  </button>
                </form>
              ) : (
                <button onClick={() => setSearchOpen(true)} className="p-2 hover:bg-white/10 rounded-full transition-colors">
                  <Search className="w-5 h-5 text-white" />
                </button>
              )}
            </div>

            {/* User / Logout */}
            {cfg && (
              <button 
                onClick={() => { clearConfig(); window.location.href = '/setup' }}
                className="hidden sm:flex items-center gap-2 text-sm text-netflix-lightgray hover:text-white transition-colors"
              >
                <LogOut className="w-4 h-4" />
                <span className="hidden lg:inline">Exit</span>
              </button>
            )}

            {/* Mobile Menu Button */}
            <button 
              className="md:hidden p-2 hover:bg-white/10 rounded-full"
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            >
              {mobileMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
            </button>
          </div>
        </div>
      </div>

      {/* Mobile Menu */}
      {mobileMenuOpen && (
        <div className="md:hidden bg-netflix-black/98 nav-blur border-t border-white/10 animate-fade-in">
          <div className="px-4 py-4 space-y-2">
            {navLinks.map(link => (
              <Link
                key={link.to}
                to={link.to}
                onClick={() => setMobileMenuOpen(false)}
                className="block py-2 text-sm font-medium text-netflix-lightgray hover:text-white"
              >
                {link.label}
              </Link>
            ))}
            {cfg && (
              <button 
                onClick={() => { clearConfig(); window.location.href = '/setup' }}
                className="flex items-center gap-2 py-2 text-sm text-netflix-red"
              >
                <LogOut className="w-4 h-4" />
                Sign Out
              </button>
            )}
          </div>
        </div>
      )}
    </nav>
  )
}
