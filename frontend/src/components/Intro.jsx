import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

// Opening intro — cinematic "HukaTube" logo reveal.
//
// Sequence (≈4.3 s, tap anywhere to skip):
//   0.0  dark stage, slow light rays + drifting embers (canvas)
//   0.2  a glass 3D cube with a gold play-mark spins in from depth
//   1.65 cube detonates: flash, shock-rings, anamorphic light streak,
//        particle burst
//   1.7  "HukaTube" resolves letter-by-letter out of blur, light sweeps over it
//   2.3  gold rule draws out, rotating tagline: Anime · Movies · K-Drama ·
//        Short Drama · & More  (so every kind of content is covered)
//
// Pure CSS 3D + one small <canvas> + synthesized Web Audio (no files, no
// libraries). The real app is already mounted underneath; the intro leaves
// after MIN_MS but not before Home has content (window.__hukaReady), capped at
// MAX_MS so it can never trap the user.
const MIN_MS = 4400
const MAX_MS = 7000
const FADE_MS = 550
const BURST_MS = 1650
const LETTERS = 'HukaTube'.split('')
const WORDS = ['Anime', 'Movies', 'K-Drama', 'Short Drama', '& More']
const WORD_START = 2300
const WORD_STEP = 460

let shownThisProcess = false

// ---- Sound design (Web Audio, synthesized) --------------------------------
function playIntroSound() {
  try {
    const AC = window.AudioContext || window.webkitAudioContext
    if (!AC) return () => {}
    const ctx = new AC()
    if (ctx.state === 'suspended') ctx.resume().catch(() => {})
    const t0 = ctx.currentTime + 0.05

    // master -> dry + small generated "hall" reverb for a polished tail
    const master = ctx.createGain()
    master.gain.value = 0.6
    master.connect(ctx.destination)
    try {
      const len = Math.floor(ctx.sampleRate * 1.8)
      const ir = ctx.createBuffer(2, len, ctx.sampleRate)
      for (let c = 0; c < 2; c++) {
        const d = ir.getChannelData(c)
        for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 2.6)
      }
      const conv = ctx.createConvolver()
      conv.buffer = ir
      const wet = ctx.createGain()
      wet.gain.value = 0.32
      master.connect(conv)
      conv.connect(wet)
      wet.connect(ctx.destination)
    } catch {
      /* reverb is optional */
    }

    const noiseBuf = (() => {
      const len = Math.floor(ctx.sampleRate * 1.6)
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
      g.gain.exponentialRampToValueAtTime(vol, t0 + at + dur * 0.55)
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + at + dur)
      src.connect(fl).connect(g).connect(master)
      src.start(t0 + at)
      src.stop(t0 + at + dur + 0.05)
    }
    const tone = (at, dur, f0, f1, vol, type = 'sine', attack = 0.012) => {
      const o = ctx.createOscillator()
      o.type = type
      o.frequency.setValueAtTime(f0, t0 + at)
      o.frequency.exponentialRampToValueAtTime(Math.max(20, f1), t0 + at + dur)
      const g = ctx.createGain()
      g.gain.setValueAtTime(0.0001, t0 + at)
      g.gain.exponentialRampToValueAtTime(vol, t0 + at + attack)
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + at + dur)
      o.connect(g).connect(master)
      o.start(t0 + at)
      o.stop(t0 + at + dur + 0.05)
    }

    // cube spinning in: rising riser + low sweep + mechanical ticks
    noise(0.15, 1.45, 220, 4200, 0.38, 'bandpass', 0.8)
    tone(0.15, 1.45, 80, 640, 0.16, 'sine', 0.4)
    ;[0.5, 0.78, 1.04, 1.28].forEach((a) => tone(a, 0.05, 2000, 900, 0.07, 'square'))
    // detonation: sub boom + crack + air
    const B = BURST_MS / 1000
    tone(B, 0.9, 130, 32, 1.0, 'sine', 0.008)
    tone(B, 0.25, 520, 90, 0.4, 'triangle', 0.005)
    noise(B, 0.6, 5200, 260, 0.85, 'bandpass', 0.5)
    // sparkle shower
    for (let i = 0; i < 9; i++) tone(B + 0.04 + i * 0.05, 0.22, 2400 + i * 260, 3400 + i * 300, 0.055, 'sine')
    // letters resolve: soft rising pentatonic pings
    const scale = [392, 440, 523, 587, 659, 784, 880, 1047]
    LETTERS.forEach((_, i) => tone(B + 0.08 + i * 0.055, 0.45, scale[i], scale[i], 0.13, 'sine', 0.01))
    // tagline word ticks
    WORDS.forEach((_, k) => tone((WORD_START + k * WORD_STEP) / 1000, 0.07, 1500, 1500, 0.05, 'triangle'))
    // final warm chord when "& More" lands + shimmer
    const F = (WORD_START + (WORDS.length - 1) * WORD_STEP) / 1000
    ;[262, 392, 523, 659, 784].forEach((f, i) => tone(F + i * 0.03, 1.7, f, f, 0.12, 'sine', 0.05))
    noise(F, 1.0, 6500, 9500, 0.05, 'highpass', 0.4)

    return () => {
      try {
        master.gain.cancelScheduledValues(ctx.currentTime)
        master.gain.setTargetAtTime(0, ctx.currentTime, 0.08)
        window.setTimeout(() => ctx.close().catch(() => {}), 600)
      } catch {
        /* ignore */
      }
    }
  } catch {
    return () => {}
  }
}

// ---- Particles (embers + burst) -------------------------------------------
function startParticles(canvas) {
  const ctx = canvas.getContext('2d')
  if (!ctx) return () => {}
  const dpr = Math.min(window.devicePixelRatio || 1, 1.75)
  let w = 0
  let h = 0
  const resize = () => {
    w = window.innerWidth
    h = window.innerHeight
    canvas.width = Math.round(w * dpr)
    canvas.height = Math.round(h * dpr)
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  resize()
  window.addEventListener('resize', resize)

  const rand = (a, b) => a + Math.random() * (b - a)
  const embers = Array.from({ length: 46 }, () => ({
    x: rand(0, w), y: rand(0, h), r: rand(0.6, 2.1), vy: rand(0.08, 0.35), vx: rand(-0.08, 0.08),
    ph: rand(0, 6.28), a: rand(0.15, 0.55), gold: Math.random() < 0.6,
  }))
  let burst = []
  const cx = () => w / 2
  const cy = () => h * 0.46

  const fireBurst = () => {
    for (let i = 0; i < 120; i++) {
      const ang = rand(0, Math.PI * 2)
      const sp = rand(1.5, 9.5)
      burst.push({
        x: cx(), y: cy(), vx: Math.cos(ang) * sp, vy: Math.sin(ang) * sp * 0.85,
        r: rand(0.8, 2.8), life: rand(0.9, 1.9), age: 0,
        c: Math.random() < 0.55 ? '255,196,86' : Math.random() < 0.5 ? '255,255,255' : '255,140,60',
      })
    }
  }
  const burstTimer = window.setTimeout(fireBurst, BURST_MS)

  let raf = 0
  let last = performance.now()
  const frame = (now) => {
    const dt = Math.min(0.05, (now - last) / 1000)
    last = now
    const k = dt * 60
    ctx.clearRect(0, 0, w, h)
    ctx.globalCompositeOperation = 'lighter'
    for (const e of embers) {
      e.y -= e.vy * k
      e.x += e.vx * k + Math.sin(now / 1600 + e.ph) * 0.12
      e.ph += 0.01 * k
      if (e.y < -6) { e.y = h + 6; e.x = rand(0, w) }
      const tw = 0.55 + 0.45 * Math.sin(now / 500 + e.ph * 3)
      ctx.beginPath()
      ctx.fillStyle = `rgba(${e.gold ? '255,200,100' : '220,225,255'},${(e.a * tw).toFixed(3)})`
      ctx.arc(e.x, e.y, e.r, 0, 6.283)
      ctx.fill()
    }
    for (let i = burst.length - 1; i >= 0; i--) {
      const p = burst[i]
      p.age += dt
      if (p.age >= p.life) { burst.splice(i, 1); continue }
      p.vx *= Math.pow(0.955, k)
      p.vy = p.vy * Math.pow(0.955, k) + 0.045 * k
      p.x += p.vx * k
      p.y += p.vy * k
      const t = 1 - p.age / p.life
      const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.r * 4)
      g.addColorStop(0, `rgba(${p.c},${(0.9 * t).toFixed(3)})`)
      g.addColorStop(1, `rgba(${p.c},0)`)
      ctx.fillStyle = g
      ctx.beginPath()
      ctx.arc(p.x, p.y, p.r * 4, 0, 6.283)
      ctx.fill()
    }
    raf = requestAnimationFrame(frame)
  }
  raf = requestAnimationFrame(frame)
  return () => {
    cancelAnimationFrame(raf)
    window.clearTimeout(burstTimer)
    window.removeEventListener('resize', resize)
  }
}

export default function Intro() {
  const [phase, setPhase] = useState(() => (shownThisProcess ? 'gone' : 'play')) // play | fade | gone
  const canvasRef = useRef(null)

  useEffect(() => {
    if (phase !== 'play') return undefined
    shownThisProcess = true
    const stopSound = playIntroSound()
    const stopParticles = canvasRef.current ? startParticles(canvasRef.current) : () => {}
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
      stopParticles()
    }
  }, [phase])

  if (phase === 'gone') return null

  const skip = () => {
    if (phase !== 'play') return
    setPhase('fade')
    window.setTimeout(() => setPhase('gone'), FADE_MS)
  }

  return createPortal(
    <div className="huka-intro" data-phase={phase} onClick={skip}>
      <style>{CSS}</style>
      <div className="hi-rays" />
      <canvas ref={canvasRef} className="hi-canvas" />
      <div className="hi-halo" />
      <div className="hi-scene">
        <div className="hi-cube3">
          {['f', 'b', 'r', 'l', 't', 'd'].map((k) => (
            <span key={k} className={`hi-face hi-face-${k}`} />
          ))}
        </div>
      </div>
      <div className="hi-ring hi-ring1" />
      <div className="hi-ring hi-ring2" />
      <div className="hi-flare" />
      <div className="hi-flash" />
      <div className="hi-center">
        <div className="hi-logo" aria-label="HukaTube">
          {LETTERS.map((ch, i) => (
            <span
              key={i}
              className={`hi-letter ${i >= 4 ? 'hi-gold' : ''}`}
              style={{ '--ld': `${BURST_MS + 60 + i * 55}ms` }}
            >
              {ch}
            </span>
          ))}
        </div>
        <div className="hi-rule" />
        <div className="hi-tagwrap" aria-hidden="true">
          {WORDS.map((w, i) => (
            <span
              key={w}
              className={`hi-word ${i === WORDS.length - 1 ? 'hi-word-last' : ''}`}
              style={{ '--wd': `${WORD_START + i * WORD_STEP}ms` }}
            >
              {w}
            </span>
          ))}
        </div>
      </div>
      <div className="hi-vignette" />
    </div>,
    document.body
  )
}

const CY = '46%'
const CSS = `
.huka-intro{position:fixed;inset:0;z-index:300;overflow:hidden;touch-action:manipulation;
  background:radial-gradient(ellipse at 50% ${CY},#2a2826 0%,#171615 52%,#0c0b0b 100%);
  transition:opacity ${FADE_MS}ms ease}
.huka-intro[data-phase="fade"]{opacity:0;pointer-events:none}
.huka-intro *{box-sizing:border-box}
.hi-canvas{position:absolute;inset:0;width:100%;height:100%}
.hi-vignette{position:absolute;inset:0;pointer-events:none;
  background:radial-gradient(ellipse at 50% 50%,transparent 55%,rgba(0,0,0,.55) 100%)}

/* slow rotating light rays behind everything */
.hi-rays{position:absolute;left:50%;top:${CY};width:190vmax;height:190vmax;margin:-95vmax 0 0 -95vmax;
  background:repeating-conic-gradient(from 0deg,rgba(255,196,90,.075) 0deg 6deg,transparent 6deg 22deg);
  -webkit-mask-image:radial-gradient(circle,#000 0%,transparent 34%);mask-image:radial-gradient(circle,#000 0%,transparent 34%);
  opacity:0;animation:hi-rays-in 1400ms ease 300ms forwards,hi-rot 40s linear infinite}
@keyframes hi-rays-in{to{opacity:1}}
@keyframes hi-rot{to{transform:rotate(360deg)}}

.hi-halo{position:absolute;left:50%;top:${CY};width:70vmin;height:70vmin;margin:-35vmin 0 0 -35vmin;border-radius:50%;
  background:radial-gradient(circle,rgba(255,190,90,.34) 0%,rgba(255,150,40,.12) 38%,transparent 68%);
  opacity:0;animation:hi-halo 1650ms ease-in 150ms forwards,hi-breathe 3.2s ease-in-out 1900ms infinite}
@keyframes hi-halo{0%{opacity:0;transform:scale(.4)}100%{opacity:1;transform:scale(1)}}
@keyframes hi-breathe{0%,100%{opacity:.8;transform:scale(1)}50%{opacity:1;transform:scale(1.08)}}

/* ---- 3D glass cube ---- */
.hi-scene{position:absolute;left:50%;top:${CY};width:0;height:0;perspective:850px}
.hi-cube3{--cs:min(27vw,128px);--h:calc(var(--cs) / 2);position:absolute;width:var(--cs);height:var(--cs);
  left:calc(var(--cs) / -2);top:calc(var(--cs) / -2);transform-style:preserve-3d;opacity:0;will-change:transform,opacity;
  animation:hi-spin 1750ms cubic-bezier(.45,.05,.25,1) 150ms forwards}
@keyframes hi-spin{
  0%{opacity:0;transform:translateZ(-700px) rotateX(-260deg) rotateY(-460deg) scale(.2)}
  12%{opacity:1}
  58%{transform:translateZ(0) rotateX(-24deg) rotateY(-38deg) scale(1)}
  86%{opacity:1;transform:translateZ(60px) rotateX(0deg) rotateY(0deg) scale(1.1)}
  100%{opacity:0;transform:translateZ(260px) rotateX(0deg) rotateY(0deg) scale(1.8)}}
.hi-face{position:absolute;inset:0;border-radius:16px;
  border:1.5px solid rgba(246,190,80,.9);
  background:linear-gradient(145deg,rgba(78,72,64,.92) 0%,rgba(28,26,24,.94) 60%,rgba(48,42,34,.92) 100%);
  box-shadow:inset 0 0 34px rgba(246,170,50,.32),0 0 22px rgba(246,170,50,.25)}
.hi-face-f{transform:translateZ(var(--h))}
.hi-face-b{transform:rotateY(180deg) translateZ(var(--h))}
.hi-face-r{transform:rotateY(90deg) translateZ(var(--h))}
.hi-face-l{transform:rotateY(-90deg) translateZ(var(--h))}
.hi-face-t{transform:rotateX(90deg) translateZ(var(--h))}
.hi-face-d{transform:rotateX(-90deg) translateZ(var(--h))}
.hi-face-b::after{transform:scaleX(-1)}
.hi-face-f::after,.hi-face-b::after{content:"";position:absolute;left:32%;top:26%;width:44%;height:48%;
  background:linear-gradient(160deg,#ffe08a,#f2a516);clip-path:polygon(0 0,100% 50%,0 100%);
  filter:drop-shadow(0 0 10px rgba(255,190,70,.9))}

/* ---- burst: flash, rings, anamorphic streak ---- */
.hi-flash{position:absolute;left:50%;top:${CY};width:14px;height:14px;margin:-7px 0 0 -7px;border-radius:50%;opacity:0;
  background:radial-gradient(circle,#fff 0%,rgba(255,210,130,.65) 40%,transparent 70%);
  animation:hi-flash 700ms ease-out ${BURST_MS}ms both}
@keyframes hi-flash{0%{opacity:1;transform:scale(1)}100%{opacity:0;transform:scale(90)}}
.hi-ring{position:absolute;left:50%;top:${CY};width:90px;height:90px;margin:-45px 0 0 -45px;border-radius:50%;opacity:0;
  border:2px solid rgba(255,206,110,.9);box-shadow:0 0 18px rgba(255,180,70,.6),inset 0 0 14px rgba(255,180,70,.35)}
.hi-ring1{animation:hi-ring 1100ms cubic-bezier(.1,.7,.2,1) ${BURST_MS}ms both}
.hi-ring2{animation:hi-ring 1300ms cubic-bezier(.1,.7,.2,1) ${BURST_MS + 130}ms both}
@keyframes hi-ring{0%{opacity:.95;transform:scale(.3)}100%{opacity:0;transform:scale(15)}}
.hi-flare{position:absolute;left:-10%;right:-10%;top:${CY};height:2px;margin-top:-1px;opacity:0;transform:scaleX(0);
  background:linear-gradient(90deg,transparent 0%,rgba(255,200,120,.65) 22%,#fff 50%,rgba(255,200,120,.65) 78%,transparent 100%);
  box-shadow:0 0 26px 6px rgba(255,180,80,.55);animation:hi-flare 1000ms ease-out ${BURST_MS}ms both}
@keyframes hi-flare{0%{opacity:1;transform:scaleX(0)}28%{opacity:1;transform:scaleX(1)}100%{opacity:0;transform:scaleX(1.15)}}

/* ---- wordmark ---- */
.hi-center{position:absolute;left:0;right:0;top:${CY};transform:translateY(-50%);display:flex;flex-direction:column;align-items:center}
.hi-logo{display:flex;font-family:'Arial Black','Segoe UI Black',system-ui,sans-serif;font-weight:900;
  font-size:min(16vw,92px);line-height:1;letter-spacing:-.025em;
  filter:drop-shadow(0 6px 0 rgba(0,0,0,.35)) drop-shadow(0 0 22px rgba(245,170,50,.18))}
.hi-letter{display:inline-block;opacity:0;transform:translateY(34px) skewX(-8deg) scale(1.35);filter:blur(16px);
  background-image:linear-gradient(110deg,#c9c9d3 0%,#e4e4ec 40%,#fff 50%,#e4e4ec 60%,#c9c9d3 100%);
  background-size:280% 100%;background-position:130% 0;
  -webkit-background-clip:text;background-clip:text;color:transparent;-webkit-text-fill-color:transparent;
  animation:hi-letter 780ms cubic-bezier(.16,.9,.25,1) var(--ld) forwards,
            hi-shine 1100ms ease-in-out calc(var(--ld) + 700ms) forwards}
.hi-gold{background-image:linear-gradient(110deg,#e8960c 0%,#f5b63a 40%,#fff3c4 50%,#f5b63a 60%,#e8960c 100%)}
@keyframes hi-letter{
  0%{opacity:0;transform:translateY(34px) skewX(-8deg) scale(1.35);filter:blur(16px)}
  60%{opacity:1;filter:blur(0)}
  100%{opacity:1;transform:translateY(0) skewX(-8deg) scale(1);filter:blur(0)}}
@keyframes hi-shine{from{background-position:130% 0}to{background-position:-30% 0}}

.hi-rule{height:2px;width:min(58vw,280px);margin-top:min(3.6vw,18px);border-radius:2px;transform:scaleX(0);opacity:0;
  background:linear-gradient(90deg,transparent,#f5b63a 30%,#fff3c4 50%,#f5b63a 70%,transparent);
  box-shadow:0 0 14px rgba(245,182,58,.6);animation:hi-rule 700ms cubic-bezier(.2,.8,.2,1) 2150ms forwards}
@keyframes hi-rule{to{transform:scaleX(1);opacity:1}}

/* rotating tagline */
.hi-tagwrap{position:relative;height:20px;width:100%;margin-top:min(3.6vw,16px)}
.hi-word{position:absolute;left:0;right:0;top:0;text-align:center;opacity:0;white-space:nowrap;
  font:600 14px/20px system-ui,-apple-system,'Segoe UI',sans-serif;letter-spacing:.34em;text-transform:uppercase;color:#cfcfd6;
  padding-left:.34em;animation:hi-word ${WORD_STEP}ms ease var(--wd) both}
.hi-word-last{color:#f5c25a;text-shadow:0 0 14px rgba(245,182,58,.55);animation:hi-word-last 600ms ease var(--wd) forwards}
@keyframes hi-word{0%{opacity:0;transform:translateY(10px);filter:blur(4px)}18%{opacity:1;transform:none;filter:none}78%{opacity:1;transform:none}100%{opacity:0;transform:translateY(-10px);filter:blur(4px)}}
@keyframes hi-word-last{0%{opacity:0;transform:translateY(10px);filter:blur(4px)}100%{opacity:1;transform:none;filter:none}}

@media (prefers-reduced-motion:reduce){.hi-rays{animation:none;opacity:1}}
`
