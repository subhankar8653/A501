import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  clearConfig,
  clearProfile,
  getProfile,
  avatarUrl,
  isVerified,
  getContinueWatching,
  removeWatchProgress,
  getMyRatings,
  getMyComments,
  getMyReports,
  getMySubscription,
  deleteComment,
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
    <div className="flex items-center justify-between gap-3 py-2">
      <div className="min-w-0">
        <p className="text-sm text-reel-ink font-medium">{label}</p>
        {sub ? <p className="text-[11px] text-reel-muted mt-0.5">{sub}</p> : null}
      </div>
      <button
        onClick={() => onChange(!checked)}
        aria-checked={checked}
        role="switch"
        className={`shrink-0 w-11 h-6 rounded-full relative transition-colors ${checked ? 'bg-reel-gold' : 'bg-reel-ink/15'}`}
      >
        <span
          className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform ${checked ? 'translate-x-[22px]' : 'translate-x-0.5'}`}
        />
      </button>
    </div>
  )
}

function StarRow({ value }) {
  return (
    <span className="inline-flex items-center gap-0.5 text-reel-gold shrink-0">
      {[1, 2, 3, 4, 5].map((n) => (
        <svg key={n} width="11" height="11" viewBox="0 0 24 24" fill={n <= value ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.5">
          <path d="M12 2l2.9 6.6 7.1.6-5.4 4.7 1.6 7-6.2-3.8-6.2 3.8 1.6-7L2 9.2l7.1-.6z" />
        </svg>
      ))}
    </span>
  )
}

function EmptyRow({ text }) {
  return <p className="text-xs text-reel-muted py-3 text-center">{text}</p>
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
// Watch History, My Ratings, My Reports, My Comments, Storage,
// Playback/Ambient settings, App version/About, My Plan, Notification
// preferences, Theme picker — sab add karo, aur professional dikhna
// chahiye"): every section below reuses data/components that already
// existed elsewhere in the app (ContinueWatchingRail, ThemeSheet,
// downloadsStore, notificationsStore) or a small new backend read added
// specifically for this (get_user_ratings/get_user_comments/
// get_user_reports/get_subscription_status — see database.py +
// stremio_routes.py's new /my/* routes). Nothing here is placeholder UI —
// every number and every list is real user data.
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
  const [ratings, setRatings] = useState(null)
  const [myComments, setMyComments] = useState(null)
  const [reports, setReports] = useState(null)
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
    getMyRatings().then((v) => !cancelled && setRatings(v)).catch(() => !cancelled && setRatings([]))
    getMyComments().then((v) => !cancelled && setMyComments(v)).catch(() => !cancelled && setMyComments([]))
    getMyReports().then((v) => !cancelled && setReports(v)).catch(() => !cancelled && setReports([]))
    getMySubscription().then((v) => !cancelled && setSubscription(v)).catch(() => !cancelled && setSubscription({ enabled: false }))
    return () => {
      cancelled = true
    }
  }, [verified])

  function removeContinueWatchingItem(item) {
    setContinueWatching((prev) => (prev || []).filter((it) => it.k !== item.k))
    removeWatchProgress(item.media_id, item.episode_id).catch(() => {})
  }

  async function removeMyComment(c) {
    setMyComments((prev) => (prev || []).filter((x) => !(x.media_id === c.media_id && x.ts === c.ts)))
    try {
      await deleteComment(c.media_type, c.media_id, c.ts)
    } catch {
      /* already optimistically removed */
    }
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
          <div className="w-16 h-16 mx-auto mb-3 rounded-full bg-reel-surface2 flex items-center justify-center text-reel-muted">
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="8" r="4" /><path d="M4 20c0-3.9 3.6-7 8-7s8 3.1 8 7" /></svg>
          </div>
          <h1 className="font-display text-xl text-reel-ink">{t('profile_verify_title')}</h1>
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
    <div className="max-w-2xl mx-auto py-8 px-4 sm:px-6">
      {/* ---- Header ---- */}
      <div className="flex flex-col items-center text-center mb-6">
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

      {/* ---- Stats ---- */}
      <div className="grid grid-cols-4 gap-2 mb-6">
        {[
          [saved.length, t('profile_saved')],
          [doneDownloads.length, t('profile_downloads')],
          [ratings?.length ?? '—', t('profile_ratings')],
          [reports?.length ?? '—', t('profile_reports')],
        ].map(([value, label]) => (
          <div key={label} className="bg-reel-surface rounded-lg py-3 px-1 text-center ring-1 ring-reel-ink/5">
            <p className="font-display text-lg text-reel-gold">{value}</p>
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

      {/* ---- Continue Watching ---- */}
      {continueWatching && continueWatching.length ? (
        <div className="mb-1 -mx-4 sm:mx-0">
          <ContinueWatchingRail items={continueWatching} onRemove={removeContinueWatchingItem} />
        </div>
      ) : null}

      {/* ---- My Ratings ---- */}
      <SectionCard
        title={t('profile_my_ratings')}
        icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M12 2l2.9 6.6 7.1.6-5.4 4.7 1.6 7-6.2-3.8-6.2 3.8 1.6-7L2 9.2l7.1-.6z" /></svg>}
      >
        {ratings === null ? (
          <div className="space-y-2">
            {[0, 1].map((i) => <div key={i} className="h-12 rounded-lg bg-reel-surface2 animate-pulse" />)}
          </div>
        ) : ratings.length === 0 ? (
          <EmptyRow text={t('profile_my_ratings_empty')} />
        ) : (
          <div className="space-y-1">
            {ratings.map((r) => (
              <Link
                key={`${r.media_type}:${r.media_id}`}
                to={`/title/${r.media_type}/${encodeURIComponent(r.media_id)}`}
                className="flex items-center gap-3 py-1.5 rounded-lg hover:bg-reel-ink/[0.04] active:scale-[0.99] transition"
              >
                <span className="shrink-0 w-8 h-11 rounded-md overflow-hidden bg-reel-surface2 ring-1 ring-reel-ink/10">
                  {r.poster ? <img src={r.poster} alt="" loading="lazy" className="w-full h-full object-cover" /> : null}
                </span>
                <span className="min-w-0 flex-1 text-sm text-reel-ink truncate">{r.title || r.media_id}</span>
                <StarRow value={r.mine || 0} />
              </Link>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- My Comments ---- */}
      <SectionCard
        title={t('profile_my_comments')}
        icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" /></svg>}
      >
        {myComments === null ? (
          <div className="space-y-2">
            {[0, 1].map((i) => <div key={i} className="h-12 rounded-lg bg-reel-surface2 animate-pulse" />)}
          </div>
        ) : myComments.length === 0 ? (
          <EmptyRow text={t('profile_my_comments_empty')} />
        ) : (
          <div className="space-y-2.5">
            {myComments.map((c) => (
              <div key={`${c.media_id}:${c.ts}`} className="flex items-start gap-3">
                <Link to={`/title/${c.media_type}/${encodeURIComponent(c.media_id)}`} className="shrink-0 w-8 h-11 rounded-md overflow-hidden bg-reel-surface2 ring-1 ring-reel-ink/10">
                  {c.poster ? <img src={c.poster} alt="" loading="lazy" className="w-full h-full object-cover" /> : null}
                </Link>
                <div className="min-w-0 flex-1">
                  <Link to={`/title/${c.media_type}/${encodeURIComponent(c.media_id)}`} className="text-xs font-medium text-reel-ink hover:text-reel-gold truncate block">
                    {c.title || c.media_id}
                  </Link>
                  <p className="text-sm text-reel-ink/85 break-words">{c.text}</p>
                  <button onClick={() => removeMyComment(c)} className="text-[11px] text-reel-muted hover:text-reel-rust mt-0.5">
                    {t('remove')}
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- My Reports ---- */}
      <SectionCard
        title={t('profile_my_reports')}
        icon={<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z" /><line x1="4" y1="22" x2="4" y2="15" /></svg>}
      >
        {reports === null ? (
          <div className="space-y-2">
            {[0, 1].map((i) => <div key={i} className="h-12 rounded-lg bg-reel-surface2 animate-pulse" />)}
          </div>
        ) : reports.length === 0 ? (
          <EmptyRow text={t('profile_my_reports_empty')} />
        ) : (
          <div className="space-y-1">
            {reports.map((r) => (
              <Link
                key={r._id}
                to={`/title/${r.media_type}/${encodeURIComponent(r.media_id)}`}
                className="flex items-center gap-3 py-1.5 rounded-lg hover:bg-reel-ink/[0.04] active:scale-[0.99] transition"
              >
                <span className="shrink-0 w-8 h-11 rounded-md overflow-hidden bg-reel-surface2 ring-1 ring-reel-ink/10">
                  {r.poster ? <img src={r.poster} alt="" loading="lazy" className="w-full h-full object-cover" /> : null}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm text-reel-ink truncate">{r.title || r.media_id}</span>
                  <span className="block text-[10px] text-reel-muted capitalize">{r.reason}</span>
                </span>
                <span className={`shrink-0 text-[10px] font-semibold px-2 py-1 rounded-full ${r.status === 'resolved' ? 'bg-green-500/15 text-green-500' : 'bg-reel-gold/15 text-reel-gold'}`}>
                  {r.status === 'resolved' ? t('profile_report_resolved') : t('profile_report_open')}
                </span>
              </Link>
            ))}
          </div>
        )}
      </SectionCard>

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
