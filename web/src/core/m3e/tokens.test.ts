import { describe, expect, it } from 'vitest'
import { generatedKotlin, propertiesFor, tokens } from './tokenSource'

/**
 * The two clients cannot drift.
 *
 * `packages/design-tokens/tokens.json` generates a CSS sheet and a Kotlin object, and until
 * this file existed nothing checked that the two ended up carrying the same design. They had
 * already drifted twice — the sequential ramp and the elevation scale reached the web and
 * never reached Android — and both times it was found by reading the generator, months later.
 *
 * Everything below compares the *generated artefacts*, not the source. A test that read
 * tokens.json twice would agree with itself no matter what the generator did.
 */

const kebab = (name: string) => name.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()

/** #RRGGBB for a Kotlin `Color(0xFFRRGGBB)` literal, wherever it sits in the line. */
function fromArgb(literal: string): string {
  const match = /0x[0-9A-F]{2}([0-9A-F]{6})/.exec(literal)
  if (!match) throw new Error(`Not an ARGB literal: ${literal}`)
  return `#${(match[1] as string).toLowerCase()}`
}

const hex = (value: string | undefined) => value?.toLowerCase()

/** The parenthesised body of `val <name> … ( … )`, matched paren for paren. */
function block(name: string): string {
  const start = generatedKotlin.indexOf(`val ${name}`)
  if (start < 0) throw new Error(`GeneratedTokens.kt has no '${name}'`)
  const open = generatedKotlin.indexOf('(', start)
  let depth = 0
  for (let i = open; i < generatedKotlin.length; i++) {
    const character = generatedKotlin[i]
    if (character === '(') depth++
    if (character === ')') {
      depth--
      if (depth === 0) return generatedKotlin.slice(open, i + 1)
    }
  }
  throw new Error(`Unbalanced parentheses after '${name}'`)
}

function namedColors(name: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [, role, hex] of block(name).matchAll(/(\w+) = Color\((0x[0-9A-F]{8})\)/g)) {
    out[role as string] = fromArgb(hex as string)
  }
  return out
}

function colorList(name: string): string[] {
  return [...block(name).matchAll(/Color\((0x[0-9A-F]{8})\)/g)].map(([, hex]) =>
    fromArgb(hex as string),
  )
}

function pairs(name: string, pattern: RegExp): Record<string, string> {
  const out: Record<string, string> = {}
  for (const match of block(name).matchAll(pattern)) {
    out[match[1] as string] = match[2] as string
  }
  return out
}

/** A single `val <name> ... = <value>` outside any block. */
function scalar(name: string): string {
  const match = new RegExp(`val ${name}(?::[^=]+)? = (.+)`).exec(generatedKotlin)
  if (!match) throw new Error(`GeneratedTokens.kt has no '${name}'`)
  return (match[1] as string).trim()
}

const cssColor = (properties: Map<string, string>, role: string) =>
  hex(properties.get(`--md-sys-color-${kebab(role)}`))

/**
 * Roles that legitimately reach one client and not the other. Every entry is a fact about a
 * platform, not a shortcut — if this list grows for any other reason, the drift is real.
 */
const EXPECTED_ASYMMETRY = {
  // Compose derives shadows from elevation and has no ColorScheme slot for the colour; passing
  // one is a compile error. The web draws its own shadows and does use it.
  colorRoles: ['shadow'],
  // CSS needs a font stack to hand the browser; Compose resolves the platform default, so
  // there is no Android value to compare against.
  typography: ['fontFamily'],
}

describe('generated token parity', () => {
  for (const mode of ['light', 'dark'] as const) {
    describe(mode, () => {
      const css = propertiesFor(mode)

      it('carries every colour role at the same value', () => {
        const expected = Object.fromEntries(
          Object.entries(tokens.color[mode]).map(([role, hex]) => [role, hex.toLowerCase()]),
        )
        const fromCss = Object.fromEntries(
          Object.keys(expected).map((role) => [role, cssColor(css, role)]),
        )
        expect(fromCss).toEqual(expected)

        const kotlin = namedColors(`${mode}ColorScheme`)
        for (const role of EXPECTED_ASYMMETRY.colorRoles) delete expected[role]
        expect(kotlin).toEqual(expected)
      })

      it('carries the series palette in the same slot order', () => {
        const expected = tokens.chart.series[mode].map((hex) => hex.toLowerCase())
        const fromCss = expected.map((_, index) => hex(css.get(`--chart-series-${index + 1}`)))
        expect(fromCss).toEqual(expected)
        expect(colorList(`chartSeries${mode === 'light' ? 'Light' : 'Dark'}`)).toEqual(expected)
      })

      it('carries the same chart chrome', () => {
        const expected = Object.fromEntries(
          Object.entries(tokens.chart.chrome[mode]).map(([role, hex]) => [
            role,
            hex.toLowerCase(),
          ]),
        )
        const fromCss = Object.fromEntries(
          Object.keys(expected).map((role) => [role, hex(css.get(`--chart-${kebab(role)}`))]),
        )
        expect(fromCss).toEqual(expected)
        expect(namedColors(`chartChrome${mode === 'light' ? 'Light' : 'Dark'}`)).toEqual(expected)
      })

      it('carries the same status colours', () => {
        const expected = Object.fromEntries(
          Object.entries(tokens.chart.status[mode]).map(([role, hex]) => [role, hex.toLowerCase()]),
        )
        const fromCss = Object.fromEntries(
          Object.keys(expected).map((role) => [role, hex(css.get(`--chart-status-${kebab(role)}`))]),
        )
        expect(fromCss).toEqual(expected)
        expect(namedColors(`status${mode === 'light' ? 'Light' : 'Dark'}`)).toEqual(expected)
      })

      it('lays ordinal scales out over the same span of the ramp', () => {
        const floor = mode === 'light' ? tokens.chart.sequential.ordinalFloorLight : 1
        const ceiling =
          mode === 'dark' ? tokens.chart.sequential.ordinalCeilingDark : tokens.chart.sequential.steps.length
        expect(css.get('--chart-sequential-floor')).toBe(String(floor))
        expect(css.get('--chart-sequential-ceiling')).toBe(String(ceiling))
      })
    })
  }

  it('carries the same sequential ramp and its bounds', () => {
    const expected = tokens.chart.sequential.steps.map((hex) => hex.toLowerCase())
    const css = propertiesFor('light')
    expect(expected.map((_, i) => hex(css.get(`--chart-sequential-${i + 1}`)))).toEqual(expected)
    expect(colorList('chartSequential')).toEqual(expected)
    expect(scalar('sequentialFloorLight')).toBe(String(tokens.chart.sequential.ordinalFloorLight))
    expect(scalar('sequentialCeilingDark')).toBe(String(tokens.chart.sequential.ordinalCeilingDark))
  })

  it('carries the same diverging scale', () => {
    const css = propertiesFor('light')
    expect(hex(css.get('--chart-diverging-negative'))).toBe(tokens.chart.diverging.negative)
    expect(hex(css.get('--chart-diverging-positive'))).toBe(tokens.chart.diverging.positive)
    expect(hex(propertiesFor('dark').get('--chart-diverging-midpoint'))).toBe(
      tokens.chart.diverging.midpoint.dark,
    )
    expect(fromArgb(scalar('divergingNegative'))).toBe(tokens.chart.diverging.negative)
    expect(fromArgb(scalar('divergingPositive'))).toBe(tokens.chart.diverging.positive)
    expect(fromArgb(scalar('divergingMidpointLight'))).toBe(tokens.chart.diverging.midpoint.light)
    expect(fromArgb(scalar('divergingMidpointDark'))).toBe(tokens.chart.diverging.midpoint.dark)
  })

  it('gives every channel the same slot on both clients', () => {
    const css = propertiesFor('light')
    const kotlin = pairs('channelSlots', /"(\w+)" to (\d+),/g)
    for (const [channel, slot] of Object.entries(tokens.chart.channelSlots)) {
      // The web indirects through the series variable rather than restating a hex, which is
      // what keeps a channel following the light/dark scheme.
      expect(css.get(`--chart-channel-${kebab(channel)}`)).toBe(`var(--chart-series-${slot + 1})`)
      expect(kotlin[channel]).toBe(String(slot))
    }
    expect(Object.keys(kotlin)).toEqual(Object.keys(tokens.chart.channelSlots))
  })

  it('carries the same type scale, in rem on the web and sp on Android', () => {
    const css = propertiesFor('light')
    const kotlin = pairs(
      'typeScale',
      /"(\w+)" to TypeToken\(([-\d.]+)\.sp, ([-\d.]+)\.sp, (\d+), ([-\d.]+)\.sp\)/g,
    )
    const kotlinRaw = [
      ...block('typeScale').matchAll(
        /"(\w+)" to TypeToken\(([-\d.]+)\.sp, ([-\d.]+)\.sp, (\d+), ([-\d.]+)\.sp\)/g,
      ),
    ]
    expect(Object.keys(kotlin)).toEqual(Object.keys(tokens.typography.scale))

    for (const match of kotlinRaw) {
      const role = match[1] as string
      const token = tokens.typography.scale[role]
      expect(token, `${role} is generated but not declared`).toBeDefined()
      expect([match[2], match[3], match[4], match[5]].map(Number)).toEqual([
        token!.size,
        token!.lineHeight,
        token!.weight,
        token!.tracking,
      ])

      const key = `--md-sys-type-${kebab(role)}`
      expect(rem(css.get(`${key}-size`))).toBe(token!.size)
      expect(rem(css.get(`${key}-line-height`))).toBe(token!.lineHeight)
      expect(rem(css.get(`${key}-tracking`))).toBe(token!.tracking)
      expect(css.get(`${key}-weight`)).toBe(String(token!.weight))
    }
    expect(EXPECTED_ASYMMETRY.typography).toEqual(['fontFamily'])
    expect(css.get('--md-sys-type-font-family')).toBe(tokens.typography.fontFamily)
  })

  it('carries the same shape, spacing and elevation scales', () => {
    const css = propertiesFor('light')
    const shape = pairs('shapeScale', /"(\w+)" to ([\d.]+)\.dp,/g)
    for (const [name, px] of Object.entries(tokens.shape)) {
      expect(css.get(`--md-sys-shape-${kebab(name)}`)).toBe(name === 'full' ? '9999px' : `${px}px`)
      expect(shape[name]).toBe(String(px))
    }

    const spacingKotlin: Record<string, string> = {}
    for (const [, name, px] of generatedKotlin.matchAll(/val (\w+): Dp = ([\d.]+)\.dp/g)) {
      spacingKotlin[name as string] = px as string
    }
    for (const [name, px] of Object.entries(tokens.spacing)) {
      expect(css.get(`--hh-space-${kebab(name)}`)).toBe(`${px}px`)
      expect(spacingKotlin[name]).toBe(String(px))
    }

    const elevation = pairs('elevationScale', /"(\w+)" to ([\d.]+)\.dp,/g)
    for (const [name, dp] of Object.entries(tokens.elevation)) {
      expect(css.get(`--md-sys-elevation-${kebab(name)}`)).toBe(`${dp}px`)
      expect(elevation[name]).toBe(String(dp))
    }
  })

  it('carries the same mark geometry', () => {
    const css = propertiesFor('light')
    for (const [name, px] of Object.entries(tokens.chart.marks)) {
      if (name.startsWith('$')) continue
      expect(css.get(`--chart-mark-${kebab(name)}`)).toBe(`${px as number}px`)
      expect(scalar(name)).toBe(`${px as number}.dp`)
    }
  })

  it('names the same motion springs on both sides', () => {
    const css = propertiesFor('light')
    const springs = Object.keys(pairs('springs', /"([\w.]+)" to SpringToken\(([\d.]+)f/g))
    // CSS has no spring primitive, so the web gets an easing per spring rather than the
    // spring itself. What must agree is that every spring has a counterpart.
    const easings = [...css.keys()]
      .filter((name) => name.startsWith('--md-sys-motion-'))
      .map((name) => name.replace('--md-sys-motion-', ''))
    expect(springs.map((name) => kebab(name.replace('.', '-'))).sort()).toEqual(easings.sort())
  })
})

/** A `rem` custom property back in the px the token file declares. */
function rem(value: string | undefined): number {
  if (value === undefined) return Number.NaN
  expect(value.endsWith('rem'), `${value} must be rem so it follows the reader's font size`).toBe(
    true,
  )
  return Number((Number.parseFloat(value) * 16).toFixed(5))
}
