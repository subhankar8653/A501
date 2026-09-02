import { useLanguage } from '../i18n/LanguageContext'

// FEATURE (user ask: "Report ya Rating add karo"): tap a star (1-5) to
// rate — submits immediately, no separate "submit" step. Shows the
// title's current average + vote count underneath so the sheet also
// doubles as "see what others rated this".
export default function RatingStars({ rating, onRate }) {
  const { t } = useLanguage()
  const { average, count, mine } = rating

  return (
    <div className="flex flex-col items-center gap-3 py-2">
      <div className="flex gap-1.5">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            onClick={() => onRate(n)}
            aria-label={`${n} star`}
            className="active:scale-90 transition"
          >
            <svg
              width="34"
              height="34"
              viewBox="0 0 24 24"
              fill={mine && n <= mine ? 'currentColor' : 'none'}
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
              className={mine && n <= mine ? 'text-reel-gold' : 'text-reel-muted'}
            >
              <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
            </svg>
          </button>
        ))}
      </div>
      <p className="text-sm text-reel-muted">
        {count > 0 ? (
          <>★ {average} · {count} {t('rate_count_suffix')}</>
        ) : (
          t('rate_be_first')
        )}
      </p>
    </div>
  )
}
