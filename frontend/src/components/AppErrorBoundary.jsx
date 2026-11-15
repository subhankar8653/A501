import { Component } from 'react'

// Whole-app safety net (user report: "ek jagah se dusri jagah jaata hoon to poora app blank ho
// jata hai"). Only the Player had a boundary, so a render error on ANY other page unmounted the
// whole tree = a blank screen. This wraps every page (App.jsx keys it by route, so moving to
// another page always starts clean). On a crash it quietly retries once by itself, and if it
// still fails it shows a small message with Retry / Reload buttons - never a blank screen.
export default class AppErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
    this.autoTried = false
  }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    // eslint-disable-next-line no-console
    console.error('[Page crashed]', error, info?.componentStack)
    try {
      localStorage.setItem(
        'huka:last-error',
        JSON.stringify({ t: Date.now(), msg: String(error?.message || error), where: String(info?.componentStack || '').split('\n').slice(0, 4).join(' | ') }),
      )
    } catch { /* ignore */ }
    if (!this.autoTried) {
      this.autoTried = true
      setTimeout(() => this.setState({ error: null }), 250)
    }
  }

  render() {
    if (this.state.error && this.autoTried) {
      // first crash: render nothing for ~250 ms while the silent retry happens
      return (
        <div className="px-4 sm:px-6 py-16 text-center">
          <p className="text-reel-ink font-medium mb-1">Kuch gadbad ho gayi</p>
          <p className="text-xs text-reel-muted mb-5 break-words">{String(this.state.error?.message || this.state.error)}</p>
          <div className="flex justify-center gap-3">
            <button
              onClick={() => this.setState({ error: null })}
              className="text-sm px-4 py-2 rounded-full bg-reel-surface2 text-reel-ink active:scale-95 transition"
            >
              Retry
            </button>
            <button
              onClick={() => window.location.reload()}
              className="text-sm px-4 py-2 rounded-full bg-reel-gold text-black active:scale-95 transition"
            >
              Reload
            </button>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}
