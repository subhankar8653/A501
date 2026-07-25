import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getStreams } from '../api'
import VideoPlayer from '../components/VideoPlayer'

export default function Player() {
  const { type, id } = useParams()
  const navigate = useNavigate()
  const [streams, setStreams] = useState(null)
  const [active, setActive] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => {
    setStreams(null)
    setActive(null)
    setError('')
    getStreams(type, id)
      .then((list) => {
        if (!list.length) {
          setError('Is title ke liye koi stream nahi mili.')
          return
        }
        setStreams(list)
        setActive(list[0])
      })
      .catch(() => setError('Stream load nahi hui.'))
  }, [type, id])

  return (
    <div className="max-w-5xl mx-auto py-6 px-4 sm:px-6">
      <button onClick={() => navigate(-1)} className="text-sm text-reel-muted hover:text-reel-ink mb-4">
        ← Back
      </button>

      {error ? (
        <p className="text-reel-rust">{error}</p>
      ) : !active ? (
        <div className="aspect-video bg-reel-surface2 rounded-xl animate-pulse" />
      ) : (
        <>
          <div className="aspect-video bg-black rounded-xl overflow-hidden ring-1 ring-white/10">
            <VideoPlayer key={active.url} src={active.url} />
          </div>

          {streams.length > 1 ? (
            <div className="mt-4">
              <p className="text-xs text-reel-muted mb-2">Available qualities</p>
              <div className="flex gap-2 flex-wrap">
                {streams.map((s, i) => (
                  <button
                    key={i}
                    onClick={() => setActive(s)}
                    title={s.title}
                    className={`text-sm px-3 py-1.5 rounded-lg transition whitespace-pre-line ${
                      s === active
                        ? 'bg-reel-gold text-reel-bg font-semibold'
                        : 'bg-reel-surface2 text-reel-muted hover:text-reel-ink'
                    }`}
                  >
                    {s.name}
                  </button>
                ))}
              </div>
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}
