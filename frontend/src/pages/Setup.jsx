import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  saveConfig,
  clearConfig,
  getManifest,
  DEFAULT_BACKEND_URL,
  getBotUsername,
  createSignupCode,
  getSignupStatus,
  saveProfile,
} from '../api'

const POLL_MS = 2500

export default function Setup() {
  const navigate = useNavigate()

  // "Sign up with Telegram" flow state
  const [phase, setPhase] = useState('idle') // idle | starting | waiting | expired | error
  const [deepLink, setDeepLink] = useState('')
  const [error, setError] = useState('')
  const pollRef = useRef(null)
  const codeRef = useRef(null)

  // Advanced/manual fallback (kept for testing — hidden by default)
  const [showManual, setShowManual] = useState(false)
  const [backendUrl, setBackendUrl] = useState(DEFAULT_BACKEND_URL)
  const [token, setToken] = useState('')
  const [manualError, setManualError] = useState('')
  const [checking, setChecking] = useState(false)

  useEffect(() => () => stopPolling(), [])

  function stopPolling() {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }

  async function finishSignup(status) {
    stopPolling()
    saveConfig({ backendUrl: DEFAULT_BACKEND_URL, token: status.token })
    saveProfile({
      userId: status.user_id,
      name: status.name,
      username: status.username,
      hasPhoto: status.has_photo,
    })
    navigate('/')
  }

  async function pollOnce(code) {
    try {
      const status = await getSignupStatus(code)
      if (status.state === 'verified') {
        await finishSignup(status)
      } else if (status.state === 'expired' || status.state === 'invalid') {
        stopPolling()
        setPhase('expired')
      }
      // 'pending' — keep waiting
    } catch {
      // transient network hiccup — just try again on the next tick
    }
  }

  async function startSignup() {
    setError('')
    setPhase('starting')
    try {
      const [username, code] = await Promise.all([getBotUsername(), createSignupCode()])
      codeRef.current = code
      setDeepLink(`https://t.me/${username}?start=su_${code}`)
      setPhase('waiting')
      stopPolling()
      pollRef.current = setInterval(() => pollOnce(code), POLL_MS)
    } catch {
      setPhase('error')
      setError("Backend se connect nahi ho paya. Thodi der baad phir try karo.")
    }
  }

  async function handleManualSubmit(e) {
    e.preventDefault()
    setManualError('')
    if (!backendUrl.trim() || !token.trim()) {
      setManualError('Dono fields zaroori hain — backend URL aur token.')
      return
    }
    setChecking(true)
    saveConfig({ backendUrl, token })
    try {
      await getManifest()
      navigate('/')
    } catch {
      clearConfig()
      setManualError("Backend se connect nahi ho paya. URL aur token check karo.")
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

        {!showManual ? (
          <div className="bg-reel-surface rounded-xl p-6 space-y-4 ring-1 ring-white/5 text-center">
            {phase === 'idle' || phase === 'error' ? (
              <>
                <p className="text-sm text-reel-muted">
                  Ek tap mein sign up karo — apna naam aur photo Telegram se seedha aa jaayega.
                </p>
                {error ? <p className="text-reel-rust text-xs">{error}</p> : null}
                <button
                  onClick={startSignup}
                  className="w-full bg-reel-gold text-reel-bg font-semibold rounded-lg py-3 text-sm hover:brightness-110 active:scale-[0.98] transition"
                >
                  Sign up with Telegram
                </button>
              </>
            ) : null}

            {phase === 'starting' ? (
              <p className="text-sm text-reel-muted py-4">Connecting…</p>
            ) : null}

            {phase === 'waiting' ? (
              <>
                <p className="text-sm text-reel-muted">
                  Telegram khol kar bot ko <b>Start</b> karo — hum yahin wait kar rahe hain.
                </p>
                <a
                  href={deepLink}
                  className="block w-full bg-reel-gold text-reel-bg font-semibold rounded-lg py-3 text-sm hover:brightness-110 active:scale-[0.98] transition"
                >
                  Open Telegram
                </a>
                <p className="text-xs text-reel-muted animate-pulse">Waiting for confirmation…</p>
              </>
            ) : null}

            {phase === 'expired' ? (
              <>
                <p className="text-reel-rust text-xs">Yeh link expire ho gaya. Dubara try karo.</p>
                <button
                  onClick={startSignup}
                  className="w-full bg-reel-gold text-reel-bg font-semibold rounded-lg py-3 text-sm hover:brightness-110 active:scale-[0.98] transition"
                >
                  Try again
                </button>
              </>
            ) : null}
          </div>
        ) : (
          <form onSubmit={handleManualSubmit} className="bg-reel-surface rounded-xl p-6 space-y-4 ring-1 ring-white/5">
            <div>
              <label className="block text-xs font-medium text-reel-muted mb-1">Backend URL (Railway)</label>
              <input
                type="url"
                autoFocus
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

            {manualError ? <p className="text-reel-rust text-xs">{manualError}</p> : null}

            <button
              type="submit"
              disabled={checking}
              className="w-full bg-reel-gold text-reel-bg font-semibold rounded-lg py-2.5 text-sm hover:brightness-110 active:scale-[0.98] transition disabled:opacity-60"
            >
              {checking ? 'Connecting…' : 'Continue'}
            </button>
          </form>
        )}

        <p className="text-center text-xs text-reel-muted mt-4">
          <button className="underline underline-offset-2" onClick={() => setShowManual((v) => !v)}>
            {showManual ? 'Back to sign up' : 'Advanced: enter backend URL/token manually'}
          </button>
        </p>
      </div>
    </div>
  )
}
