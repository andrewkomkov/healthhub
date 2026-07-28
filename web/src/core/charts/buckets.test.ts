import { describe, expect, it } from 'vitest'
import { bucketMean, bucketize, finiteOf } from './buckets'

/**
 * The reduction, pinned on both clients.
 *
 * These are the same cases as `ChartSeriesTest` on the Kotlin side, with the same numbers. The
 * two implementations are separate because one draws on a Compose canvas and the other hands an
 * array to µPlot, but they must not disagree about the shape of the same ride — an athlete
 * comparing the phone to the browser is comparing two pictures of one walk.
 */
describe('bucketize', () => {
  it('never asks for more buckets than there are samples', () => {
    // A 46-minute walk sampled once a minute, asked to fill a chart with 220 points in it.
    const x = Float64Array.from({ length: 36 }, (_, i) => i * 77)

    const buckets = bucketize(x)

    // Two hundred buckets from thirty-six samples leaves most of them empty, every empty one
    // breaks the path, and the chart renders as nothing at all beside a correct axis.
    expect(buckets.count).toBe(36)
    expect(bucketMean(new Float64Array(36).fill(1), buckets).every((v) => v !== null)).toBe(true)
  })

  it('reduces a dense ride to the bucket count', () => {
    const x = Float64Array.from({ length: 20_000 }, (_, i) => i)

    expect(bucketize(x).count).toBe(220)
  })

  it('carries the last samples of the ride into the last bucket', () => {
    // The boundary walk is by value, so a rounding error at the right-hand edge would leave the
    // final samples in no bucket at all.
    const x = Float64Array.from({ length: 1000 }, (_, i) => i)

    const buckets = bucketize(x, 7)

    expect(buckets.to[buckets.count - 1]).toBe(1000)
  })

  it('buckets an irregular axis as evenly as a regular one', () => {
    // Distance, where the ride stopped for a while: no length accrues, so a quarter of the
    // samples sit at one x. They belong to the bucket that covers that distance, not to a
    // quarter of the chart.
    const x = new Float64Array(400)
    for (let i = 0; i < 400; i++) x[i] = i < 100 ? i : i < 200 ? 100 : i - 100

    const buckets = bucketize(x, 10)

    expect(buckets.count).toBe(10)
    expect(buckets.from[0]).toBe(0)
    expect(buckets.to[buckets.count - 1]).toBe(400)
  })
})

describe('bucketMean', () => {
  it('is a mean, not an envelope', () => {
    // A channel alternating between two values every sample — sensor noise, or a cadence sensor
    // reading between two teeth. Drawn as an envelope this is a solid block of ink the height of
    // the swing; drawn as a mean it is the line through the middle of it.
    const x = Float64Array.from({ length: 600 }, (_, i) => i)
    const values = Float64Array.from({ length: 600 }, (_, i) => (i % 2 === 0 ? 80 : 100))

    const means = bucketMean(values, bucketize(x, 60))

    expect(means.every((value) => value !== null && Math.abs(value - 90) < 1e-9)).toBe(true)
  })

  it('lifts the bucket a spike is in rather than dropping it', () => {
    const x = Float64Array.from({ length: 1000 }, (_, i) => i)
    const values = new Float64Array(1000).fill(120)
    values[517] = 186

    const means = bucketMean(values, bucketize(x, 100))
    const peak = Math.max(...means.map((value) => value ?? Number.NEGATIVE_INFINITY))

    // Ten samples to a bucket: (9 × 120 + 186) / 10. Picking one sample per bucket would drop
    // it altogether; the unaveraged figure is reported by the summary and the range statistics.
    expect(peak).toBeCloseTo(126.6, 6)
  })

  it('reports a bucket that recorded nothing as a gap, never as a zero', () => {
    const x = Float64Array.from({ length: 40 }, (_, i) => i)
    const values = Float64Array.from({ length: 40 }, (_, i) => (i >= 10 && i < 20 ? NaN : 5))

    const means = bucketMean(values, bucketize(x, 40))

    expect(means.filter((value) => value === null)).toHaveLength(10)
    // µPlot reads null as a gap and a number as a value. A zero here would draw the ride
    // dropping to the floor for ten samples and climbing back out.
    expect(means.some((value) => value === 0)).toBe(false)
  })

  it('hands the scale arithmetic NaN for a gap', () => {
    const x = Float64Array.from({ length: 20 }, (_, i) => i)
    const values = Float64Array.from({ length: 20 }, (_, i) => (i < 10 ? NaN : 42))

    const finite = finiteOf(bucketMean(values, bucketize(x, 20)))

    expect(Number.isNaN(finite[0]!)).toBe(true)
    expect(finite[19]).toBe(42)
  })
})
