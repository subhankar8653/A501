import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { useLanguage } from '../i18n/LanguageContext'
import { useUpdate } from '../lib/useUpdate'
import {
  applyBundle,
  dismissNotice,
  markBootOk,
  startApkDownload,
  startUpdateChecks,
} from '../lib/updateStore'

// Mounted once in App.jsx. Does three things:
//  1. tells the native shell "this UI booted fine" (OTA rollback guard),
//  2. starts the periodic update check,
//  3. shows the update popup (new APK) / "restart to get new features"
//     banner (OTA UI bundle that finished downloading).
// The same info also appears as a card inside the notification bell
// (see NotificationSheet.jsx).
export default function UpdatePrompt() {
  const { t } = useLanguage()
  const u = useUpdate()

  useEffect(() => {
    const id = window.setTimeout(markBootOk, 2500)
    startUpdateChecks()
    return () => window.clearTimeout(id)
  }, [])

  const apkForce = !!(u.apk && u.apk.force)
  const showApk = !!u.apk && (apkForce || !u.dismissed)
  const showBundle = !u.apk && u.bundleStatus === 'ready' && !!u.bundle && !u.bundle.force && !u.dismissed

  if (showApk) {
    const busy = u.apkStatus === 'downloading' || u.apkStatus === 'installing'
    return createPortal(
      <div className="fixed inset-0 z-[96] flex items-center justify-center px-5">
        <div className="absolute inset-0 bg-black/70" />
        <div className="relative w-full max-w-sm bg-reel-surface rounded-2xl p-5 ring-1 ring-reel-ink/10 shadow-[0_16px_48px_rgba(0,0,0,0.7)]">
          <p className="text-reel-ink font-semibold text-base">{t('update_title')}</p>
          <p className="text-reel-gold text-xs mt-0.5">v{u.apk.version_name}</p>

          {u.apk.changelog ? (
            <div className="mt-3">
              <p className="text-[11px] uppercase tracking-wide text-reel-muted mb-1">{t('update_whats_new')}</p>
              <p className="text-sm text-reel-ink/90 whitespace-pre-line max-h-40 overflow-y-auto">{u.apk.changelog}</p>
            </div>
          ) : null}

          {u.apkStatus === 'downloading' ? (
            <div className="mt-4">
              <div className="h-1.5 rounded-full bg-reel-ink/10 overflow-hidden">
                <div className="h-full bg-reel-gold transition-[width] duration-200" style={{ width: `${u.apkPct}%` }} />
              </div>
              <p className="text-[11px] text-reel-muted mt-1.5">
                {t('update_downloading')} {u.apkPct}%
              </p>
            </div>
          ) : null}
          {u.apkStatus === 'installing' ? (
            <p className="text-[11px] text-reel-muted mt-4">{t('update_installing')}</p>
          ) : null}
          {u.apkStatus === 'needs_permission' ? (
            <p className="text-xs text-reel-gold mt-4">{t('update_allow_install')}</p>
          ) : null}
          {u.apkStatus === 'error' ? <p className="text-xs text-reel-rust mt-4">{t('update_failed')}</p> : null}

          <div className="mt-5 flex gap-2.5">
            {!apkForce ? (
              <button
                onClick={dismissNotice}
                disabled={busy}
                className="flex-1 rounded-lg py-3 text-sm font-medium text-reel-ink bg-reel-surface2 ring-1 ring-reel-ink/10 disabled:opacity-50 active:scale-[0.98] transition"
              >
                {t('update_later')}
              </button>
            ) : null}
            <button
              onClick={startApkDownload}
              disabled={busy}
              className="flex-1 rounded-lg py-3 text-sm font-semibold bg-reel-gold text-reel-bg hover:brightness-110 disabled:opacity-60 active:scale-[0.98] transition"
            >
              {t('update_now')}
            </button>
          </div>
        </div>
      </div>,
      document.body
    )
  }

  if (showBundle) {
    return createPortal(
      <div
        className="fixed left-3 right-3 z-[96] flex justify-center"
        style={{ bottom: 'calc(84px + env(safe-area-inset-bottom))' }}
      >
        <div className="w-full max-w-md bg-reel-surface2 ring-1 ring-reel-gold/30 rounded-xl px-4 py-3 shadow-[0_8px_24px_-8px_rgba(0,0,0,0.7)]">
          <p className="text-sm font-semibold text-reel-ink">{t('update_features_ready')}</p>
          <p className="text-[11px] text-reel-muted mt-0.5">{t('update_ready_sub')}</p>
          <div className="mt-2.5 flex gap-2">
            <button
              onClick={dismissNotice}
              className="flex-1 rounded-lg py-2 text-xs font-medium text-reel-ink bg-reel-surface ring-1 ring-reel-ink/10 active:scale-[0.98] transition"
            >
              {t('update_later')}
            </button>
            <button
              onClick={applyBundle}
              className="flex-1 rounded-lg py-2 text-xs font-semibold bg-reel-gold text-reel-bg hover:brightness-110 active:scale-[0.98] transition"
            >
              {t('update_restart')}
            </button>
          </div>
        </div>
      </div>,
      document.body
    )
  }

  return null
}
