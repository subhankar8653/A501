import { clearConfig, clearProfile, getProfile, avatarUrl } from '../api'
import { useNavigate } from 'react-router-dom'
import { useSavedList } from '../lib/savedStore'
import { useDownloadsList } from '../lib/downloadsStore'

export default function Profile() {
  const navigate = useNavigate()
  const saved = useSavedList()
  const downloads = useDownloadsList()
  const doneDownloads = downloads.filter((d) => d.status === 'done').length
  const profile = getProfile()
  const displayName = profile?.name || 'Guest'
  const handle = profile?.username ? `@${profile.username}` : null

  function handleLogout() {
    clearConfig()
    clearProfile()
    navigate('/setup')
  }

  return (
    <div className="max-w-6xl mx-auto py-8 px-4 sm:px-6">
      <div className="flex flex-col items-center text-center mb-8">
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

      <div className="grid grid-cols-2 gap-3 mb-8">
        <div className="bg-reel-surface rounded-lg p-4 text-center ring-1 ring-white/5">
          <p className="font-display text-2xl text-reel-gold">{saved.length}</p>
          <p className="text-xs text-reel-muted mt-1">Saved</p>
        </div>
        <div className="bg-reel-surface rounded-lg p-4 text-center ring-1 ring-white/5">
          <p className="font-display text-2xl text-reel-gold">{doneDownloads}</p>
          <p className="text-xs text-reel-muted mt-1">Downloads</p>
        </div>
      </div>

      {profile ? (
        <button
          onClick={handleLogout}
          className="w-full text-sm px-4 py-3 rounded-lg bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-[0.98] transition"
        >
          Log out
        </button>
      ) : null}
    </div>
  )
}
