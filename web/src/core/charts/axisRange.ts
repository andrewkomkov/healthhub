/**
 * How wide an axis a channel gets, which is not simply its own minimum and maximum.
 *
 * Its own file, with no µPlot import, for the same reason `ChartSeries.kt` is separate from the
 * canvas that draws it: this is arithmetic about a distribution, it is the half most likely to
 * be wrong, and it should be testable without a rendering context. Importing the component
 * instead pulls in µPlot, which touches `matchMedia` at module scope and cannot be loaded in a
 * test environment at all.
 */

/** Below this there is no distribution to speak of, only a short recording. */
const MIN_VALUES_TO_TRIM = 16

/** Tukey's constant. 1.5 IQR is the conventional fence for a mild outlier. */
const FENCE = 1.5

/** Headroom above and below the series, as a fraction of its own span. */
const RANGE_PAD = 0.08

/** Linear interpolation between the two order statistics either side of `fraction`. */
function quantile(sorted: number[], fraction: number): number {
  const position = (sorted.length - 1) * fraction
  const lower = Math.floor(position)
  const upper = Math.min(lower + 1, sorted.length - 1)
  const weight = position - lower
  return sorted[lower]! * (1 - weight) + sorted[upper]! * weight
}

/**
 * The range the axis is drawn against, which is not the same as the data's own bounds.
 *
 * One sample of near-zero speed — a walk pausing at a crossing — is a pace of 122 minutes per
 * kilometre, and scaling to it squashes the whole workout into a sliver along the bottom edge of
 * an otherwise empty panel. Tukey fences rather than a fixed percentage off each end, because the
 * shape of the tail differs per channel and a walk that stops four times has four outliers, not
 * one. µPlot clamps drawn points to the scale, so a sample beyond a fence still reads as a line
 * running along the edge rather than vanishing.
 *
 * This is `ChartSeries.trimmedRange` on the Kotlin side, deliberately the same rule and the same
 * constants: the two clients must not disagree about the shape of the same ride.
 */
export function axisRange(values: Float64Array): [number, number] | null {
  const finite: number[] = []
  for (const value of values) if (!Number.isNaN(value)) finite.push(value)
  if (finite.length === 0) return null

  let min = finite[0]!
  let max = finite[0]!
  for (const value of finite) {
    if (value < min) min = value
    if (value > max) max = value
  }

  let lo = min
  let hi = max
  if (finite.length >= MIN_VALUES_TO_TRIM) {
    const sorted = [...finite].sort((a, b) => a - b)
    const q1 = quantile(sorted, 0.25)
    const q3 = quantile(sorted, 0.75)
    const iqr = q3 - q1
    if (iqr > 0) {
      const lowFence = Math.max(q1 - FENCE * iqr, min)
      const highFence = Math.min(q3 + FENCE * iqr, max)
      if (highFence > lowFence) {
        lo = lowFence
        hi = highFence
      }
    }
  }

  // A flat channel — a turbo trainer holding 200 W — would otherwise be a zero-height scale
  // drawn on one edge; giving it a span puts the line through the middle instead.
  const span = hi - lo > 0 ? hi - lo : 1
  return [lo - span * RANGE_PAD, hi + span * RANGE_PAD]
}
