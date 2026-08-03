import { describe, expect, it } from 'vitest'
import {
  baseline,
  daily,
  deviationLabel,
  deviationPercent,
  latest,
  median,
  readiness,
} from './recovery'
import type { Measurement } from '../../core/api/client'

/**
 * The browser half of a rule that exists twice.
 *
 * The Kotlin twins are `ReadinessTest` and `TrendsTest`, and these assertions are deliberately
 * the same ones: the failure this guards against is not a crash, it is an athlete opening the
 * same morning on their phone and in a browser and being told two different things about it.
 */

const reading = (localDate: string, value: number, kind = 'hrv_rmssd'): Measurement => ({
  id: `${kind}-${localDate}-${value}`,
  kind,
  measuredAt: Date.parse(`${localDate}T06:00:00Z`),
  tzOffsetMinutes: 0,
  localDate,
  value,
  secondaryValue: null,
  unit: null,
})

const days = (values: number[], from = 1) =>
  values.map((value, i) => reading(`2026-07-${String(from + i).padStart(2, '0')}`, value))

describe('trends', () => {
  it('takes the median of a day rather than the sum', () => {
    // A watch and a phone app both report this morning's resting heart rate. Summing them
    // would draw a resting pulse of 96 for somebody whose pulse is 48.
    const points = daily([
      reading('2026-07-01', 48, 'resting_heart_rate'),
      reading('2026-07-01', 48, 'resting_heart_rate'),
    ])

    expect(points).toHaveLength(1)
    expect(points[0]!.value).toBe(48)
  })

  it('orders days oldest first and reports the newest as the latest', () => {
    const points = daily([reading('2026-07-03', 60), reading('2026-07-01', 50)])
    expect(points.map((p) => p.date)).toEqual(['2026-07-01', '2026-07-03'])
    expect(latest(points)!.value).toBe(60)
  })

  it('drops a reading the edge could not date', () => {
    expect(daily([reading('', 42)])).toHaveLength(0)
  })

  it('refuses a baseline below a week of history', () => {
    // Six days plus today is six usable days: not a baseline, a coincidence.
    expect(baseline(daily(days([50, 51, 52, 53, 54, 55, 56])), 21)).toBeNull()
  })

  it('excludes today from its own baseline', () => {
    // Seven ordinary days and then one enormous one. If today counted towards the window it
    // would drag the very baseline it is about to be compared against.
    const points = daily(days([50, 50, 50, 50, 50, 50, 50, 200]))
    expect(baseline(points, 21)).toBe(50)
  })

  it('is a median, so one outlier does not move it', () => {
    const points = daily(days([50, 50, 50, 999, 50, 50, 50, 50, 60]))
    expect(baseline(points, 21)).toBe(50)
  })

  it('reports the deviation as a percentage, and in words', () => {
    expect(deviationPercent(52, 50)).toBeCloseTo(4, 6)
    expect(deviationLabel(deviationPercent(52, 50))).toBe('4% above normal')
    expect(deviationLabel(deviationPercent(48, 50))).toBe('4% below normal')
    expect(deviationLabel(deviationPercent(50, 50))).toBe('at your normal')
    expect(deviationLabel(deviationPercent(50, null))).toBeNull()
  })

  it('has a median for an even count', () => {
    expect(median([1, 2, 3, 4])).toBe(2.5)
    expect(median([])).toBeNull()
  })
})

describe('readiness', () => {
  it('is absent rather than average when there is no baseline', () => {
    // The failure this pins is a screen showing 50 out of 100 and looking like an answer.
    const score = readiness({})
    expect(score.value).toBeNull()
    expect(score.components).toHaveLength(0)
    expect(score.note).toContain('needs a week')
  })

  it('reads a resting pulse above normal as the tired direction', () => {
    const tired = readiness({ restingHrToday: 56, restingHrBaseline: 50 })
    const rested = readiness({ restingHrToday: 47, restingHrBaseline: 50 })
    expect(tired.value!).toBeLessThan(rested.value!)
  })

  it('reads heart-rate variability above normal as the recovered direction', () => {
    const low = readiness({ hrvToday: 40, hrvBaseline: 50 })
    const high = readiness({ hrvToday: 55, hrvBaseline: 50 })
    expect(high.value!).toBeGreaterThan(low.value!)
  })

  it('weighs over what is present rather than penalising what is absent', () => {
    // An athlete whose watch reports sleep but no HRV should get a sleep-shaped answer, not a
    // third of one: a full night alone must not score as though two components read zero.
    const sleepOnly = readiness({ sleptSeconds: 8 * 3600 })
    expect(sleepOnly.value).toBe(100)
    expect(sleepOnly.note).toContain('No baseline yet for')
  })

  it('puts an ordinary day short of the top of the scale', () => {
    // Exactly the baseline on every component is an ordinary day, not a personal best. A scale
    // whose midpoint is unreachable is a scale nobody trusts twice.
    const ordinary = readiness({
      hrvToday: 50,
      hrvBaseline: 50,
      restingHrToday: 50,
      restingHrBaseline: 50,
      sleptSeconds: 8 * 3600,
    })
    expect(ordinary.value!).toBeGreaterThan(50)
    expect(ordinary.value!).toBeLessThan(100)
    expect(ordinary.note).toContain('Not a medical assessment')
  })

  it('clamps to nought and a hundred', () => {
    const dreadful = readiness({ hrvToday: 1, hrvBaseline: 50, restingHrToday: 90, restingHrBaseline: 50 })
    const perfect = readiness({ hrvToday: 90, hrvBaseline: 50, restingHrToday: 30, restingHrBaseline: 50 })
    expect(dreadful.value).toBe(0)
    expect(perfect.value).toBe(100)
  })

  it('names its components in the athlete’s own terms', () => {
    const score = readiness({ hrvToday: 52, hrvBaseline: 50 })
    expect(score.components[0]!.detail).toBe('52 ms against a normal of 50 ms')
  })
})
