import { clearConfig, clearProfile, getProfile, avatarUrl, isVerified } from '../api'
import { useNavigate } from 'react-router-dom'
import { useSavedList } from '../lib/savedStore'
import { useDownloadsList } from '../lib/downloadsStore'
import TelegramSignup from '../components/TelegramSignup'
import LanguagePicker from '../components/LanguagePicker'
import { useLanguage } from '../i18n/LanguageContext'

export default function Profile() {
  const navigate = useNavigate()
  const saved = useSavedList()
  const downloads = useDownloadsList()
  const { t } = useLanguage()
  const doneDownloads = downloads.filter((d) => d.status === 'done').length
  const profile = getProfile()
  const verified = isVerified()
  const displayName = profile?.name || t('guest')
  const handle = profile?.username ? `@${profile.username}` : null

  function handleLogout() {
    clearConfig()
    clearProfile()
    // BUG FIX (user ask): logout ke baad ab poore app ko force-signup screen
    // par nahi bhejna — bas isi Profile page par wapas "Verify" card dikhna
    // chahiye, aur Home/Detail/Player khud-ba-khud locked ho jaayenge
    // (dekho VerifyGate — sab isVerified() par based hain).
    navigate('/profile', { replace: true })
  }

  // FEATURE (user ask: "app bina login ke khul jaaye, Profile se hi verify
  // ho — verify hone tak Home/Detail/Player locked rahein, Saved/Downloads
  // hamesha khule rahein"): agar abhi tak verify nahi hua, Profile ka poora
  // page hi wahi "Sign up with Telegram" card ban jaata hai (bilkul jaisa
  // pehle app-launch par Setup.jsx mein dikhta tha) — verify hote hi yeh
  // khud reload ho kar normal profile view dikha dega. Language selector
  // yahan bhi rehta hai taaki verify se pehle bhi user apni pasand ki
  // bhasha choose kar sake.
  if (!verified) {
    return (
      <div className="max-w-6xl mx-auto py-8 px-4 sm:px-6">
        <div className="text-center mb-6">
          <div className="w-16 h-16 mx-auto mb-3 rounded-full bg-reel-surface2 flex items-center justify-center text-reel-muted">
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="8" r="4" /><path d="M4 20c0-3.9 3.6-7 8-7s8 3.1 8 7" /></svg>
          </div>
          <h1 className="font-display text-xl text-reel-ink">{t('profile_verify_title')}</h1>
          <p className="text-reel-muted text-sm mt-1">
            {t('profile_verify_subtitle')}
          </p>
        </div>
        <TelegramSignup onDone={() => navigate('/')} />
        <div className="max-w-md mx-auto mt-8">
          <LanguagePicker />
        </div>
      </div>
    )
  }

  return (
    <div className="max-w-6xl mx-auto py-8 px-4 sm:px-6">
      <div className="flex flex-col items-center text-center mb-8">
        <div className="w-20 h-20 rounded-full bg-reel-surface2 ring-2 ring-reel-gold/60 flex items-center justify-center text-reel-gold mb-3 overflow-hidden">
          {profile?.hasPhoto && profile?.userId ? (
            <img src={avatarUrl(profile.userId)} alt={displayName} className="w-full h-full object-cover" />
          ) : (
            <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="8" r="4" /><path d="M4 20c0-3.9 3.6-7 8-7s8 3.1 8 7" /></svg>
          )}
        </div>
        <h1 className="font-display text-xl text-reel-ink">{displayName}</h1>
        {handle ? <p className="text-reel-muted text-xs mt-1">{handle}</p> : null}
      </div>

      <div className="grid grid-cols-2 gap-3 mb-8">
        <div className="bg-reel-surface rounded-lg p-4 text-center ring-1 ring-white/5">
          <p className="font-display text-2xl text-reel-gold">{saved.length}</p>
          <p className="text-xs text-reel-muted mt-1">{t('profile_saved')}</p>
        </div>
        <div className="bg-reel-surface rounded-lg p-4 text-center ring-1 ring-white/5">
          <p className="font-display text-2xl text-reel-gold">{doneDownloads}</p>
          <p className="text-xs text-reel-muted mt-1">{t('profile_downloads')}</p>
        </div>
      </div>

      <div className="mb-8">
        <LanguagePicker />
      </div>

      <button
        onClick={handleLogout}
        className="w-full text-sm px-4 py-3 rounded-lg bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-[0.98] transition"
      >
        {t('profile_logout')}
      </button>
    </div>
  )
}
