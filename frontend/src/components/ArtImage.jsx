import { useEffect, useState } from 'react'
import PosterPlaceholder from './PosterPlaceholder'

// Placeholder images the backend sends when there is no real artwork
// (inline "No preview" SVG) count as "no image" so we show our branded logo instead.
export function isRealImage(url) {
  if (!url || typeof url !== 'string') return false
  if (url.startsWith('data:image/svg+xml')) return false
  return true
}

// Tries each candidate URL in order (one retry each) and reports the first that really loads.
// state: 'loading' | 'ok' | 'none'
export function useFirstWorkingImage(candidates) {
  const list = (candidates || []).filter(isRealImage)
  const key = list.join('|')
  const [res, setRes] = useState({ src: '', state: list.length ? 'loading' : 'none' })

  useEffect(() => {
    let dead = false
    if (!list.length) { setRes({ src: '', state: 'none' }); return undefined }
    setRes({ src: '', state: 'loading' })
    const tryAt = (i, retry) => {
      if (dead) return
      if (i >= list.length) { setRes({ src: '', state: 'none' }); return }
      const u = retry ? list[i] + (list[i].includes('?') ? '&' : '?') + '_r=1' : list[i]
      const im = new Image()
      const kill = setTimeout(() => { im.onload = im.onerror = null; next() }, 8000)
      const next = () => { clearTimeout(kill); retry ? tryAt(i + 1, false) : setTimeout(() => tryAt(i, true), 700) }
      im.onload = () => { clearTimeout(kill); if (!dead) setRes({ src: u, state: 'ok' }) }
      im.onerror = () => { im.onload = null; next() }
      im.src = u
    }
    tryAt(0, false)
    return () => { dead = true }
  }, [key]) // eslint-disable-line react-hooks/exhaustive-deps
  return res
}

// Image box that never looks empty: branded colour + logo while loading / when missing.
// Parent must be position:relative with a size (or pass className with sizing).
export default function ArtImage({ srcs, name = '', alt = '', className = '', imgClassName = 'object-cover', logoOnly = false, size }) {
  const { src, state } = useFirstWorkingImage(Array.isArray(srcs) ? srcs : [srcs])
  return (
    <div className={`relative overflow-hidden ${className}`}>
      {state !== 'ok' ? <PosterPlaceholder name={name} showTitle={!logoOnly && state === 'none'} pulse={state === 'loading'} /> : null}
      {state === 'ok' ? (
        <img src={src} alt={alt || name} className={`absolute inset-0 w-full h-full ${imgClassName}`} draggable={false} />
      ) : null}
    </div>
  )
}
