import { useNavigate } from 'react-router-dom'

// FEATURE (user ask: "app bina login ke khul jaaye, home page lock rahe —
// use karne ki koshish par ek popup + 'Profile pe jao, verify karo' button
// aa jaaye"): yeh wahi popup hai. Har jagah jo library/backend data maangti
// hai (Home, Detail, Player, Search) apna real content is card se replace
// kar deti hai jab tak user Profile se ek baar Telegram se verify na kar
// le — Saved/Downloads jaan-boojh kar isse touch nahi karte (woh hamesha
// local data se chalte hain, verify se pehle bhi).
export default function VerifyGate({ message }) {
  const navigate = useNavigate()

  return (
    <div className="min-h-[70vh] flex items-center justify-center px-4">
      <div className="w-full max-w-sm bg-reel-surface rounded-xl p-6 text-center ring-1 ring-white/5 space-y-4">
        <div className="w-14 h-14 mx-auto rounded-full bg-reel-surface2 ring-1 ring-reel-gold/30 flex items-center justify-center text-reel-gold">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="4" y="10" width="16" height="10" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /></svg>
        </div>
        <p className="text-reel-ink font-medium">
          {message || 'Yeh dekhne ke liye pehle khud ko verify karo.'}
        </p>
        <p className="text-reel-muted text-xs">
          Profile par ek tap mein Telegram se verify karo — bas ek baar.
        </p>
        <button
          onClick={() => navigate('/profile')}
          className="w-full bg-reel-gold text-reel-bg font-semibold rounded-lg py-3 text-sm hover:brightness-110 active:scale-[0.98] transition"
        >
          Profile pe jao, verify karo
        </button>
      </div>
    </div>
  )
}
