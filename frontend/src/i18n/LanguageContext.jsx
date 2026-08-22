import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import { getLanguage, saveLanguage } from '../api'
import { translate } from './translations'

const LanguageContext = createContext(null)

export function LanguageProvider({ children }) {
  const [lang, setLang] = useState(getLanguage())

  const changeLanguage = useCallback((code) => {
    saveLanguage(code)
    setLang(code)
  }, [])

  const t = useCallback((key) => translate(lang, key), [lang])

  const value = useMemo(() => ({ lang, changeLanguage, t }), [lang, changeLanguage, t])

  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>
}

// Components use this to get: { lang, changeLanguage, t }
export function useLanguage() {
  return useContext(LanguageContext)
}
