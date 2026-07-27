import { afterEach, describe, expect, it } from 'vitest'
import { describePanel } from '../charts/describe'
import { toPixels } from '../charts/theme'
import { repoFile } from './tokenSource'

/**
 * The accessibility promises the foundation makes, in the places they are actually kept.
 *
 * These are the ones that live in `base.css` and in the two token-aware renderers — a focus
 * ring, a touch target, a table that can still be reached at 200% text, and a chart canvas
 * that says something to a reader who cannot see it. Screen-by-screen behaviour is not
 * checked here; this is the layer everything else inherits, so a regression at this level is
 * a regression on every screen at once.
 */

const base = repoFile('web', 'src', 'core', 'm3e', 'base.css')

/** The declarations of the first rule whose selector matches, with comments stripped. */
function rule(selector: string): string {
  const stripped = base.replace(/\/\*[\s\S]*?\*\//g, '')
  const pattern = new RegExp(`(^|[},])\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'm')
  const match = pattern.exec(stripped)
  if (!match) throw new Error(`base.css has no rule for '${selector}'`)
  return match[2] as string
}

describe('the foundation', () => {
  it('gives keyboard focus a ring of its own, from the palette', () => {
    const focus = rule(':where(a, button, input, select, textarea, summary, [tabindex]):focus-visible')
    expect(focus).toMatch(/outline:\s*\d+px solid var\(--md-sys-color-[a-z-]+\)/)
    expect(focus).toMatch(/outline-offset/)
    // A blanket `outline: none` anywhere would silently undo it for whatever it covers.
    const suppressions = [...base.matchAll(/([^{}]+)\{[^}]*outline:\s*none/g)].map(([, selector]) =>
      (selector as string).trim(),
    )
    for (const selector of suppressions) {
      expect(selector, `${selector} removes the focus ring for keyboard users too`).toContain(
        ':not(:focus-visible)',
      )
    }
  })

  it('gives every control a 48px target', () => {
    expect(rule('.m3-button')).toMatch(/min-height:\s*48px/)
    expect(rule('.m3-field > input')).toMatch(/min-height:\s*(4[89]|5\d|\d{3})px/)
    // The chip is 40px of shape with a 48px hit area laid over it, because growing the chip
    // itself would push every row apart to solve a problem that only exists under a thumb.
    expect(rule('.hh-chip::after')).toMatch(/min-height:\s*48px/)
    expect(rule('.hh-chip')).toMatch(/position:\s*relative/)
  })

  it('lets a table scroll rather than be clipped when the text is large', () => {
    expect(rule('.m3-card')).toMatch(/overflow:\s*hidden/)
    expect(rule('.hh-section:has(> .hh-table)')).toMatch(/overflow-x:\s*auto/)
  })

  it('breaks its layouts on a rem query, so text size counts as well as viewport width', () => {
    expect(base).not.toMatch(/@media \([^)]*width:\s*\d+px/)
    expect(base).toMatch(/@media \(max-width: [\d.]+rem\)/)
  })
})

describe('the largest supported text size', () => {
  afterEach(() => {
    document.documentElement.style.fontSize = ''
  })

  it('resolves a rem token against the reader\'s own root size', () => {
    expect(toPixels('0.6875rem', 11)).toBeCloseTo(11)
    document.documentElement.style.fontSize = '32px'
    // 200% text: the canvas has to be told in pixels, and the pixels have to have moved.
    expect(toPixels('0.6875rem', 11)).toBeCloseTo(22)
  })

  it('falls back rather than producing NaN for a value it cannot parse', () => {
    expect(toPixels('', 11)).toBe(11)
    expect(toPixels('inherit', 13)).toBe(13)
  })
})

describe('a chart panel', () => {
  const panel = (values: number[], summary?: string) => ({
    key: 'hr',
    label: 'Heart rate',
    values: Float64Array.from(values),
    format: (value: number) => `${Math.round(value)} bpm`,
    ...(summary === undefined ? {} : { summary }),
  })

  it('describes its extent, which is the part of a canvas that can be spoken', () => {
    expect(describePanel(panel([120, 178, 45], '143 bpm'))).toBe(
      'Heart rate chart, 45 bpm to 178 bpm, 143 bpm overall',
    )
  })

  it('ignores the gaps rather than reporting NaN as a reading', () => {
    expect(describePanel(panel([Number.NaN, 90, Number.NaN, 150]))).toBe(
      'Heart rate chart, 90 bpm to 150 bpm',
    )
  })

  it('says so when the channel recorded nothing at all', () => {
    expect(describePanel(panel([Number.NaN, Number.NaN]))).toBe('Heart rate: nothing was recorded')
  })
})
