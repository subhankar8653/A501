import { useLanguage } from '../i18n/LanguageContext'
import { LANGUAGES } from '../i18n/translations'

// FEATURE (user ask: "Profile mein app language select karne ka option do
// — abhi Hinglish hai, uske saath Bangla, Urdu, Hindi, English, Tamil,
// Telugu bhi ho"): ek simple tappable grid, current selection highlighted
// gold ring se, tap karte hi turant poore app mein apply ho jaata hai
// (localStorage mein bhi save hota hai — dekho api.js saveLanguage()).
export default function LanguagePicker() {
  const { lang, changeLanguage, t } = useLanguage()

  return (
    <div>
      <p className="text-xs font-medium text-reel-muted mb-2">{t('profile_language')}</p>
      <div className="grid grid-cols-2 gap-2">
        {LANGUAGES.map((l) => {
          const active = l.code === lang
          return (
            <button
              key={l.code}
              onClick={() => changeLanguage(l.code)}
              className={`flex flex-col items-start px-3.5 py-2.5 rounded-lg text-left transition active:scale-[0.98] ${
                active
                  ? 'bg-reel-gold/10 ring-1 ring-reel-gold text-reel-ink'
                  : 'bg-reel-surface ring-1 ring-white/5 text-reel-muted hover:text-reel-ink'
              }`}
            >
              <span className="text-sm font-medium">{l.native}</span>
              {l.native !== l.label ? <span className="text-[10px] text-reel-muted">{l.label}</span> : null}
            </button>
          )
        })}
      </div>
    </div>
  )
}
