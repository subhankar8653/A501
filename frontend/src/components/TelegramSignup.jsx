import { useEffect, useRef, useState } from 'react'
import {
  saveConfig,
  DEFAULT_BACKEND_URL,
  getBotUsername,
  createSignupCode,
  getSignupStatus,
  saveProfile,
} from '../api'
import { useLanguage } from '../i18n/LanguageContext'

const POLL_MS = 2500

// FEATURE (user ask 1: "sign up ab app-launch par zabardasti nahi, Profile
// page se 'Verify' button dabaane par ho"): yeh wahi "Sign up with Telegram"
// flow hai, ab reusable component — Setup.jsx aur Profile.jsx dono isi ko
// use karte hain taaki logic ek hi jagah rahe.
//
// FEATURE (user ask 2: "double step mat karo — 'Verify with Telegram' dabate
// hi seedha Telegram khul jaana chahiye, alag se 'Open Telegram' button
// dabaana na pade"): pehle button dabane par sirf "Connecting…" dikhta,
// deep-link taiyar hote hi ek ALAG "Open Telegram" button dikhta jise phir
// se dabaana padta — do taps lagte the. Ab ek hi tap mein: button dabate hi
// deep-link taiyar hote hi turant khud-ba-khud (window.location.href se)
// Telegram khol diya jaata hai — koi doosra tap nahi chahiye. Agar kisi
// wajah se auto-open na ho (rare), ek chhota fallback link neeche rehta hai.
//
// FEATURE (user ask 3: "itni saari details/manual-entry option user ko mat
// dikhao"): intro paragraph aur "Advanced: enter backend URL/token manually"
// wala poora manual-entry raasta hata diya — sirf icon-jaisa clean single
// button reh gaya hai.
export default function TelegramSignup({ onDone }) {
  const { t } = useLanguage()
  const [phase, setPhase] = useState('idle') // idle | starting | waiting | expired | error
  const [deepLink, setDeepLink] = useState('')
  const [error, setError] = useState('')
  const pollRef = useRef(null)

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
      const link = `https://t.me/${username}?start=su_${code}`
      setDeepLink(link)
      setPhase('waiting')
      stopPolling()
      pollRef.current = setInterval(() => pollOnce(code), POLL_MS)
      // Auto-open — this is the fix: no second tap needed, Telegram opens
      // the moment the deep-link is ready.
      window.location.href = link
    } catch {
      setPhase('error')
      setError("Backend se connect nahi ho paya. Thodi der baad phir try karo.")
    }
  }

  return (
    <div className="w-full max-w-md mx-auto">
      <div className="bg-reel-surface rounded-xl p-6 space-y-4 ring-1 ring-reel-ink/5 text-center">
        {phase === 'idle' || phase === 'error' ? (
          <>
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
            <p className="text-xs text-reel-muted animate-pulse">{t('signup_waiting_hint')}</p>
            <a href={deepLink} className="inline-block text-xs text-reel-gold underline underline-offset-2">
              {t('signup_open_telegram')}
            </a>
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
    </div>
  )
}
