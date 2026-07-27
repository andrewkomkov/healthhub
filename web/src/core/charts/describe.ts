/**
 * What a chart canvas says to somebody who cannot see it.
 *
 * A `<canvas>` is a hole in the accessibility tree, and a chart whose only description is
 * "Heart rate" tells a screen-reader user nothing the panel header did not already say. The
 * shape of the line cannot be spoken; its extent can, and that is what the summary is usually
 * being read for.
 *
 * It lives beside the chart rather than inside it because importing µPlot drags in a canvas
 * and a `matchMedia` call at module scope — this is a pure string function and should be
 * testable without either.
 */
export interface DescribablePanel {
  label: string
  values: Float64Array
  format: (value: number) => string
  summary?: string
}

export function describePanel(panel: DescribablePanel): string {
  let min = Infinity
  let max = -Infinity
  for (let i = 0; i < panel.values.length; i++) {
    const value = panel.values[i]!
    if (Number.isNaN(value)) continue
    if (value < min) min = value
    if (value > max) max = value
  }
  if (min > max) return `${panel.label}: nothing was recorded`
  const overall = panel.summary ? `, ${panel.summary} overall` : ''
  return `${panel.label} chart, ${panel.format(min)} to ${panel.format(max)}${overall}`
}
