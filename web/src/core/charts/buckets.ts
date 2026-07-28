/**
 * A channel reduced to one point per bucket, ready to be drawn.
 *
 * The reduction is the **mean of each bucket**, and that is a deliberate reversal. The charts
 * used to be handed every sample, on the reasoning that a one-second heart-rate spike must
 * survive — which it did, at the cost that a five-hour ride rendered as a solid brush of ink
 * with no shape in it. A mean is the line the eye is looking for; the extremes are still
 * reported honestly, in the summary above the chart and in the range statistics below it, which
 * is where a number is read rather than a trend.
 *
 * Its own file, with no µPlot import, for the same reason `axisRange` is separate: this is
 * arithmetic over a series, it is testable without a rendering context, and importing the
 * component instead pulls in µPlot, which touches `matchMedia` at module scope.
 *
 * This is `ChartSeries.build` on the Kotlin side — same bucket count, same forward walk over the
 * x axis, same treatment of a bucket with nothing in it. Two clients drawing one ride at two
 * different smoothings are two different rides (SC-008).
 */

/**
 * Points across the chart. More than this is not distinguishable on a phone and buys nothing in
 * a browser either. `ChartSeries.BUCKETS` is the same number for the same reason.
 */
export const CHART_BUCKETS = 220

export interface Buckets {
  count: number
  /** The x value each bucket is drawn at — the x of the sample that represents it. */
  x: Float64Array
  /** A sample inside each bucket, so a cursor over the chart names a sample of the ride. */
  sample: Int32Array
  /** Half-open sample range per bucket: `[from[i], to[i])`. */
  from: Int32Array
  to: Int32Array
}

/**
 * Bucket boundaries for an x axis.
 *
 * Computed once per axis rather than once per channel: the boundaries depend only on x, and
 * every channel of one activity must be bucketed identically or the chips would swap between
 * series that disagree about where the ride's halfway point is.
 *
 * Found by walking x forward once, so an irregular axis — distance, where a stop contributes no
 * length at all — buckets exactly as evenly as time does.
 */
export function bucketize(x: Float64Array, count = CHART_BUCKETS): Buckets {
  const samples = x.length
  // Never more buckets than samples. The reduction assumes samples are denser than buckets,
  // which a five-hour ride certainly is — but a walk recorded once a minute is not, and asking
  // for two hundred buckets from thirty-six samples leaves most of them empty. Empty reads as a
  // gap, the path breaks at every one of them, and the chart renders as nothing at all.
  const buckets = Math.max(2, Math.min(count, samples))
  const at = new Float64Array(buckets)
  const sample = new Int32Array(buckets)
  const from = new Int32Array(buckets)
  const to = new Int32Array(buckets)
  if (samples === 0) return { count: buckets, x: at, sample, from, to }

  const min = x[0]!
  const max = x[samples - 1]!
  const span = max > min ? max - min : 1

  let index = 0
  for (let bucket = 0; bucket < buckets; bucket++) {
    const upper = min + (span * (bucket + 1)) / buckets
    const start = index
    // The last bucket takes whatever is left, so a rounding error cannot drop the final samples
    // of the ride off the right-hand edge.
    const last = bucket === buckets - 1
    while (index < samples && (last || x[index]! < upper)) index++
    const end = Math.max(start + 1, index)

    from[bucket] = start
    to[bucket] = end
    // The middle of what the bucket covered, so the readout under the pointer names a sample
    // inside the stretch the point was drawn from.
    const middle = Math.min(samples - 1, (start + end - 1) >> 1)
    sample[bucket] = middle
    at[bucket] = x[middle]!
  }

  return { count: buckets, x: at, sample, from, to }
}

/**
 * The mean of each bucket, with `null` where a bucket recorded nothing.
 *
 * `null` rather than `NaN` because this is handed to µPlot, which reads `null` as a gap and
 * anything else as a value — a `NaN` coordinate is silently ignored by the canvas and the path
 * simply continues, drawing a straight line through every tunnel in the ride.
 */
export function bucketMean(values: Float64Array, buckets: Buckets): (number | null)[] {
  const out = new Array<number | null>(buckets.count)
  for (let bucket = 0; bucket < buckets.count; bucket++) {
    let sum = 0
    let seen = 0
    for (let i = buckets.from[bucket]!; i < buckets.to[bucket]!; i++) {
      const value = values[i]
      if (value !== undefined && !Number.isNaN(value)) {
        sum += value
        seen++
      }
    }
    out[bucket] = seen === 0 ? null : sum / seen
  }
  return out
}

/** The same means as a dense array, for the scale arithmetic, with gaps as `NaN`. */
export function finiteOf(means: (number | null)[]): Float64Array {
  const out = new Float64Array(means.length)
  for (let i = 0; i < means.length; i++) out[i] = means[i] ?? Number.NaN
  return out
}
