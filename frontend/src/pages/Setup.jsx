import { useNavigate } from 'react-router-dom'
import TelegramSignup from '../components/TelegramSignup'

// FEATURE (user ask: sign up ab app-launch par force nahi hota — app
// hamesha seedha Home par khulta hai. Yeh page ab sirf ek direct-link
// fallback hai; asli entry point ab Profile.jsx ka "Verify" button hai
// (dekho App.jsx — RequireConfig hata diya gaya hai).
export default function Setup() {
  const navigate = useNavigate()

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <h1 className="font-display text-4xl font-semibold text-reel-gold">Huka Tube</h1>
          <p className="text-reel-muted mt-2 text-sm">Your Telegram library, on the big screen.</p>
        </div>
        <TelegramSignup onDone={() => navigate('/')} />
      </div>
    </div>
  )
}
