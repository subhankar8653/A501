import { useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext'

const REASONS = [
  { key: 'audio', labelKey: 'report_reason_audio' },
  { key: 'subtitle', labelKey: 'report_reason_subtitle' },
  { key: 'broken', labelKey: 'report_reason_broken' },
  { key: 'quality', labelKey: 'report_reason_quality' },
  { key: 'other', labelKey: 'report_reason_other' },
]

// FEATURE (user ask: "Report ya Rating add karo"): quick-pick reason +
// optional free-text note, so both a fast "wrong subtitles" tap and a
// more detailed complaint are possible. `alreadyReported` disables
// re-submitting (see api.js getReportStatus) — one report per title per
// user is enough for admin triage, no need to spam it.
export default function ReportForm({ alreadyReported, submitting, submitted, onSubmit }) {
  const { t } = useLanguage()
  const [reason, setReason] = useState(null)
  const [note, setNote] = useState('')

  if (submitted || alreadyReported) {
    return (
      <div className="flex flex-col items-center gap-2 py-6 text-center">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="text-reel-gold"><circle cx="12" cy="12" r="9" /><polyline points="8 12 11 15 16 9" /></svg>
        <p className="text-sm text-reel-ink">
          {submitted ? t('report_submitted') : t('report_already')}
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-3 pb-2">
      <p className="text-sm text-reel-muted">{t('report_subtitle')}</p>
      <div className="flex flex-col gap-2">
        {REASONS.map((r) => (
          <button
            key={r.key}
            onClick={() => setReason(r.key)}
            className={`text-left text-sm px-3.5 py-2.5 rounded-lg ring-1 transition active:scale-[0.99] ${
              reason === r.key
                ? 'bg-reel-gold text-reel-bg font-semibold ring-reel-gold'
                : 'bg-reel-surface2 text-reel-ink ring-reel-ink/5'
            }`}
          >
            {t(r.labelKey)}
          </button>
        ))}
      </div>
      <textarea
        value={note}
        onChange={(e) => setNote(e.target.value)}
        placeholder={t('report_details_placeholder')}
        maxLength={300}
        rows={3}
        className="w-full text-sm bg-reel-surface2 text-reel-ink placeholder:text-reel-muted rounded-lg px-3.5 py-2.5 ring-1 ring-reel-ink/5 resize-none focus:outline-none focus:ring-reel-gold"
      />
      <button
        onClick={() => onSubmit(reason, note)}
        disabled={!reason || submitting}
        className="w-full text-sm font-semibold px-4 py-2.5 rounded-lg bg-reel-gold text-reel-bg active:scale-[0.98] transition disabled:opacity-40"
      >
        {submitting ? t('loading') : t('report_submit')}
      </button>
    </div>
  )
}
