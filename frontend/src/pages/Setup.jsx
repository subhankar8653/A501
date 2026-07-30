import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { saveConfig } from '../api'
import { Film, Server, Key, ArrowRight, CheckCircle, AlertCircle } from 'lucide-react'

export default function Setup() {
  const [backendUrl, setBackendUrl] = useState('')
  const [token, setToken] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const navigate = useNavigate()

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      const cleanUrl = backendUrl.replace(/\/+$/, '').trim()
      if (!cleanUrl || !token.trim()) throw new Error('Both fields are required')

      // Test connection
      const testRes = await fetch(`${cleanUrl}/stremio/${token.trim()}/manifest.json`)
      if (!testRes.ok) throw new Error('Invalid backend URL or token')

      saveConfig({ backendUrl: cleanUrl, token: token.trim() })
      navigate('/')
    } catch (err) {
      setError(err.message || 'Connection failed. Please check your details.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4 bg-[radial-gradient(ellipse_at_top,_var(--tw-gradient-stops))] from-netflix-dark via-netflix-black to-black">
      <div className="w-full max-w-md animate-scale-in">
        {/* Logo */}
        <div className="text-center mb-8">
          <div className="w-16 h-16 bg-netflix-red rounded-xl flex items-center justify-center mx-auto mb-4 shadow-lg shadow-red-900/30">
            <Film className="w-8 h-8 text-white" />
          </div>
          <h1 className="text-3xl font-bold text-white mb-2">
            Suhani<span className="text-netflix-red">Screen</span>
          </h1>
          <p className="text-netflix-lightgray text-sm">
            Your personal streaming library
          </p>
        </div>

        {/* Form Card */}
        <div className="bg-netflix-dark/80 backdrop-blur-xl border border-white/5 rounded-2xl p-6 sm:p-8 shadow-2xl">
          <form onSubmit={handleSubmit} className="space-y-5">
            {/* Backend URL */}
            <div>
              <label className="flex items-center gap-2 text-sm font-medium text-netflix-lightgray mb-2">
                <Server className="w-4 h-4" />
                Backend URL
              </label>
              <input
                type="url"
                required
                placeholder="https://your-app.railway.app"
                value={backendUrl}
                onChange={(e) => setBackendUrl(e.target.value)}
                className="w-full bg-netflix-black/60 border border-white/10 rounded-lg px-4 py-3 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-netflix-red/60 focus:ring-1 focus:ring-netflix-red/60 transition-all"
              />
              <p className="mt-1.5 text-xs text-netflix-gray">
                Your Railway / VPS deployment URL
              </p>
            </div>

            {/* Token */}
            <div>
              <label className="flex items-center gap-2 text-sm font-medium text-netflix-lightgray mb-2">
                <Key className="w-4 h-4" />
                Access Token
              </label>
              <input
                type="password"
                required
                placeholder="Paste your token here"
                value={token}
                onChange={(e) => setToken(e.target.value)}
                className="w-full bg-netflix-black/60 border border-white/10 rounded-lg px-4 py-3 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-netflix-red/60 focus:ring-1 focus:ring-netflix-red/60 transition-all"
              />
              <p className="mt-1.5 text-xs text-netflix-gray">
                From <code className="bg-white/10 px-1 py-0.5 rounded text-xs">/stremio/&lt;token&gt;/manifest.json</code>
              </p>
            </div>

            {/* Error */}
            {error && (
              <div className="flex items-center gap-2 text-sm text-red-400 bg-red-400/10 border border-red-400/20 rounded-lg px-4 py-3">
                <AlertCircle className="w-4 h-4 flex-shrink-0" />
                {error}
              </div>
            )}

            {/* Submit */}
            <button
              type="submit"
              disabled={loading}
              className="w-full flex items-center justify-center gap-2 bg-netflix-red hover:bg-red-700 disabled:bg-red-900/50 text-white font-semibold py-3 rounded-lg transition-all duration-200 disabled:cursor-not-allowed"
            >
              {loading ? (
                <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
              ) : (
                <>
                  Connect Library
                  <ArrowRight className="w-4 h-4" />
                </>
              )}
            </button>
          </form>

          {/* Success hint */}
          <div className="mt-6 pt-6 border-t border-white/5 flex items-start gap-3">
            <CheckCircle className="w-5 h-5 text-green-500 flex-shrink-0 mt-0.5" />
            <div className="text-xs text-netflix-gray space-y-1">
              <p className="text-netflix-lightgray font-medium">How to get your token:</p>
              <p>1. Open your Telegram bot</p>
              <p>2. Send <code className="text-white bg-white/10 px-1 rounded">/start</code></p>
              <p>3. Copy the token from the addon link</p>
            </div>
          </div>
        </div>

        {/* Footer */}
        <p className="text-center text-xs text-netflix-gray mt-8">
          Powered by Telegram-Stremio &bull; Self-hosted media server
        </p>
      </div>
    </div>
  )
}
