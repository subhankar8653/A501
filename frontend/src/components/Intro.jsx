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

function rnd(seed) {
  const x = Math.sin(seed * 9301 + 49297) * 233280
  return x - Math.floor(x)
}

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
  animation:hi-fadein 900ms ease both}
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
.hi-letter{display:inline-block;color:#fff;transform:translateY(90vh) skewX(-8deg) scale(.3,.4);opacity:0;
  text-shadow:0 6px 0 rgba(0,0,0,.25);
  animation:hi-letter 750ms cubic-bezier(.2,1,.3,1) var(--ld) forwards}
.hi-gold{color:#f5b63a}
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
