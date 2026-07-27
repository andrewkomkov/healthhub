/**
 * Turning one decoded `.hht` into something a chart, a map and a stats panel can all read.
 *
 * Nothing here recomputes a *summary*: splits, zones and the headline figures were computed
 * on the phone and are read verbatim (Constitution Principle I). What this file does compute
 * is the two things the device has no way to precompute — the distance axis, and statistics
 * for a range the athlete selects with the mouse.
 *
 * Where a constant appears in both places it is called out, because the two implementations
 * disagreeing is exactly the failure the device-QA pass exists to catch.
 */

const EARTH_RADIUS_M = 6_371_008.8

/** Below this the athlete is stopped. Mirrors `Metrics.MOVING_SPEED_THRESHOLD_MPS`. */
const MOVING_SPEED_THRESHOLD_MPS = 0.5

/** Barometers jitter. Mirrors `Metrics.ELEVATION_NOISE_THRESHOLD_M`. */
const ELEVATION_NOISE_THRESHOLD_M = 1.0

export function haversineMetres(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const toRad = Math.PI / 180
  const dLat = (lat2 - lat1) * toRad
  const dLon = (lon2 - lon1) * toRad
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.sin(dLon / 2) ** 2
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))))
}

/**
 * Cumulative distance along the track, in metres, one value per sample.
 *
 * Preference order matters. GPS is measured; integrating speed is a reconstruction; and when
 * neither exists — a treadmill session with only heart rate — there is no distance axis at
 * all and the caller must fall back to time. A gap contributes nothing rather than a straight
 * line, so a tunnel does not inflate the total.
 *
 * `total` reconciles the result with the figure the phone computed: the athlete sees one
 * distance for the activity, and it is the device's, not a second opinion from the browser.
 */
export function cumulativeDistance(
  time: Float64Array | null,
  lat: Float64Array | null,
  lon: Float64Array | null,
  speed: Float64Array | null,
  total: number | null,
): Float64Array | null {
  const count = time?.length ?? lat?.length ?? speed?.length ?? 0
  if (count === 0) return null

  const out = new Float64Array(count)
  let running = 0

  if (lat && lon) {
    for (let i = 1; i < count; i++) {
      const a = lat[i - 1]!
      const b = lon[i - 1]!
      const c = lat[i]!
      const d = lon[i]!
      if (!Number.isNaN(a) && !Number.isNaN(b) && !Number.isNaN(c) && !Number.isNaN(d)) {
        running += haversineMetres(a, b, c, d)
      }
      out[i] = running
    }
  } else if (speed && time) {
    for (let i = 1; i < count; i++) {
      const v = speed[i]!
      const dt = (time[i]! - time[i - 1]!) / 1000
      if (!Number.isNaN(v) && Number.isFinite(dt) && dt > 0) running += v * dt
      out[i] = running
    }
  } else {
    return null
  }

  if (running <= 0) return null

  if (total !== null && total > 0) {
    const correction = total / running
    // A large disagreement means the channels and the summary describe different things;
    // rescaling then would hide it, so the measured track is left alone.
    if (correction > 0.8 && correction < 1.25) {
      for (let i = 0; i < count; i++) out[i] = out[i]! * correction
    }
  }

  return out
}

export interface RangeStats {
  from: number
  to: number
  samples: number
  elapsedSeconds: number
  movingSeconds: number | null
  distanceM: number | null
  avgSpeedMps: number | null
  maxSpeedMps: number | null
  avgHrBpm: number | null
  maxHrBpm: number | null
  avgPowerW: number | null
  avgCadenceRpm: number | null
  elevationGainM: number | null
  elevationLossM: number | null
}

interface Channels {
  time: Float64Array | null
  distance: Float64Array | null
  speed: Float64Array | null
  hr: Float64Array | null
  power: Float64Array | null
  cadence: Float64Array | null
  elevation: Float64Array | null
}

/**
 * Statistics for the samples in `[from, to]`.
 *
 * Every channel is averaged over the samples that actually recorded a value, not over the
 * span: five minutes of heart rate inside a twenty-minute selection is an average heart rate
 * for five minutes, not a quarter of one. A channel with no valid sample in the range comes
 * back `null` — the caller renders an em dash, never a zero.
 */
export function rangeStats(channels: Channels, from: number, to: number): RangeStats {
  const lo = Math.max(0, Math.min(from, to))
  const hi = Math.min((channels.time?.length ?? 1) - 1, Math.max(from, to))

  const mean = (values: Float64Array | null) => {
    if (!values) return null
    let sum = 0
    let n = 0
    for (let i = lo; i <= hi; i++) {
      const v = values[i]!
      if (!Number.isNaN(v)) {
        sum += v
        n++
      }
    }
    return n === 0 ? null : sum / n
  }

  const max = (values: Float64Array | null) => {
    if (!values) return null
    let best = -Infinity
    for (let i = lo; i <= hi; i++) {
      const v = values[i]!
      if (!Number.isNaN(v) && v > best) best = v
    }
    return best === -Infinity ? null : best
  }

  const time = channels.time
  const elapsedSeconds = time ? Math.max(0, (time[hi]! - time[lo]!) / 1000) : 0

  let movingSeconds: number | null = null
  if (time && channels.speed) {
    let moving = 0
    let sawSpeed = false
    for (let i = lo + 1; i <= hi; i++) {
      const v = channels.speed[i]!
      if (Number.isNaN(v)) continue
      sawSpeed = true
      if (v >= MOVING_SPEED_THRESHOLD_MPS) moving += (time[i]! - time[i - 1]!) / 1000
    }
    // A channel that never crossed the threshold leaves moving time unknown, not zero —
    // storing 0 here is what once made real workouts read "0:00" in the feed.
    movingSeconds = sawSpeed ? moving : null
  }

  const distance = channels.distance
  const distanceM = distance ? Math.max(0, distance[hi]! - distance[lo]!) : null

  let gain: number | null = null
  let loss: number | null = null
  if (channels.elevation) {
    let up = 0
    let down = 0
    let reference: number | null = null
    let seen = false
    for (let i = lo; i <= hi; i++) {
      const v = channels.elevation[i]!
      if (Number.isNaN(v)) continue
      seen = true
      if (reference === null) {
        reference = v
        continue
      }
      const delta = v - reference
      if (Math.abs(delta) < ELEVATION_NOISE_THRESHOLD_M) continue
      if (delta > 0) up += delta
      else down -= delta
      reference = v
    }
    if (seen) {
      gain = up
      loss = down
    }
  }

  // Average speed over a selection is distance over time, not the mean of the speed channel:
  // a sampled mean weights a minute standing still the same as a minute at 30 km/h.
  const avgSpeedMps =
    distanceM !== null && elapsedSeconds > 0 ? distanceM / elapsedSeconds : mean(channels.speed)

  return {
    from: lo,
    to: hi,
    samples: hi - lo + 1,
    elapsedSeconds,
    movingSeconds,
    distanceM,
    avgSpeedMps,
    maxSpeedMps: max(channels.speed),
    avgHrBpm: mean(channels.hr),
    maxHrBpm: max(channels.hr),
    avgPowerW: mean(channels.power),
    avgCadenceRpm: mean(channels.cadence),
    elevationGainM: gain,
    elevationLossM: loss,
  }
}

/** Index of the sample nearest a value on a monotonically increasing axis. Binary search. */
export function nearestIndex(axis: Float64Array, value: number): number {
  let lo = 0
  let hi = axis.length - 1
  if (hi < 0) return 0
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (axis[mid]! < value) lo = mid + 1
    else hi = mid
  }
  if (lo > 0 && Math.abs(axis[lo - 1]! - value) <= Math.abs(axis[lo]! - value)) return lo - 1
  return lo
}
