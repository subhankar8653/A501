import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNotifications, markNotificationsSeen, hideNotification } from '../lib/notificationsStore'
import { useLanguage } from '../i18n/LanguageContext'
import { useUpdate } from '../lib/useUpdate'
import { applyBundle, markNoticeSeen, startApkDownload } from '../lib/updateStore'

// YouTube-style notification list: filter chips, sections (Important / Today /
// This week / Earlier), avatar + text + optional thumbnail + relative time,
// blue unread dot and a "…" menu per row. Data comes from the server
// (replies, plan changes, announcements, welcome) — no upload listing here.
const DAY = 86400

function iconFor(kind) {
  const common = { width: 20, height: 20, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round' }
  if (kind === 'reply') return <svg {...common}><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z" /></svg>
  if (kind === 'subscription') return <svg {...common}><rect x="3" y="6" width="18" height="13" rx="2" /><path d="M3 10h18M7 15h4" /></svg>
  if (kind === 'welcome') return <svg {...common}><path d="M20 12v8H4v-8M2 7h20v5H2zM12 22V7M12 7H7.5a2.5 2.5 0 1 1 0-5C11 2 12 7 12 7zM12 7h4.5a2.5 2.5 0 1 0 0-5C13 2 12 7 12 7z" /></svg>
  return <svg {...common}><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0" /></svg>
}

function relTime(ts, lang) {
  const diff = Math.max(0, Math.floor(Date.now() / 1000) - (ts || 0))
  const loc = lang === 'hinglish' ? 'en' : lang
  let rtf
  try {
    rtf = new Intl.RelativeTimeFormat(loc, { numeric: 'auto', style: 'narrow' })
  } catch {
    rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto', style: 'narrow' })
  }
  if (diff < 60) return rtf.format(0, 'second')
  if (diff < 3600) return rtf.format(-Math.floor(diff / 60), 'minute')
  if (diff < DAY) return rtf.format(-Math.floor(diff / 3600), 'hour')
  if (diff < 30 * DAY) return rtf.format(-Math.floor(diff / DAY), 'day')
  return rtf.format(-Math.floor(diff / (30 * DAY)), 'month')
}

export default function NotificationSheet({ open, onClose }) {
  const { personal, loading, lastSeen } = useNotifications()
  const { t, lang } = useLanguage()
  const u = useUpdate()
  const [filter, setFilter] = useState('all')
  const [menuFor, setMenuFor] = useState(null)
  // Freeze "last seen" at open time so the blue dots stay visible while the
  // sheet is on screen, then clear the bell badge.
  const seenAtOpen = useRef(0)
  if (!open) seenAtOpen.current = lastSeen

  useEffect(() => {
    if (open) {
      setFilter('all')
      setMenuFor(null)
      markNotificationsSeen()
      markNoticeSeen()
    }
  }, [open])

  const sections = useMemo(() => {
    const now = Math.floor(Date.now() / 1000)
    const match = (n) =>
      filter === 'all' ||
      (filter === 'replies' && n.kind === 'reply') ||
      (filter === 'plan' && n.kind === 'subscription') ||
      (filter === 'updates' && n.kind !== 'reply' && n.kind !== 'subscription')
    const list = personal.filter(match)
    const important = list.filter((n) => n.kind === 'subscription' && now - (n.ts || 0) < 3 * DAY)
    const rest = list.filter((n) => !important.includes(n))
    const today = rest.filter((n) => now - (n.ts || 0) < DAY)
    const week = rest.filter((n) => now - (n.ts || 0) >= DAY && now - (n.ts || 0) < 7 * DAY)
    const earlier = rest.filter((n) => now - (n.ts || 0) >= 7 * DAY)
    return [
      [t('notif_important'), important],
      [t('notif_today'), today],
      [t('notif_week'), week],
      [t('notif_earlier'), earlier],
    ].filter(([, arr]) => arr.length)
  }, [personal, filter, t])

  if (!open) return null

  const chips = [
    ['all', t('notif_f_all')],
    ['replies', t('notif_f_replies')],
    ['plan', t('notif_f_plan')],
    ['updates', t('notif_f_updates')],
  ]

  const renderRow = (n, i) => {
    const href = n.media_type && n.media_id ? `/title/${n.media_type}/${encodeURIComponent(n.media_id)}` : null
    const unread = (n.ts || 0) * 1000 > seenAtOpen.current
    const key = `${n.kind}-${n.ref}-${n.ts}-${i}`
    const main = (
      <>
        <span className="shrink-0 w-11 h-11 rounded-full bg-reel-surface2 ring-1 ring-reel-ink/10 flex items-center justify-center text-reel-gold">
          {iconFor(n.kind)}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[13px] leading-snug font-medium text-reel-ink line-clamp-2">{n.title}</span>
          {n.body ? <span className="block text-xs text-reel-muted line-clamp-2 mt-0.5">{n.body}</span> : null}
          <span className="block text-[11px] text-reel-muted mt-1">{relTime(n.ts, lang)}</span>
        </span>
        {n.poster ? (
          <span className="shrink-0 w-16 h-10 rounded-md overflow-hidden bg-reel-surface2">
            <img src={n.poster} alt="" loading="lazy" className="w-full h-full object-cover" />
          </span>
        ) : null}
      </>
    )
    const cls = 'flex-1 min-w-0 flex items-center gap-3 py-2.5 pr-1 active:opacity-70 transition'
    return (
      <div key={key} className={`relative flex items-center pl-4 pr-1 ${unread ? 'bg-reel-gold/[0.06]' : ''}`}>
        <span className={`absolute left-1.5 top-1/2 -translate-y-1/2 w-1.5 h-1.5 rounded-full ${unread ? 'bg-sky-500' : 'bg-transparent'}`} />
        {href ? (
          <Link to={href} onClick={onClose} className={cls}>{main}</Link>
        ) : (
          <div className={cls}>{main}</div>
        )}
        <button
          type="button"
          aria-label="More"
          onClick={() => setMenuFor(menuFor === key ? null : key)}
          className="shrink-0 w-9 h-9 rounded-full flex items-center justify-center text-reel-muted hover:bg-reel-ink/[0.06]"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="12" cy="19" r="1.8" /></svg>
        </button>
        {menuFor === key ? (
          <div className="absolute right-3 top-11 z-10 rounded-xl bg-reel-surface2 ring-1 ring-reel-ink/10 shadow-xl py-1">
            <button
              type="button"
              onClick={() => {
                setMenuFor(null)
                hideNotification(n)
              }}
              className="block w-full text-left px-4 py-2.5 text-sm text-reel-ink whitespace-nowrap hover:bg-reel-ink/[0.06]"
            >
              {t('notif_hide')}
            </button>
          </div>
        ) : null}
      </div>
    )
  }

  return createPortal(
    <div className="fixed inset-0 z-[95] flex items-end justify-center" onClick={onClose}>
      <div className="absolute inset-0 bg-black/60" />
      <div
        onClick={(e) => {
          e.stopPropagation()
          setMenuFor(null)
        }}
        className="relative w-full max-w-md bg-reel-bg rounded-t-2xl pt-3 pb-[calc(1rem+env(safe-area-inset-bottom))] ring-1 ring-reel-ink/10 shadow-[0_-8px_32px_rgba(0,0,0,0.7)] h-[85vh] flex flex-col"
      >
        <div className="w-10 h-1 rounded-full bg-reel-ink/15 mx-auto mb-3" />
        <div className="flex items-center justify-between px-5 mb-3">
          <p className="text-reel-ink font-semibold text-lg">{t('notifications_title')}</p>
          <button onClick={onClose} aria-label="Close" className="w-8 h-8 rounded-full flex items-center justify-center text-reel-muted hover:bg-reel-ink/[0.06]">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>

        <div className="flex gap-2 px-5 pb-3 overflow-x-auto no-scrollbar">
          {chips.map(([id, label]) => (
            <button
              key={id}
              onClick={() => setFilter(id)}
              className={`shrink-0 px-3.5 py-1.5 rounded-lg text-xs font-medium transition active:scale-95 ${
                filter === id ? 'bg-reel-ink text-reel-bg' : 'bg-reel-surface2 text-reel-ink ring-1 ring-reel-ink/10'
              }`}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="flex-1 overflow-y-auto">
          {u.notice ? (
            <div className="mx-4 mb-3 rounded-xl bg-reel-surface2 ring-1 ring-reel-gold/30 p-3.5">
              <p className="text-sm font-semibold text-reel-ink">
                {u.apk ? `${t('update_title')} · v${u.apk.version_name}` : t('update_features_ready')}
              </p>
              {(u.apk ? u.apk.changelog : u.bundle && u.bundle.changelog) ? (
                <p className="text-xs text-reel-muted mt-1 whitespace-pre-line max-h-28 overflow-y-auto">
                  {u.apk ? u.apk.changelog : u.bundle.changelog}
                </p>
              ) : null}
              {u.apk && u.apkStatus === 'downloading' ? (
                <div className="mt-2 h-1.5 rounded-full bg-reel-ink/10 overflow-hidden">
                  <div className="h-full bg-reel-gold" style={{ width: `${u.apkPct}%` }} />
                </div>
              ) : null}
              <button
                onClick={u.apk ? startApkDownload : applyBundle}
                disabled={u.apk && (u.apkStatus === 'downloading' || u.apkStatus === 'installing')}
                className="mt-3 w-full rounded-lg py-2.5 text-xs font-semibold bg-reel-gold text-reel-bg hover:brightness-110 disabled:opacity-60 active:scale-[0.98] transition"
              >
                {u.apk ? t('update_now') : t('update_restart')}
              </button>
            </div>
          ) : null}

          {loading ? (
            <div className="space-y-3 px-4">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="h-14 rounded-xl bg-reel-surface2 animate-pulse" />
              ))}
            </div>
          ) : sections.length === 0 ? (
            <p className="text-reel-muted text-sm py-10 text-center">{t('notifications_empty')}</p>
          ) : (
            sections.map(([title, arr]) => (
              <div key={title} className="mb-2">
                <p className="px-5 pt-2 pb-1 text-sm font-semibold text-reel-ink">{title}</p>
                {arr.map(renderRow)}
              </div>
            ))
          )}
        </div>
      </div>
    </div>,
    document.body
  )
}
