import { describe, expect, it } from 'vitest'
import { decodePolyline } from './polyline'
import { distance, duration, paceOrSpeed } from './format'

/**
 * The polyline codec exists twice: encoded in Kotlin on the phone, decoded in TypeScript
 * here. These use the same reference vector as `RouteTest` in core:telemetry, so the two
 * implementations cannot drift apart without one of the suites going red.
 */
describe('polyline', () => {
  it('decodes the reference vector', () => {
    const points = decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@')

    expect(points).toHaveLength(3)
    expect(points[0]![0]).toBeCloseTo(38.5, 5)
    expect(points[0]![1]).toBeCloseTo(-120.2, 5)
    expect(points[1]![0]).toBeCloseTo(40.7, 5)
    expect(points[2]![0]).toBeCloseTo(43.252, 5)
    expect(points[2]![1]).toBeCloseTo(-126.453, 5)
  })

  it('returns nothing for an empty string', () => {
    expect(decodePolyline('')).toEqual([])
  })
})

describe('formatting', () => {
  it('renders distance in the athlete’s units', () => {
    expect(distance(92_310.4, 'metric')).toBe('92.31 km')
    expect(distance(92_310.4, 'imperial')).toBe('57.36 mi')
    expect(distance(null, 'metric')).toBe('—')
  })

  it('renders duration with hours only when there are hours', () => {
    expect(duration(65)).toBe('1:05')
    expect(duration(3_725)).toBe('1:02:05')
    expect(duration(null)).toBe('—')
  })

  it('shows cycling as speed and running as pace', () => {
    // Telling a runner they averaged 12.4 km/h is accurate and useless.
    expect(paceOrSpeed(6.41, 'cycling', 'metric')).toBe('23.1 km/h')
    expect(paceOrSpeed(3.33, 'running', 'metric')).toBe('5:00 /km')
    expect(paceOrSpeed(null, 'running', 'metric')).toBe('—')
  })

  it('carries a rounded 60 seconds into the next minute', () => {
    // 1000 / 2.7809 ≈ 359.6 s/km. The seconds round to 60, which must read 6:00 — the
    // obvious implementation prints "5:60".
    expect(paceOrSpeed(2.7809, 'running', 'metric')).toBe('6:00 /km')
  })
})
