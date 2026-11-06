import { useState } from 'react'
import { createPortal } from 'react-dom'
import {
  TYPE_OPTIONS, LANG_OPTIONS, YEAR_OPTIONS, SORT_OPTIONS, CHIP_OPTIONS,
  DEFAULT_FILTERS, getFilters, saveFilters,
} from '../lib/searchFilters'

function Row({ label, value, onChange, options }) {
  return (
    <label className="flex items-center py-2.5">
      <span className="w-28 shrink-0 text-reel-muted text-[15px]">{label}</span>
      <span className="relative flex-1">
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full appearance-none bg-transparent text-reel-ink font-bold text-[17px] pr-8 py-1 focus:outline-none"
        >
          {options.map(([v, l]) => (
            <option key={v} value={v} className="text-black">{l}</option>
          ))}
        </select>
        <svg className="absolute right-1 top-1/2 -translate-y-1/2 text-reel-muted pointer-events-none" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
      </span>
    </label>
  )
}

// "Search filters" dialog, same layout as YouTube's: labelled dropdown rows,
// feature chips, Cancel / Apply. Apply saves; Search.jsx re-filters live.
export default function SearchFilters({ onClose }) {
  const [f, setF] = useState(() => getFilters())
  const set = (patch) => setF((p) => ({ ...p, ...patch }))
  const toggleChip = (c) =>
    setF((p) => ({ ...p, chips: p.chips.includes(c) ? p.chips.filter((x) => x !== c) : [...p.chips, c] }))

  return createPortal(
    <div className="fixed inset-0 z-[70] flex items-center justify-center px-5" onClick={onClose}>
      <div className="absolute inset-0 bg-black/70" />
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-sm rounded-[28px] bg-reel-surface px-6 pt-6 pb-4 shadow-[0_16px_48px_rgba(0,0,0,0.7)] max-h-[88vh] overflow-y-auto"
        style={{ border: '1px solid color-mix(in srgb, var(--reel-ink) 14%, transparent)' }}
      >
        <p className="text-reel-ink text-[19px] mb-2">Search filters</p>
        <Row label="Type" value={f.type} onChange={(v) => set({ type: v })} options={TYPE_OPTIONS} />
        <Row label="Language" value={f.lang} onChange={(v) => set({ lang: v })} options={LANG_OPTIONS} />
        <Row label="Release" value={f.year} onChange={(v) => set({ year: v })} options={YEAR_OPTIONS} />
        <Row label="Prioritize" value={f.sort} onChange={(v) => set({ sort: v })} options={SORT_OPTIONS} />

        <p className="text-reel-muted text-[15px] mt-4 mb-2.5">Features</p>
        <div className="flex flex-wrap gap-2.5">
          {CHIP_OPTIONS.map(([id, label]) => {
            const on = f.chips.includes(id)
            return (
              <button
                key={id}
                type="button"
                onClick={() => toggleChip(id)}
                className={`px-4 py-2 rounded-full text-[15px] transition active:scale-95 ${
                  on ? 'bg-reel-gold text-reel-bg font-semibold' : 'bg-reel-surface2 text-reel-ink'
                }`}
              >
                {label}
              </button>
            )
          })}
        </div>

        <div className="flex items-center justify-between mt-6">
          <button type="button" onClick={() => setF({ ...DEFAULT_FILTERS })} className="text-sm text-reel-muted px-1 py-2">
            Reset
          </button>
          <div className="flex gap-6">
            <button type="button" onClick={onClose} className="text-[17px] font-semibold text-reel-ink py-2">Cancel</button>
            <button
              type="button"
              onClick={() => {
                saveFilters(f)
                onClose()
              }}
              className="text-[17px] font-semibold text-reel-gold py-2"
            >
              Apply
            </button>
          </div>
        </div>
      </div>
    </div>,
    document.body
  )
}
