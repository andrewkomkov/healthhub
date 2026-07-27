import { describe, expect, it } from 'vitest'
import { axisRange } from './axisRange'

/**
 * The axis range rule, pinned on both clients.
 *
 * These are the same cases as `ChartSeriesTest` on the Kotlin side, with the same numbers. The
 * two implementations are separate because one draws on a Compose canvas and the other hands a
 * scale to µPlot, but they must not disagree about the shape of the same ride — an athlete
 * comparing the phone to the browser is comparing two pictures of one walk.
 */
describe('axisRange', () => {
  it('does not let one stopped sample stretch the axis over the whole walk', () => {
    // A walk holding about 1 m per second that pauses once at a crossing.
    const values = new Float64Array(60)
    for (let i = 0; i < 60; i++) values[i] = i === 30 ? 0.004 : 1.0 + (i % 5) * 0.05

    const range = axisRange(values)

    expect(range).not.toBeNull()
    // The pause is well below the fence, so the axis ignores it. µPlot clamps the drawn point
    // to the scale, so the sample still reads as a line running along the bottom edge.
    expect(range![0]).toBeGreaterThan(0.5)
  })

  it('keeps its own bounds for a channel with no outliers', () => {
    const values = new Float64Array(40)
    for (let i = 0; i < 40; i++) values[i] = 100 + i

    const [lo, hi] = axisRange(values)!

    // Padded by RANGE_PAD on each side, but bounded by the data rather than by a fence.
    expect(lo).toBeCloseTo(100 - 39 * 0.08, 6)
    expect(hi).toBeCloseTo(139 + 39 * 0.08, 6)
  })

  it('leaves a short recording alone rather than guessing at a tail', () => {
    const values = Float64Array.from([1, 1, 1, 1, 1, 40])

    const [, hi] = axisRange(values)!

    expect(hi).toBeGreaterThanOrEqual(40)
  })

  it('gives a flat channel a span instead of a zero-height scale', () => {
    const values = new Float64Array(30).fill(200)

    const [lo, hi] = axisRange(values)!

    expect(hi).toBeGreaterThan(lo)
    expect(lo).toBeLessThan(200)
    expect(hi).toBeGreaterThan(200)
  })

  it('reports nothing for a channel that recorded nothing at all', () => {
    expect(axisRange(new Float64Array([Number.NaN, Number.NaN]))).toBeNull()
  })
})
