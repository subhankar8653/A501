import { Component } from 'react'

// FEATURE (bug report: "episode pe click karta hoon toh blank page khulta
// hai, player nahi khulta"): a JS exception anywhere in Player.jsx's render
// currently unmounts the *entire* page with no message — React's default
// behaviour without a boundary. That's exactly what a silent "blank page"
// looks like from the outside. This wraps Player's content so any future
// crash there shows the real error (and logs it to console for logcat/
// devtools) instead of just going dark — makes the next bug report
// actionable instead of a guessing game.
export default class PlayerErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    // eslint-disable-next-line no-console
    console.error('[Player crashed]', error, info?.componentStack)
  }

  render() {
    if (this.state.error) {
      return (
        <div className="px-4 sm:px-6 py-10 text-center">
          <p className="text-reel-rust font-medium mb-2">Player load nahi ho paaya</p>
          <p className="text-xs text-reel-muted mb-4 break-words">{String(this.state.error?.message || this.state.error)}</p>
          <button
            onClick={() => this.setState({ error: null })}
            className="text-sm px-4 py-2 rounded-full bg-reel-surface2 text-reel-ink hover:bg-reel-surface2/70 active:scale-95 transition"
          >
            Retry
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
