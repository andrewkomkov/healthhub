/**
 * The colour arithmetic behind SC-011.
 *
 * The criterion says text and essential graphics meet standard contrast expectations on every
 * screen, including charts. That is a claim about the token file, and until now it was only
 * ever asserted in prose — so this module exists to let `contrast.test.ts` check it instead.
 *
 * Two measures, because they answer different questions. WCAG contrast answers "can this be
 * read against what is behind it"; CIELAB separation under a colour-vision simulation answers
 * "can these two series be told apart by somebody who does not see red and green". A palette
 * can pass the first comprehensively and fail the second, which is exactly what the chart
 * series palette does in dark mode — see the note in tokens.json.
 */

export type Rgb = readonly [number, number, number]

/** Colour-vision deficiencies simulated here. `none` is the identity, kept for symmetry. */
export type Vision = 'none' | 'protanopia' | 'deuteranopia' | 'tritanopia'

export function parseHex(hex: string): Rgb {
  const value = hex.trim().replace('#', '')
  if (!/^[0-9a-fA-F]{6}$/.test(value)) throw new Error(`Not a #RRGGBB colour: ${hex}`)
  return [
    Number.parseInt(value.slice(0, 2), 16) / 255,
    Number.parseInt(value.slice(2, 4), 16) / 255,
    Number.parseInt(value.slice(4, 6), 16) / 255,
  ]
}

const toLinear = (channel: number) =>
  channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4

export function relativeLuminance(hex: string): number {
  const [r, g, b] = parseHex(hex).map(toLinear) as unknown as Rgb
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG 2 contrast ratio, 1 to 21. */
export function contrastRatio(a: string, b: string): number {
  const first = relativeLuminance(a)
  const second = relativeLuminance(b)
  const [lighter, darker] = first > second ? [first, second] : [second, first]
  return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Machado, Oliveira and Fernandes (2009) simulation matrices at full severity, applied in
 * linear RGB. Chosen over Brettel/Viénot because it is a single matrix per deficiency with no
 * confusion-line projection to get subtly wrong, and the published coefficients are what every
 * other implementation of this check uses — so a number here is comparable with one elsewhere.
 */
const MATRICES: Record<Exclude<Vision, 'none'>, readonly Rgb[]> = {
  protanopia: [
    [0.152286, 1.052583, -0.204868],
    [0.114503, 0.786281, 0.099216],
    [-0.003882, -0.048116, 1.051998],
  ],
  deuteranopia: [
    [0.367322, 0.860646, -0.227968],
    [0.280085, 0.672501, 0.047413],
    [-0.01182, 0.04294, 0.968881],
  ],
  tritanopia: [
    [1.255528, -0.076749, -0.178779],
    [-0.078411, 0.930809, 0.147602],
    [0.004733, 0.691367, 0.3039],
  ],
}

function simulateLinear(hex: string, vision: Vision): Rgb {
  const linear = parseHex(hex).map(toLinear) as unknown as Rgb
  if (vision === 'none') return linear
  const matrix = MATRICES[vision]
  return matrix.map((row) =>
    Math.min(1, Math.max(0, row[0] * linear[0] + row[1] * linear[1] + row[2] * linear[2])),
  ) as unknown as Rgb
}

/** CIELAB under D65, from linear sRGB. */
function lab([r, g, b]: Rgb): Rgb {
  const x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
  const y = 0.2126 * r + 0.7152 * g + 0.0722 * b
  const z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
  const f = (v: number) => (v > 0.008856 ? Math.cbrt(v) : 7.787 * v + 16 / 116)
  return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))]
}

/**
 * CIE76 colour difference between two colours as a given eye would see them.
 *
 * CIE76 rather than CIEDE2000: the thresholds this is compared against are order-of-magnitude
 * judgements ("these two are the same colour to a protanope"), and the simpler formula makes
 * the number reproducible by hand.
 */
export function separation(a: string, b: string, vision: Vision = 'none'): number {
  const first = lab(simulateLinear(a, vision))
  const second = lab(simulateLinear(b, vision))
  return Math.hypot(first[0] - second[0], first[1] - second[1], first[2] - second[2])
}
