import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  clearConfig,
  clearProfile,
  getProfile,
  avatarUrl,
  isVerified,
  getContinueWatching,
  removeWatchProgress,
  getMySubscription,
  getBotUsername,
} from '../api'
import { useSavedList } from '../lib/savedStore'
import { useDownloadsList, deleteDownload } from '../lib/downloadsStore'
import { pushToast } from '../lib/toastStore'
import { isNewUploadsEnabled, setNewUploadsEnabled } from '../lib/notificationsStore'
import { useThemeMode } from '../theme/ThemeContext'
import TelegramSignup from '../components/TelegramSignup'
import LanguagePicker from '../components/LanguagePicker'
import ThemeSheet from '../components/ThemeSheet'
import ContinueWatchingRail from '../components/ContinueWatchingRail'
import { useLanguage } from '../i18n/LanguageContext'

// ---------------------------------------------------------------------
// Small shared building blocks — every section below is one of these, so
// the whole page reads as one consistent "settings app" rather than a
// pile of one-off layouts.
// ---------------------------------------------------------------------

function SectionCard({ title, icon, right, children }) {
  return (
    <section className="mb-5 bg-reel-surface rounded-xl ring-1 ring-reel-ink/5 overflow-hidden">
      <div className="flex items-center justify-between gap-2 px-4 pt-4 pb-3">
        <h2 className="flex items-center gap-2 font-display text-[15px] font-semibold text-reel-ink">
          {icon ? <span className="text-reel-gold shrink-0">{icon}</span> : null}
          {title}
        </h2>
        {right}
      </div>
      <div className="px-4 pb-4">{children}</div>
    </section>
  )
}

function ToggleRow({ label, sub, checked, onChange }) {
  return (
    <div className="flex items-center justify-between gap-3 py-2.5">
      <div className="min-w-0 flex-1 pr-2">
        <p className="text-sm text-reel-ink font-medium">{label}</p>
        {sub ? <p className="text-[11px] text-reel-muted mt-0.5">{sub}</p> : null}
      </div>
      <button
        onClick={() => onChange(!checked)}
        aria-checked={checked}
        role="switch"
        className={`shrink-0 relative w-12 h-7 rounded-full transition-colors duration-200 ${checked ? 'bg-reel-gold' : 'bg-reel-ink/15'}`}
      >
        <span
          className={`absolute top-1 left-1 w-5 h-5 rounded-full bg-white shadow-md transition-transform duration-200 ${checked ? 'translate-x-5' : 'translate-x-0'}`}
        />
      </button>
    </div>
  )
}

function bytesToSize(bytes) {
  if (!bytes) return '0 MB'
  const gb = bytes / (1024 * 1024 * 1024)
  if (gb >= 1) return `${gb.toFixed(2)} GB`
  const mb = bytes / (1024 * 1024)
  return `${mb.toFixed(1)} MB`
}

// ---------------------------------------------------------------------
// PROFILE FEATURE (user ask: "profile section bahut khali khali hai —
// Watch History, Storage, Playback/Ambient settings, App version/About,
// My Plan, Notification preferences, Theme picker — sab add karo, aur
// professional dikhna chahiye"): every section below reuses data/
// components that already existed elsewhere in the app
// (ContinueWatchingRail, ThemeSheet, downloadsStore, notificationsStore)
// or a small new backend read added specifically for this
// (get_subscription_status — see database.py + stremio_routes.py's /my/*
// routes). My Ratings/My Comments/My Reports were later removed on user
// request ("koi kaam ka nahi hai") — see getMySubscription-only fetch
// below; the corresponding backend /my/ratings, /my/comments, /my/reports
// routes and get_user_ratings/get_user_comments/get_user_reports still
// exist server-side and are harmless to leave, just unused by this page
// now.
// ---------------------------------------------------------------------
export default function Profile() {
  const navigate = useNavigate()
  const saved = useSavedList()
  const downloads = useDownloadsList()
  const { t } = useLanguage()
  const { themeId, themes, patternId, patterns } = useThemeMode()
  const doneDownloads = downloads.filter((d) => d.status === 'done')
  const profile = getProfile()
  const verified = isVerified()
  const displayName = profile?.name || t('guest')
  const handle = profile?.username ? `@${profile.username}` : null

  const [continueWatching, setContinueWatching] = useState(null)
  const [subscription, setSubscription] = useState(null)
  const [themeSheetOpen, setThemeSheetOpen] = useState(false)
  const [notifEnabled, setNotifEnabled] = useState(() => isNewUploadsEnabled())

  // Same localStorage keys/semantics as Player.jsx's own toggles — this is
  // just a second control surface over the exact same settings, so
  // whichever screen the user touches last wins, no separate state to
  // keep in sync.
  const [autoplay, setAutoplayState] = useState(() => {
    try {
      return localStorage.getItem('suhani-screen:autoplay') !== 'off'
    } catch {
      return true
    }
  })
  const [ambientMode, setAmbientModeState] = useState(() => {
    try {
      return localStorage.getItem('suhani-screen:ambient') !== 'off'
    } catch {
      return true
    }
  })

  function toggleAutoplay(next) {
    setAutoplayState(next)
    try {
      localStorage.setItem('suhani-screen:autoplay', next ? 'on' : 'off')
    } catch {
      /* ignore storage failures */
    }
  }

  function toggleAmbient(next) {
    setAmbientModeState(next)
    try {
      localStorage.setItem('suhani-screen:ambient', next ? 'on' : 'off')
    } catch {
      /* ignore storage failures */
    }
  }

  function toggleNotif(next) {
    setNotifEnabled(next)
    setNewUploadsEnabled(next)
  }

  useEffect(() => {
    if (!verified) return
    let cancelled = false
    getContinueWatching().then((v) => !cancelled && setContinueWatching(v)).catch(() => !cancelled && setContinueWatching([]))
    getMySubscription().then((v) => !cancelled && setSubscription(v)).catch(() => !cancelled && setSubscription({ enabled: false }))
    return () => {
      cancelled = true
    }
  }, [verified])

  function removeContinueWatchingItem(item) {
    setContinueWatching((prev) => (prev || []).filter((it) => it.k !== item.k))
    removeWatchProgress(item.media_id, item.episode_id).catch(() => {})
  }

  async function handleClearDownloads() {
    if (!doneDownloads.length) return
    if (!window.confirm(t('profile_clear_downloads_confirm'))) return
    for (const d of doneDownloads) {
      await deleteDownload(d.id).catch(() => {})
    }
    pushToast(t('profile_downloads') + ' ' + t('remove'))
  }

  async function handlePlanCta() {
    try {
      const username = await getBotUsername()
      window.open(`https://t.me/${username}?start=status`, '_blank', 'noopener,noreferrer')
    } catch {
      pushToast('Could not reach the bot — try again in a moment.', 'error')
    }
  }

  async function handleSupport() {
    try {
      const username = await getBotUsername()
      window.open(`https://t.me/${username}`, '_blank', 'noopener,noreferrer')
    } catch {
      pushToast('Could not reach the bot — try again in a moment.', 'error')
    }
  }

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
  // page hi wahi "Sign up with Telegram" card ban jaata hai.
  if (!verified) {
    return (
      <div className="max-w-6xl mx-auto py-8 px-4 sm:px-6">
        <div className="text-center mb-6">
          <div className="w-20 h-20 mx-auto mb-3 rounded-full bg-gradient-to-br from-reel-gold/25 via-reel-surface2 to-reel-rust/20 ring-1 ring-reel-gold/25 flex items-center justify-center text-reel-gold">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="8" r="4" /><path d="M4 20c0-3.9 3.6-7 8-7s8 3.1 8 7" /></svg>
          </div>
          <h1 className="font-display text-xl text-reel-ink mt-1">{t('profile_verify_title')}</h1>
        </div>
        <TelegramSignup onDone={() => navigate('/')} />
        <div className="max-w-md mx-auto mt-8">
          <LanguagePicker />
        </div>
      </div>
    )
  }

  const currentTheme = themes.find((th) => th.id === themeId)
  const currentPattern = patterns.find((p) => p.id === patternId)
  const totalStorage = doneDownloads.reduce((sum, d) => sum + (d.sizeBytes || 0), 0)

  return (
    <div className="max-w-2xl mx-auto py-5 sm:py-6 px-4 sm:px-6">
      {/* ---- Header ----
          REDESIGN (user ask: "profile section ka design bekar/ajeeb hai,
          professional aur stylish banao — YouTube ke profile page se
          inspiration lo"): banner-style header — gradient cover strip
          (theme-driven, so it still reflows with every reel-* theme
          swap), avatar overlapping the bottom edge, a gold verified
          checkmark badge on the avatar itself, and an active-plan pill
          under the handle. FOLLOW-UP FIX (user ask: "naam aadha kata hua
          dikh raha hai"): name+handle were sitting in the same flex row
          as the avatar, right where the row overlapped the banner's
          bottom edge — that's what was clipping the top of the letters.
          Moved name+handle into their own block, stacked clearly below
          the avatar with a plain margin-top, so they never share any
          space with the banner at all. */}
      <div className="relative mb-5">
        <div className="h-28 sm:h-36 rounded-2xl overflow-hidden relative bg-gradient-to-br from-reel-gold/25 via-reel-surface2 to-reel-rust/20">
          <div
            className="absolute inset-0 opacity-[0.12] text-reel-ink"
            style={{ backgroundImage: 'radial-gradient(currentColor 1px, transparent 1px)', backgroundSize: '18px 18px' }}
          />
          <div className="absolute inset-0 bg-gradient-to-t from-reel-bg via-reel-bg/10 to-transparent" />
        </div>

        <div className="px-1 -mt-10 sm:-mt-12 relative">
          <div className="relative inline-block">
            <div className="w-20 h-20 sm:w-24 sm:h-24 rounded-full ring-4 ring-reel-bg bg-reel-surface2 shadow-lg overflow-hidden flex items-center justify-center text-reel-gold">
              {profile?.hasPhoto && profile?.userId ? (
                <img src={avatarUrl(profile.userId)} alt={displayName} className="w-full h-full object-cover" />
              ) : (
                <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="8" r="4" /><path d="M4 20c0-3.9 3.6-7 8-7s8 3.1 8 7" /></svg>
              )}
            </div>
            {verified ? (
              <span className="absolute bottom-0.5 right-0.5 w-5 h-5 sm:w-6 sm:h-6 rounded-full bg-reel-gold ring-2 ring-reel-bg flex items-center justify-center">
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#0B0B12" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
              </span>
            ) : null}
          </div>

          <div className="mt-3">
            <h1 className="font-display text-lg sm:text-xl font-bold text-reel-ink truncate">{displayName}</h1>
            {handle ? <p className="text-reel-muted text-xs mt-0.5 truncate">{handle}</p> : null}
          </div>

          {subscription?.active ? (
            <span className="inline-flex items-center gap-1.5 mt-3 text-[10px] font-bold tracking-[0.08em] uppercase text-reel-gold bg-reel-gold/10 ring-1 ring-reel-gold/25 px-2.5 py-1 rounded-full">
              <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l2.9 6.6L22 9.3l-5 4.9 1.2 7-6.2-3.4L5.8 21.2 7 14.2 2 9.3l7.1-.7L12 2z" /></svg>
              {t('profile_plan_active')}
            </span>
          ) : null}
        </div>
      </div>

      {/* ---- History ----
          MOVED HERE (user ask: "save/downloads button ke upar history add
          karo, jaise YouTube ke profile page mein hota hai"): this is the
          same Continue Watching rail/data that used to sit lower on the
          page (near My Plan) — just relocated above the stats bar and
          labelled "History" here instead of "Continue Watching", to match
          the YouTube reference screenshot. Same items/data, no new
          backend call. */}
      {continueWatching && continueWatching.length ? (
        <div className="mb-1 -mx-4 sm:mx-0">
          <ContinueWatchingRail items={continueWatching} onRemove={removeContinueWatchingItem} title={t('profile_history')} />
        </div>
      ) : null}

      {/* ---- Stats ----
          My Ratings/My Comments/My Reports removed on user request, so the
          stat strip now only carries the two counts that still have a
          section on the page (Saved, Downloads). */}
      <div className="flex bg-reel-surface rounded-xl ring-1 ring-reel-ink/5 mb-6 overflow-hidden">
        {[
          [saved.length, t('profile_saved')],
          [doneDownloads.length, t('profile_downloads')],
        ].map(([value, label], i) => (
          <div key={label} className={`flex-1 text-center py-3.5 px-1 ${i > 0 ? 'border-l border-reel-ink/5' : ''}`}>
            <p className="font-display text-base sm:text-lg font-bold text-reel-gold">{value}</p>
            <p className="text-[10px] text-reel-muted mt-0.5 leading-tight">{label}</p>
          </div>
        ))}
      </div>

      {/* ---- My Plan ---- */}
      {subscription?.enabled ? (
        <SectionCard
          title={t('profile_my_plan')}
          icon={
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="2" y="5" width="20" height="14" rx="2" /><line x1="2" y1="10" x2="22" y2="10" /></svg>
          }
        >
          <div className="flex items-center justify-between gap-3">
            <div>
              {subscription.active ? (
                <>
                  <p className="text-sm text-reel-ink font-medium flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-green-500 inline-block" />
                    {t('profile_plan_active')}
                  </p>
                  <p className="text-[11px] text-reel-muted mt-0.5">
                    {subscription.days_left != null ? `${subscription.days_left} ${t('profile_plan_days_left')}` : ''}
                  </p>
                </>
              ) : (
                <p className="text-sm text-reel-muted font-medium">{t('profile_plan_inactive')}</p>
              )}
            </div>
            <button
              onClick={handlePlanCta}
              className="text-xs px-4 py-2 rounded-lg bg-reel-gold text-reel-bg font-semibold active:scale-95 transition shrink-0"
            >
              {subscription.active ? t('profile_plan_renew') : t('profile_plan_subscribe')}
            </button>
          </div>
        </SectionCard>
      ) : null}

      {/* ---- Storage ---- */}
      <SectionCard
        title={t('profile_storage')}
        icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><ellipse cx="12" cy="5" rx="9" ry="3" /><path d="M3 5v14c0 1.7 4 3 9 3s9-1.3 9-3V5" /><path d="M3 12c0 1.7 4 3 9 3s9-1.3 9-3" /></svg>}
      >
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm text-reel-ink font-medium">{bytesToSize(totalStorage)}</p>
            <p className="text-[11px] text-reel-muted mt-0.5">{t('profile_storage_used')}</p>
          </div>
          <button
            onClick={handleClearDownloads}
            disabled={!doneDownloads.length}
            className="text-xs px-3.5 py-2 rounded-lg bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-95 transition disabled:opacity-40"
          >
            {t('profile_clear_downloads')}
          </button>
        </div>
      </SectionCard>

      {/* ---- Playback Settings ---- */}
      <SectionCard
        title={t('profile_playback_settings')}
        icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polygon points="5 3 19 12 5 21 5 3" /></svg>}
      >
        <ToggleRow label={t('profile_autoplay')} sub={t('profile_autoplay_sub')} checked={autoplay} onChange={toggleAutoplay} />
        <div className="h-px bg-reel-ink/5" />
        <ToggleRow label={t('profile_ambient_mode')} sub={t('profile_ambient_mode_sub')} checked={ambientMode} onChange={toggleAmbient} />
      </SectionCard>

      {/* ---- Appearance ---- */}
      <SectionCard
        title={t('profile_appearance')}
        icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="10" /><path d="M12 2a10 10 0 0 0 0 20 3 3 0 0 0 0-6 2 2 0 0 1 0-4 3 3 0 0 0 0-6" /></svg>}
      >
        <button
          onClick={() => setThemeSheetOpen(true)}
          className="w-full flex items-center justify-between gap-3 py-1 active:scale-[0.99] transition"
        >
          <div className="flex items-center gap-2.5">
            <span className="shrink-0 w-8 h-8 rounded-full ring-1 ring-reel-ink/10 overflow-hidden grid grid-cols-2 grid-rows-2">
              {currentTheme ? (
                <>
                  <span style={{ background: currentTheme.colors.bg }} />
                  <span style={{ background: currentTheme.colors.gold }} />
                  <span style={{ background: currentTheme.colors.surface2 }} />
                  <span style={{ background: currentTheme.colors.rust }} />
                </>
              ) : null}
            </span>
            <span className="text-left">
              <span className="block text-sm text-reel-ink font-medium">{t('profile_theme')}</span>
              <span className="block text-[11px] text-reel-muted">{currentTheme?.name}{currentPattern && currentPattern.id !== 'none' ? ` · ${currentPattern.name}` : ''}</span>
            </span>
          </div>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className="text-reel-muted shrink-0"><path d="m9 18 6-6-6-6" /></svg>
        </button>
      </SectionCard>
      <ThemeSheet open={themeSheetOpen} onClose={() => setThemeSheetOpen(false)} />

      {/* ---- Notifications ---- */}
      <SectionCard
        title={t('profile_notifications')}
        icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" /><path d="M13.7 21a2 2 0 0 1-3.4 0" /></svg>}
      >
        <ToggleRow label={t('profile_notif_new_uploads')} sub={t('profile_notif_new_uploads_sub')} checked={notifEnabled} onChange={toggleNotif} />
      </SectionCard>

      {/* ---- Language ---- */}
      <SectionCard
        title={t('profile_language')}
        icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="10" /><line x1="2" y1="12" x2="22" y2="12" /><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" /></svg>}
      >
        <LanguagePicker hideLabel />
      </SectionCard>

      {/* ---- About ---- */}
      <SectionCard
        title={t('profile_about')}
        icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="10" /><line x1="12" y1="16" x2="12" y2="12" /><line x1="12" y1="8" x2="12.01" y2="8" /></svg>}
      >
        <div className="flex items-center justify-between py-1.5">
          <span className="text-sm text-reel-ink">{t('profile_app_version')}</span>
          <span className="text-xs text-reel-muted">v1.0.0</span>
        </div>
        <div className="h-px bg-reel-ink/5" />
        <button onClick={handleSupport} className="w-full flex items-center justify-between py-2.5 active:scale-[0.99] transition">
          <span className="text-sm text-reel-ink">{t('profile_support')}</span>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className="text-reel-muted"><path d="m9 18 6-6-6-6" /></svg>
        </button>
      </SectionCard>

      <button
        onClick={handleLogout}
        className="w-full text-sm px-4 py-3 rounded-lg bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-[0.98] transition mt-2"
      >
        {t('profile_logout')}
      </button>
    </div>
  )
}
