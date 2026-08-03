import { describe, expect, it } from 'vitest'
import {
  cumulativeDistance,
  haversineMetres,
  nearestIndex,
  rangeStats,
  sampleGapCapSeconds,
} from './analysis'

const f = (values: number[]) => Float64Array.from(values)

describe('cumulativeDistance', () => {
  it('measures along the track and skips the gap', () => {
    const time = f([0, 1000, 2000, 3000])
    const lat = f([55.75, 55.751, NaN, 55.753])
    const lon = f([37.61, 37.61, NaN, 37.61])

    const distance = cumulativeDistance(time, lat, lon, null, null)!

    const firstLeg = haversineMetres(55.75, 37.61, 55.751, 37.61)
    expect(distance[0]).toBe(0)
    expect(distance[1]).toBeCloseTo(firstLeg, 3)
    // Crossing the gap adds nothing — a tunnel is not free distance.
    expect(distance[3]).toBeCloseTo(firstLeg, 3)
  })

  it('reconstructs from speed when there is no GPS at all', () => {
    const time = f([0, 1000, 2000, 3000])
    const speed = f([3, 3, 3, 3])

    const distance = cumulativeDistance(time, null, null, speed, null)!

    expect(distance[3]).toBeCloseTo(9, 6)
  })

  it('has no distance axis for a treadmill session with only heart rate', () => {
    expect(cumulativeDistance(f([0, 1000]), null, null, null, null)).toBeNull()
  })

  it("reconciles with the phone's figure so the athlete sees one distance", () => {
    const time = f([0, 1000, 2000])
    const speed = f([5, 5, 5])

    // Measured 10 m; the device says 10.5 — a 5% correction, well inside plausible.
    const distance = cumulativeDistance(time, null, null, speed, 10.5)!

    expect(distance[2]).toBeCloseTo(10.5, 6)
  })

  it('leaves the track alone when the summary describes something else entirely', () => {
    const time = f([0, 1000, 2000])
    const speed = f([5, 5, 5])

    // Double counting across sources is exactly how a 42 km ride is reported as 89 km.
    // Silently rescaling to it would hide the bug instead of showing it.
    const distance = cumulativeDistance(time, null, null, speed, 21)!

    expect(distance[2]).toBeCloseTo(10, 6)
  })
})

describe('rangeStats', () => {
  const channels = {
    time: f([0, 1000, 2000, 3000, 4000]),
    distance: f([0, 5, 10, 15, 20]),
    speed: f([5, 5, 0.1, 5, 5]),
    hr: f([120, NaN, NaN, 150, 160]),
    power: f([200, 210, 0, 220, 230]),
    cadence: f([80, 82, NaN, 84, 86]),
    elevation: f([100, 100.5, 103, 102.8, 99]),
  }

  it('averages speed as distance over time, not as a mean of samples', () => {
    const stats = rangeStats(channels, 0, 4)

    expect(stats.distanceM).toBeCloseTo(20, 6)
    expect(stats.elapsedSeconds).toBeCloseTo(4, 6)
    expect(stats.avgSpeedMps).toBeCloseTo(5, 6)
  })

  it('averages a channel over the samples that recorded it', () => {
    const stats = rangeStats(channels, 0, 4)

    // Three readings, not five: the missing two are unknown, not zero.
    expect(stats.avgHrBpm).toBeCloseTo((120 + 150 + 160) / 3, 6)
    expect(stats.maxHrBpm).toBe(160)
  })

  it('excludes time below the moving threshold', () => {
    const stats = rangeStats(channels, 0, 4)

    // One of the four seconds was spent at 0.1 m/s — stopped.
    expect(stats.movingSeconds).toBeCloseTo(3, 6)
  })

  it('keeps a sparse source\'s moving time instead of losing all of it', () => {
    // The Kotlin twin of this test is `MetricsTest`, and the defect is the same one, found on
    // a real Pixel: Google Fit samples a walk about once every 77 seconds, so under a flat
    // 30-second gap cap every interval was a gap and a 46-minute walk stored 2:56 of movement.
    // This file's range statistics applied no cap at all and reported 27:35 over exactly the
    // same samples — two numbers for one walk on one screen, which is what SC-008 forbids.
    const cadence = 77_000
    const samples = 36
    const time = f(Array.from({ length: samples }, (_, i) => i * cadence))
    const speed = f(Array.from({ length: samples }, () => 1.4))

    const stats = rangeStats(
      { time, distance: null, speed, hr: null, power: null, cadence: null, elevation: null },
      0,
      samples - 1,
    )

    expect(stats.movingSeconds).toBeCloseTo(((samples - 1) * cadence) / 1000, 6)
  })

  it('leaves moving time unknown when nothing ever recorded speed', () => {
    const stats = rangeStats({ ...channels, speed: null }, 0, 4)

    expect(stats.movingSeconds).toBeNull()
  })

  it('ignores elevation jitter below the noise threshold', () => {
    const stats = rangeStats(channels, 0, 4)

    // 100 → 100.5 is jitter and does not count; 100 → 103 is a climb of three metres.
    expect(stats.elevationGainM).toBeCloseTo(3, 6)
    expect(stats.elevationLossM).toBeCloseTo(4, 6)
  })

  it('reports nothing rather than zero for a channel the range does not cover', () => {
    const stats = rangeStats(channels, 1, 2)

    expect(stats.avgHrBpm).toBeNull()
    expect(stats.avgCadenceRpm).toBeCloseTo(82, 6)
  })

  it('accepts a selection dragged right to left', () => {
    expect(rangeStats(channels, 4, 1).from).toBe(1)
    expect(rangeStats(channels, 4, 1).to).toBe(4)
  })
})

describe('nearestIndex', () => {
  const axis = f([0, 10, 20, 30, 40])

  it('finds the closest sample on either side', () => {
    expect(nearestIndex(axis, 0)).toBe(0)
    expect(nearestIndex(axis, 11)).toBe(1)
    expect(nearestIndex(axis, 16)).toBe(2)
    expect(nearestIndex(axis, 1000)).toBe(4)
    expect(nearestIndex(axis, -5)).toBe(0)
  })
})

describe('sampleGapCapSeconds', () => {
  it('leaves a one hertz recording on exactly the threshold it always had', () => {
    // The floor is what makes the cadence-relative rule safe over a history already synced:
    // three times a one-second cadence is three seconds, so the cap stays at thirty.
    expect(sampleGapCapSeconds(f(Array.from({ length: 120 }, (_, i) => i * 1000)))).toBe(30)
  })

  it('widens to the source\'s own cadence', () => {
    const time = f(Array.from({ length: 40 }, (_, i) => i * 77_000))
    expect(sampleGapCapSeconds(time)).toBeCloseTo(231, 6)
  })

  it('refuses to infer a cadence from too few intervals', () => {
    // Three samples a second and then five minutes apart have a median interval of two and a
    // half minutes. Calling that a cadence would turn the pause into movement.
    expect(sampleGapCapSeconds(f([0, 1000, 301_000]))).toBe(30)
  })
})
