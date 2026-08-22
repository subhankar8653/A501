import { useEffect, useRef, useState } from 'react'
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
import { useLanguage } from '../i18n/LanguageContext'

const POLL_MS = 2500

// FEATURE (user ask: "sign up ab app-launch par zabardasti nahi, Profile
// page se 'Verify' button dabaane par ho — download/saved pehle jaisa hi
// hamesha khula rahe"): yeh wahi "Sign up with Telegram" flow hai jo pehle
// Setup.jsx mein tha (poll karke code verify hone ka wait), ab ek reusable
// component ke roop mein — Setup.jsx (agar kabhi seedha khula) aur
// Profile.jsx (naya "Verify" entry point) dono isi ko use karte hain, taaki
// logic ek hi jagah rahe.
export default function TelegramSignup({ onDone }) {
  const { t } = useLanguage()
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
    onDone?.()
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
      onDone?.()
    } catch {
      clearConfig()
      setManualError("Backend se connect nahi ho paya. URL aur token check karo.")
    } finally {
      setChecking(false)
    }
  }

  return (
    <div className="w-full max-w-md mx-auto">
      {!showManual ? (
        <div className="bg-reel-surface rounded-xl p-6 space-y-4 ring-1 ring-white/5 text-center">
          {phase === 'idle' || phase === 'error' ? (
            <>
              <p className="text-sm text-reel-muted">
                {t('signup_intro')}
              </p>
              {error ? <p className="text-reel-rust text-xs">{error}</p> : null}
              <button
                onClick={startSignup}
                className="w-full bg-reel-gold text-reel-bg font-semibold rounded-lg py-3 text-sm hover:brightness-110 active:scale-[0.98] transition"
              >
                {t('signup_button')}
              </button>
            </>
          ) : null}

          {phase === 'starting' ? (
            <p className="text-sm text-reel-muted py-4">{t('signup_connecting')}</p>
          ) : null}

          {phase === 'waiting' ? (
            <>
              <p className="text-sm text-reel-muted">
                {t('signup_waiting')}
              </p>
              <a
                href={deepLink}
                className="block w-full bg-reel-gold text-reel-bg font-semibold rounded-lg py-3 text-sm hover:brightness-110 active:scale-[0.98] transition"
              >
                {t('signup_open_telegram')}
              </a>
              <p className="text-xs text-reel-muted animate-pulse">{t('signup_waiting_hint')}</p>
            </>
          ) : null}

          {phase === 'expired' ? (
            <>
              <p className="text-reel-rust text-xs">{t('signup_expired')}</p>
              <button
                onClick={startSignup}
                className="w-full bg-reel-gold text-reel-bg font-semibold rounded-lg py-3 text-sm hover:brightness-110 active:scale-[0.98] transition"
              >
                {t('signup_try_again')}
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
            {checking ? t('signup_connecting') : 'Continue'}
          </button>
        </form>
      )}

      <p className="text-center text-xs text-reel-muted mt-4">
        <button className="underline underline-offset-2" onClick={() => setShowManual((v) => !v)}>
          {showManual ? t('signup_manual_toggle_hide') : t('signup_manual_toggle_show')}
        </button>
      </p>
    </div>
  )
}
