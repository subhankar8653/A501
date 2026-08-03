import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { saveConfig, getManifest } from '../api'

export default function Setup() {
  const navigate = useNavigate()
  const [backendUrl, setBackendUrl] = useState('')
  const [token, setToken] = useState('')
  const [error, setError] = useState('')
  const [checking, setChecking] = useState(false)

  async function handleSubmit(e) {
    e.preventDefault()
    setError('')
    if (!backendUrl.trim() || !token.trim()) {
      setError('Dono fields zaroori hain — backend URL aur token.')
      return
    }
    setChecking(true)
    saveConfig({ backendUrl, token })
    try {
      await getManifest()
      navigate('/')
    } catch {
      setError("Backend se connect nahi ho paya. URL aur token check karo — token bot ke /start se milta hai.")
    } finally {
      setChecking(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <h1 className="font-display text-4xl font-semibold text-reel-gold">Huka Tube</h1>
          <p className="text-reel-muted mt-2 text-sm">Your Telegram library, on the big screen.</p>
        </div>

        <form onSubmit={handleSubmit} className="bg-reel-surface rounded-xl p-6 space-y-4 ring-1 ring-white/5">
          <div>
            <label className="block text-xs font-medium text-reel-muted mb-1">Backend URL (Railway)</label>
            <input
              type="url"
              placeholder="https://your-app.up.railway.app"
              value={backendUrl}
              onChange={(e) => setBackendUrl(e.target.value)}
              className="w-full bg-reel-surface2 rounded-lg px-3 py-2 text-sm border border-white/5 focus:outline-none focus:ring-2 focus:ring-reel-gold/60"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-reel-muted mb-1">Access token</label>
            <input
              type="text"
              placeholder="Token bot se /start karke milega"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              className="w-full bg-reel-surface2 rounded-lg px-3 py-2 text-sm border border-white/5 focus:outline-none focus:ring-2 focus:ring-reel-gold/60"
            />
          </div>

          {error ? <p className="text-reel-rust text-xs">{error}</p> : null}

          <button
            type="submit"
            disabled={checking}
            className="w-full bg-reel-gold text-reel-bg font-semibold rounded-lg py-2.5 text-sm hover:brightness-110 active:scale-[0.98] transition disabled:opacity-60"
          >
            {checking ? 'Connecting…' : 'Continue'}
          </button>
        </form>

        <p className="text-center text-xs text-reel-muted mt-4">
          Token wahi hai jo tumhare Telegram-Stremio bot ke addon link mein use hota hai —
          <br />
          <code className="text-reel-ink/80">/stremio/&lt;token&gt;/manifest.json</code>
        </p>
      </div>
    </div>
  )
}
