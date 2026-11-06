import { useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'

// Opening intro: "HukaTube" logo animation (inspired by the Anime.js 3D logo
// reel): a red cube drops onto a tiled floor, shakes, pops — the floor tiles
// explode outward and the letters bounce in with a squish.
//
// Pure CSS/DOM (no three.js, no extra libraries) so it adds ~0 KB, runs smooth
// on low-end phones and cannot crash the WebView. The real app is already
// mounted + loading underneath; the intro leaves after MIN_MS, but not before
// Home has content (window.__hukaReady) — capped at MAX_MS so it never traps
// the user. Tap anywhere to skip.
const MIN_MS = 3300
const MAX_MS = 6500
const FADE_MS = 500
const COLS = 13
const ROWS = 7
const LETTERS = 'HukaTube'.split('')

let shownThisProcess = false

// ---- Sound design (Web Audio, synthesized — no audio file, ~0 KB) --------
// drop whoosh -> landing thud -> rattle -> POP boom + sparkle -> letter blips
// -> final shimmer chord. Every call is wrapped: if audio is unavailable or
// the phone is muted the intro just plays silently.
function playIntroSound() {
  try {
    const AC = window.AudioContext || window.webkitAudioContext
    if (!AC) return () => {}
    const ctx = new AC()
    if (ctx.state === 'suspended') ctx.resume().catch(() => {})
    const t0 = ctx.currentTime + 0.05
    const master = ctx.createGain()
    master.gain.value = 0.55
    master.connect(ctx.destination)

    const noiseBuf = (() => {
      const len = ctx.sampleRate * 1.2
      const b = ctx.createBuffer(1, len, ctx.sampleRate)
      const d = b.getChannelData(0)
      for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1
      return b
    })()

    const noise = (at, dur, f0, f1, vol, type = 'bandpass', q = 1.2) => {
      const src = ctx.createBufferSource()
      src.buffer = noiseBuf
      const fl = ctx.createBiquadFilter()
      fl.type = type
      fl.Q.value = q
      fl.frequency.setValueAtTime(f0, t0 + at)
      fl.frequency.exponentialRampToValueAtTime(f1, t0 + at + dur)
      const g = ctx.createGain()
      g.gain.setValueAtTime(0.0001, t0 + at)
      g.gain.exponentialRampToValueAtTime(vol, t0 + at + dur * 0.4)
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + at + dur)
      src.connect(fl).connect(g).connect(master)
      src.start(t0 + at)
      src.stop(t0 + at + dur + 0.05)
    }
    const tone = (at, dur, f0, f1, vol, type = 'sine') => {
      const o = ctx.createOscillator()
      o.type = type
      o.frequency.setValueAtTime(f0, t0 + at)
      o.frequency.exponentialRampToValueAtTime(Math.max(20, f1), t0 + at + dur)
      const g = ctx.createGain()
      g.gain.setValueAtTime(0.0001, t0 + at)
      g.gain.exponentialRampToValueAtTime(vol, t0 + at + 0.012)
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + at + dur)
      o.connect(g).connect(master)
      o.start(t0 + at)
      o.stop(t0 + at + dur + 0.05)
    }

    // cube falling: rising whoosh
    noise(0.2, 0.5, 300, 2600, 0.5)
    // landing: deep thud + dust
    tone(0.7, 0.35, 110, 38, 0.95)
    noise(0.7, 0.18, 900, 120, 0.5, 'lowpass', 0.7)
    // rattle while it shakes
    for (let i = 0; i < 6; i++) tone(0.86 + i * 0.07, 0.06, 220 - i * 18, 120, 0.12 * (1 - i / 7), 'triangle')
    // charge-up riser, then POP
    noise(1.0, 0.3, 400, 5000, 0.35, 'highpass', 0.5)
    tone(1.28, 0.5, 180, 40, 1.0)
    noise(1.28, 0.45, 3500, 300, 0.8, 'bandpass', 0.6)
    // sparkles
    for (let i = 0; i < 7; i++) tone(1.34 + i * 0.045, 0.14, 2200 + i * 320, 3200 + i * 380, 0.07, 'sine')
    // letters: rising blips, one per letter
    const scale = [392, 440, 523, 587, 659, 784, 880, 1047]
    LETTERS.forEach((_, i) => tone(1.5 + i * 0.065, 0.2, scale[i], scale[i] * 1.01, 0.22, 'triangle'))
    // final shimmer chord
    ;[523, 659, 784, 1047].forEach((f, i) => tone(2.25 + i * 0.035, 1.3, f, f, 0.16, 'sine'))
    noise(2.25, 0.9, 6000, 9000, 0.06, 'highpass', 0.4)

    return () => {
      try {
        master.gain.cancelScheduledValues(ctx.currentTime)
        master.gain.setTargetAtTime(0, ctx.currentTime, 0.05)
        window.setTimeout(() => ctx.close().catch(() => {}), 400)
      } catch {
        /* ignore */
      }
    }
  } catch {
    return () => {}
  }
}

function rnd(seed) {
  const x = Math.sin(seed * 9301 + 49297) * 233280
  return x - Math.floor(x)
}

const SPARKS = Array.from({ length: 16 }, (_, i) => {
  const a = (i / 16) * Math.PI * 2 + rnd(i + 3) * 0.4
  const d = 90 + rnd(i + 40) * 130
  return { i, x: Math.cos(a) * d, y: Math.sin(a) * d * 0.7 - 30, s: 3 + rnd(i + 11) * 5 }
})

export default function Intro() {
  const [phase, setPhase] = useState(() => (shownThisProcess ? 'gone' : 'play')) // play | fade | gone

  const tiles = useMemo(() => {
    const out = []
    const cx = (COLS - 1) / 2
    const cy = (ROWS - 1) / 2
    for (let r = 0; r < ROWS; r++) {
      for (let c = 0; c < COLS; c++) {
        const dx = c - cx
        const dy = r - cy
        const d = Math.hypot(dx, dy)
        const i = r * COLS + c
        out.push({
          i, c, r, d,
          // explode direction + spin, seeded so it is the same every launch
          ex: dx * 90 + (rnd(i) - 0.5) * 140,
          ey: -160 - rnd(i + 99) * 260,
          rot: (rnd(i + 7) - 0.5) * 900,
          delay: 1300 + d * 22,
        })
      }
    }
    return out
  }, [])

  useEffect(() => {
    if (phase !== 'play') return undefined
    shownThisProcess = true
    const stopSound = playIntroSound()
    const t0 = Date.now()
    let done = false
    const finish = () => {
      if (done) return
      done = true
      setPhase('fade')
      window.setTimeout(() => setPhase('gone'), FADE_MS)
    }
    const check = () => {
      const age = Date.now() - t0
      if (age >= MAX_MS || (age >= MIN_MS && window.__hukaReady)) finish()
    }
    const id = window.setInterval(check, 150)
    const onReady = () => check()
    window.addEventListener('huka-app-ready', onReady)
    return () => {
      window.clearInterval(id)
      window.removeEventListener('huka-app-ready', onReady)
      stopSound()
    }
  }, [phase])

  if (phase === 'gone') return null

  return createPortal(
    <div
      className="huka-intro"
      data-phase={phase}
      onClick={() => {
        setPhase('fade')
        window.setTimeout(() => setPhase('gone'), FADE_MS)
      }}
    >
      <style>{CSS}</style>
      <div className="hi-stage">
        <div className="hi-floor">
          {tiles.map((t) => (
            <span
              key={t.i}
              className="hi-tile"
              style={{
                gridColumn: t.c + 1,
                gridRow: t.r + 1,
                '--ex': `${t.ex}%`,
                '--ey': `${t.ey}%`,
                '--rot': `${t.rot}deg`,
                '--dl': `${t.delay}ms`,
                '--dip': t.d < 1.6 ? 1 : 0,
              }}
            />
          ))}
        </div>
        <div className="hi-flash" />
        {SPARKS.map((p) => (
          <span key={p.i} className="hi-spark" style={{ '--sx': `${p.x}px`, '--sy': `${p.y}px`, width: p.s, height: p.s }} />
        ))}
        <div className="hi-cubewrap">
          <div className="hi-cube" />
        </div>
        <div className="hi-logo" aria-label="HukaTube">
          {LETTERS.map((ch, i) => (
            <span
              key={i}
              className={`hi-letter ${i >= 4 ? 'hi-gold' : ''}`}
              style={{ '--ld': `${1500 + i * 65}ms` }}
            >
              {ch}
            </span>
          ))}
        </div>
        <p className="hi-tag">Anime · Movies · K-Drama</p>
      </div>
    </div>,
    document.body
  )
}

const CSS = `
.huka-intro{position:fixed;inset:0;z-index:300;background:#252423;overflow:hidden;
  transition:opacity ${FADE_MS}ms ease;touch-action:manipulation}
.huka-intro[data-phase="fade"]{opacity:0;pointer-events:none}
.hi-stage{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;
  background:radial-gradient(ellipse at 50% 58%,#3b3937 0%,#252423 45%,#141312 100%);
  animation:hi-fadein 900ms ease both,hi-shake 380ms ease-out 900ms both}
@keyframes hi-shake{0%,100%{transform:translate(0,0)}15%{transform:translate(-5px,4px)}35%{transform:translate(5px,-3px)}55%{transform:translate(-3px,2px)}75%{transform:translate(2px,-1px)}}
.hi-flash{position:absolute;left:50%;top:58%;width:10px;height:10px;border-radius:50%;margin:-5px 0 0 -5px;
  background:radial-gradient(circle,#fff 0%,rgba(255,200,120,.55) 40%,transparent 70%);opacity:0;
  animation:hi-flash 650ms ease-out 1300ms both}
@keyframes hi-flash{0%{opacity:.95;transform:scale(1)}100%{opacity:0;transform:scale(70)}}
.hi-spark{position:absolute;left:50%;top:58%;border-radius:50%;background:#ffd98a;opacity:0;
  box-shadow:0 0 8px 2px rgba(255,200,100,.8);
  animation:hi-spark 800ms cubic-bezier(.1,.7,.3,1) 1300ms both}
@keyframes hi-spark{0%{opacity:1;transform:translate(0,0) scale(1)}100%{opacity:0;transform:translate(var(--sx),var(--sy)) scale(.1)}}
@keyframes hi-fadein{from{opacity:0}to{opacity:1}}

/* tiled floor in perspective */
.hi-floor{position:absolute;left:50%;top:58%;width:130vw;aspect-ratio:${COLS}/${ROWS};
  transform:translate(-50%,-50%) perspective(900px) rotateX(52deg);
  display:grid;grid-template-columns:repeat(${COLS},1fr);grid-template-rows:repeat(${ROWS},1fr);gap:2px}
.hi-tile{background:#d4d4d8;border-radius:2px;will-change:transform,opacity;
  animation:hi-tile-explode 900ms cubic-bezier(.2,.7,.3,1) var(--dl) forwards,
            hi-tile-dip 420ms ease-out 700ms both}
@keyframes hi-tile-dip{0%{transform:translateY(0)}40%{transform:translateY(calc(var(--dip) * 18%))}100%{transform:translateY(0)}}
@keyframes hi-tile-explode{
  0%{transform:translate(0,0) rotate(0);opacity:1}
  100%{transform:translate(var(--ex),var(--ey)) rotate(var(--rot)) scale(.2);opacity:0}}

/* red cube: drop, squash, shake, pop */
.hi-cubewrap{position:absolute;left:50%;top:58%;width:0;height:0}
.hi-cube{position:absolute;width:15vw;height:15vw;max-width:90px;max-height:90px;left:-7.5vw;margin-left:0;
  background:#ff4b4b;border-radius:10px;box-shadow:inset 0 -8px 0 rgba(0,0,0,.18);
  transform-origin:50% 100%;bottom:0;
  animation:hi-cube 1500ms cubic-bezier(.5,0,.8,.4) 200ms both}
@media (min-width:600px){.hi-cube{left:-45px}}
@keyframes hi-cube{
  0%{transform:translateY(-70vh) scale(.8,1.4)}
  33%{transform:translateY(0) scale(1.25,.75)}
  42%{transform:translateY(0) scale(.92,1.1) skewX(-6deg)}
  50%{transform:translateY(0) scale(1.05,.97) skewX(6deg)}
  58%{transform:translateY(0) scale(.98,1.02) skewX(-4deg)}
  68%{transform:translateY(0) scale(1,1) skewX(0)}
  82%{transform:translateY(-34vh) scale(.8,1.2) rotate(-200deg);opacity:1}
  100%{transform:translateY(-44vh) scale(0) rotate(-360deg);opacity:0}}

/* letters bounce in with a squish, italic like the reference logo */
.hi-logo{position:relative;display:flex;gap:.01em;font-family:'Arial Black','Segoe UI Black',system-ui,sans-serif;
  font-weight:900;font-size:min(16.5vw,96px);letter-spacing:-.02em;line-height:1;margin-top:-4vh}
.hi-letter{display:inline-block;transform:translateY(90vh) skewX(-8deg) scale(.3,.4);opacity:0;
  background-image:linear-gradient(110deg,#d9d9e2 0%,#d9d9e2 42%,#fff 50%,#d9d9e2 58%,#d9d9e2 100%);
  background-size:260% 100%;background-position:130% 0;
  -webkit-background-clip:text;background-clip:text;color:transparent;-webkit-text-fill-color:transparent;
  filter:drop-shadow(0 5px 0 rgba(0,0,0,.3)) drop-shadow(0 0 14px rgba(255,255,255,.08));
  animation:hi-letter 750ms cubic-bezier(.2,1,.3,1) var(--ld) forwards,
            hi-shine 900ms ease-in-out calc(var(--ld) + 900ms) forwards}
.hi-gold{background-image:linear-gradient(110deg,#f2a516 0%,#f5b63a 42%,#fff2c0 50%,#f5b63a 58%,#f2a516 100%);
  filter:drop-shadow(0 5px 0 rgba(0,0,0,.3)) drop-shadow(0 0 16px rgba(245,182,58,.35))}
@keyframes hi-shine{from{background-position:130% 0}to{background-position:-30% 0}}
@keyframes hi-letter{
  0%{transform:translateY(60vh) skewX(-8deg) scale(.3,.4);opacity:0}
  35%{transform:translateY(-14vh) skewX(-8deg) scale(.85,1.4);opacity:1}
  55%{transform:translateY(2vh) skewX(-8deg) scale(1.12,.7)}
  75%{transform:translateY(-1vh) skewX(-8deg) scale(.97,1.08)}
  100%{transform:translateY(0) skewX(-8deg) scale(1,1);opacity:1}}
.hi-tag{position:absolute;left:0;right:0;top:calc(50% + min(14vw,80px));text-align:center;
  font:600 14px/1 system-ui,sans-serif;letter-spacing:.28em;text-transform:uppercase;color:#b5b5ba;
  opacity:0;animation:hi-tag 700ms ease 2400ms forwards}
@keyframes hi-tag{from{opacity:0;transform:translateY(8px)}to{opacity:.9;transform:none}}
@media (prefers-reduced-motion:reduce){.hi-tile,.hi-cube{animation-duration:1ms}}
`
