import { describe, expect, it } from 'vitest'
import { contrastRatio, separation, type Vision } from './contrast'
import { tokens } from './tokenSource'

/**
 * SC-011, checked rather than claimed.
 *
 * "Text and essential graphics meet standard contrast expectations on all screens, including
 * charts." The thresholds are WCAG 2's: 4.5:1 for body text, 3:1 for large text and for any
 * graphic that carries meaning. Everything below is measured against the surface the thing is
 * actually drawn on, in both appearances, because a palette that passes on one and fails on
 * the other passes half the product.
 *
 * Two exemption lists appear here. Both are pinned to exact contents, so a future change that
 * adds a ninth failing pairing fails this file instead of shipping.
 */

const TEXT = 4.5
const GRAPHIC = 3

/** Surfaces text and marks actually sit on: the page, and the cards laid over it. */
const backgrounds = (mode: 'light' | 'dark') => {
  const color = tokens.color[mode]
  return {
    surface: color['surface'] as string,
    card: color['surfaceContainerLow'] as string,
    raised: color['surfaceContainerHigh'] as string,
  }
}

function ratio(foreground: string, background: string): number {
  return Number(contrastRatio(foreground, background).toFixed(2))
}

describe.each(['light', 'dark'] as const)('%s appearance', (mode) => {
  const color = tokens.color[mode]
  const chrome = tokens.chart.chrome[mode]

  /** Every `onX` against its `X`, plus `onSurface` against the whole container family. */
  const textPairs = (): [string, string][] => {
    const pairs: [string, string][] = []
    for (const role of Object.keys(color)) {
      if (!role.startsWith('on')) continue
      const surface = role.slice(2, 3).toLowerCase() + role.slice(3)
      if (color[surface]) pairs.push([role, surface])
    }
    for (const surface of Object.keys(color)) {
      if (surface.startsWith('surfaceContainer') || surface === 'surfaceDim' || surface === 'surfaceBright') {
        pairs.push(['onSurface', surface], ['onSurfaceVariant', surface])
      }
    }
    pairs.push(['primary', 'surface'], ['error', 'surface'], ['inversePrimary', 'inverseSurface'])
    return pairs
  }

  it.each(textPairs())('reads %s on %s', (foreground, background) => {
    expect(ratio(color[foreground] as string, color[background] as string)).toBeGreaterThanOrEqual(
      TEXT,
    )
  })

  it('draws chart ink and the outline as legible graphics', () => {
    // Axis labels and tick values are text, so they answer to 4.5:1 — against the chart's own
    // surface token and against the card the chart stack is laid on, which is not the same
    // colour and is the one a reader is actually looking at.
    for (const ink of ['inkPrimary', 'inkSecondary', 'inkMuted'] as const) {
      for (const background of [chrome['surface'] as string, backgrounds(mode).card]) {
        expect(ratio(chrome[ink] as string, background), `${ink} on ${background}`)
          .toBeGreaterThanOrEqual(TEXT)
      }
    }
    // The outline is a control boundary, so it is an essential graphic rather than text.
    // `outlineVariant` is deliberately not here: Material defines it for decorative dividers,
    // and so does this product — every divider drawn with it has a labelled thing beside it.
    for (const background of Object.values(backgrounds(mode))) {
      expect(ratio(color['outline'] as string, background)).toBeGreaterThanOrEqual(GRAPHIC)
    }
  })

  it('draws every status colour as an essential graphic on both surfaces', () => {
    for (const [name, hex] of Object.entries(tokens.chart.status[mode])) {
      for (const background of [backgrounds(mode).surface, backgrounds(mode).card]) {
        expect(ratio(hex, background), `${name} on ${background}`).toBeGreaterThanOrEqual(GRAPHIC)
      }
    }
  })

  /**
   * The light palette has three slots below 3:1 against the page, which is what obliges the
   * relief rule: every channel gets its own panel, its own name and its own value readout, so
   * identity is never carried by colour alone. The exemption is the exact set, not a count.
   */
  const LOW_CONTRAST_SLOTS: Record<'light' | 'dark', number[]> = {
    light: [3, 4, 5],
    dark: [],
  }

  it('keeps the series palette above 3:1 except for the slots the relief rule covers', () => {
    const below: number[] = []
    tokens.chart.series[mode].forEach((hex, index) => {
      if (contrastRatio(hex, chrome['surface'] as string) < GRAPHIC) below.push(index + 1)
    })
    expect(below).toEqual(LOW_CONTRAST_SLOTS[mode])
  })

  it('keeps the usable span of the sequential ramp legible on the track it is drawn on', () => {
    // A zone bar is drawn inside a `surfaceContainerHighest` track, which is the strictest
    // background the ramp ever meets — stricter than the page, and it is what decides the
    // bounds. Outside this span the bar reads as an empty track.
    const track = color['surfaceContainerHighest'] as string
    const steps = tokens.chart.sequential.steps
    const floor = mode === 'light' ? tokens.chart.sequential.ordinalFloorLight : 1
    const ceiling = mode === 'dark' ? tokens.chart.sequential.ordinalCeilingDark : steps.length

    for (let step = floor; step <= ceiling; step++) {
      expect(ratio(steps[step - 1] as string, track), `step ${step}`).toBeGreaterThanOrEqual(
        GRAPHIC,
      )
    }
    // And the bound is tight: the step just outside it does not clear the bar, or the span is
    // needlessly narrow and the ramp has lost resolution for nothing.
    const outside = mode === 'light' ? floor - 1 : ceiling + 1
    expect(ratio(steps[outside - 1] as string, track)).toBeLessThan(GRAPHIC)
  })

  it('keeps both ends of the diverging scale legible, and the midpoint neutral', () => {
    const { negative, positive, midpoint } = tokens.chart.diverging
    for (const end of [negative, positive]) {
      expect(ratio(end, chrome['surface'] as string)).toBeGreaterThanOrEqual(GRAPHIC)
      // Not 3:1 against the midpoint: a neutral zero is meant to be quiet, and WCAG's
      // non-text rule is about the background, not about the next colour along the scale.
      // What must hold is that neither end is mistakable for "no deviation".
      expect(ratio(end, midpoint[mode])).toBeGreaterThanOrEqual(2)
    }
  })
})

/**
 * Colour-vision deficiency, measured.
 *
 * The claim written into `DynamicColors.kt` and `dynamicTheme.ts` is that the series palette
 * is validated for CVD. It is — and the measurement says the light palette clears ΔE 10 on
 * every pair while the dark one does not. Eight categorical hues cannot survive three
 * simulations at once; that is why the relief rule exists rather than being optional. The weak
 * pairs are pinned so a change that adds another one is a failure, not a discovery.
 */
describe('colour-vision deficiency', () => {
  const VISIONS: Vision[] = ['protanopia', 'deuteranopia', 'tritanopia']
  const SEPARATED = 10

  /** slot pair (1-based) → the floor it actually achieves, rounded down. */
  const KNOWN_COLLISIONS: Record<string, Record<string, number>> = {
    light: {},
    dark: {
      'protanopia 1/7': 2,
      'deuteranopia 3/5': 4,
      'tritanopia 5/8': 7,
      'protanopia 3/8': 9,
      'tritanopia 2/8': 9,
    },
  }

  it.each(['light', 'dark'] as const)('%s: every pair is either separated or pinned', (mode) => {
    const series = tokens.chart.series[mode]
    const measured: Record<string, number> = {}

    for (const vision of VISIONS) {
      for (let i = 0; i < series.length; i++) {
        for (let j = i + 1; j < series.length; j++) {
          const delta = separation(series[i] as string, series[j] as string, vision)
          if (delta < SEPARATED) measured[`${vision} ${i + 1}/${j + 1}`] = Math.floor(delta)
        }
      }
    }
    expect(measured).toEqual(KNOWN_COLLISIONS[mode])
  })

  it.each(['light', 'dark'] as const)('%s: every slot stays off the surface', (mode) => {
    const surface = tokens.chart.chrome[mode]['surface'] as string
    for (const vision of [...VISIONS, 'none' as const]) {
      for (const hex of tokens.chart.series[mode]) {
        expect(separation(hex, surface, vision), `${hex} under ${vision}`).toBeGreaterThan(20)
      }
    }
  })
})
