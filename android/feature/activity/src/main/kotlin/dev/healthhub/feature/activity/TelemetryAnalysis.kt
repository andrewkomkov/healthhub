package dev.healthhub.feature.activity

import dev.healthhub.core.telemetry.Metrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turning one decoded `.hht` into something the charts, the map and the statistics panel can
 * all read.
 *
 * Nothing here recomputes a *summary*: splits, zones and the headline figures were computed at
 * ingest time by `core:telemetry` and are read verbatim (Constitution Principle I). What this
 * file does compute is the two things the device has no way to precompute for a screen — the
 * distance axis, and statistics for a range the athlete selects with a finger.
 *
 * This is the Kotlin twin of `web/src/core/telemetry/analysis.ts`, deliberately line-for-line.
 * SC-008 says the two clients report the same numbers for the same activity, and the only way
 * to keep that true when the logic exists twice is for both copies to be obviously the same
 * thing. Every constant below is repeated there; changing one without the other is the drift
 * the device-QA pass exists to catch.
 */
internal object TelemetryAnalysis {

    /** Below this the athlete is stopped. Mirrors `Metrics.MOVING_SPEED_THRESHOLD_MPS`. */
    const val MOVING_SPEED_THRESHOLD_MPS = 0.5

    /** Barometers jitter. Mirrors `Metrics.ELEVATION_NOISE_THRESHOLD_M`. */
    const val ELEVATION_NOISE_THRESHOLD_M = 1.0

    /**
     * How far the integrated track may disagree with the stored total before the reconciliation
     * is abandoned. A larger gap means the channels and the summary describe different things,
     * and rescaling would hide that rather than fix it.
     *
     * Taken from [Metrics] rather than written out again: the ingest path applies the same band
     * when it decides whether a GPS track may stand in for a source's own distance, and two
     * copies of one tolerance is how the phone and the browser start disagreeing. Both are
     * `const`, so this is still a compile-time constant and still mirrors
     * `web/src/core/telemetry/analysis.ts` value for value.
     */
    const val MIN_DISTANCE_CORRECTION = Metrics.MIN_DISTANCE_CORRECTION
    const val MAX_DISTANCE_CORRECTION = Metrics.MAX_DISTANCE_CORRECTION

    /**
     * Cumulative distance along the track, in metres, one value per sample.
     *
     * Preference order matters. GPS is measured; integrating speed is a reconstruction; and when
     * neither exists — a treadmill session with only heart rate — there is no distance axis at
     * all and the caller must fall back to time. A gap contributes nothing rather than a straight
     * line, so a tunnel does not inflate the total.
     *
     * [total] reconciles the result with the figure the phone stored at ingest: the athlete sees
     * one distance for the activity, and it is the one the summary reports.
     */
    fun cumulativeDistance(
        time: DoubleArray?,
        lat: DoubleArray?,
        lon: DoubleArray?,
        speed: DoubleArray?,
        total: Double?,
    ): DoubleArray? {
        val count = time?.size ?: lat?.size ?: speed?.size ?: 0
        if (count == 0) return null

        val out = DoubleArray(count)
        var running = 0.0

        if (lat != null && lon != null) {
            for (i in 1 until count) {
                val a = lat.at(i - 1)
                val b = lon.at(i - 1)
                val c = lat.at(i)
                val d = lon.at(i)
                if (!a.isNaN() && !b.isNaN() && !c.isNaN() && !d.isNaN()) {
                    running += Metrics.haversineMetres(a, b, c, d)
                }
                out[i] = running
            }
        } else if (speed != null && time != null) {
            for (i in 1 until count) {
                val v = speed.at(i)
                val dt = (time.at(i) - time.at(i - 1)) / 1000.0
                if (!v.isNaN() && dt.isFinite() && dt > 0) running += v * dt
                out[i] = running
            }
        } else {
            return null
        }

        if (running <= 0) return null

        if (total != null && total > 0) {
            val correction = total / running
            if (correction > MIN_DISTANCE_CORRECTION && correction < MAX_DISTANCE_CORRECTION) {
                for (i in 0 until count) out[i] = out[i] * correction
            }
        }

        return out
    }

    /**
     * Statistics for the samples in `[from, to]`.
     *
     * Every channel is averaged over the samples that actually recorded a value, not over the
     * span: five minutes of heart rate inside a twenty-minute selection is an average heart rate
     * for five minutes, not a quarter of one. A channel with no valid sample in the range comes
     * back null — the caller renders an em dash, never a zero.
     */
    fun rangeStats(channels: TelemetryChannels, from: Int, to: Int): RangeStats {
        val lo = max(0, min(from, to))
        val hi = min((channels.time?.size ?: 1) - 1, max(from, to))

        val time = channels.time
        val elapsedSeconds = if (time == null) 0.0 else max(0.0, (time.at(hi) - time.at(lo)) / 1000.0)

        var movingSeconds: Double? = null
        val speed = channels.speed
        if (time != null && speed != null) {
            var moving = 0.0
            var sawSpeed = false
            for (i in lo + 1..hi) {
                val v = speed.at(i)
                if (v.isNaN()) continue
                sawSpeed = true
                if (v >= MOVING_SPEED_THRESHOLD_MPS) moving += (time.at(i) - time.at(i - 1)) / 1000.0
            }
            // A channel that never crossed the threshold leaves moving time unknown, not zero —
            // storing 0 here is what once made real workouts read "0:00" in the feed.
            movingSeconds = if (sawSpeed) moving else null
        }

        val distance = channels.distance
        val distanceM = if (distance == null) null else max(0.0, distance.at(hi) - distance.at(lo))

        var gain: Double? = null
        var loss: Double? = null
        val elevation = channels.elevation
        if (elevation != null) {
            var up = 0.0
            var down = 0.0
            var reference = Double.NaN
            var seen = false
            for (i in lo..hi) {
                val v = elevation.at(i)
                if (v.isNaN()) continue
                seen = true
                if (reference.isNaN()) {
                    reference = v
                    continue
                }
                val delta = v - reference
                if (abs(delta) < ELEVATION_NOISE_THRESHOLD_M) continue
                if (delta > 0) up += delta else down -= delta
                reference = v
            }
            if (seen) {
                gain = up
                loss = down
            }
        }

        // Average speed over a selection is distance over time, not the mean of the speed
        // channel: a sampled mean weights a minute standing still the same as a minute at 30.
        val avgSpeedMps =
            if (distanceM != null && elapsedSeconds > 0) distanceM / elapsedSeconds
            else mean(speed, lo, hi)

        return RangeStats(
            from = lo,
            to = hi,
            samples = hi - lo + 1,
            elapsedSeconds = elapsedSeconds,
            movingSeconds = movingSeconds,
            distanceM = distanceM,
            avgSpeedMps = avgSpeedMps,
            maxSpeedMps = maxOf(speed, lo, hi),
            avgHrBpm = mean(channels.hr, lo, hi),
            maxHrBpm = maxOf(channels.hr, lo, hi),
            avgPowerW = mean(channels.power, lo, hi),
            avgCadenceRpm = mean(channels.cadence, lo, hi),
            elevationGainM = gain,
            elevationLossM = loss,
        )
    }

    /** Index of the sample nearest a value on a monotonically increasing axis. Binary search. */
    fun nearestIndex(axis: DoubleArray, value: Double): Int {
        var lo = 0
        var hi = axis.size - 1
        if (hi < 0) return 0
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (axis[mid] < value) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && abs(axis[lo - 1] - value) <= abs(axis[lo] - value)) return lo - 1
        return lo
    }

    private fun mean(values: DoubleArray?, lo: Int, hi: Int): Double? {
        if (values == null) return null
        var sum = 0.0
        var n = 0
        for (i in lo..hi) {
            val v = values.at(i)
            if (v.isNaN()) continue
            sum += v
            n++
        }
        return if (n == 0) null else sum / n
    }

    private fun maxOf(values: DoubleArray?, lo: Int, hi: Int): Double? {
        if (values == null) return null
        var best = Double.NEGATIVE_INFINITY
        for (i in lo..hi) {
            val v = values.at(i)
            if (!v.isNaN() && v > best) best = v
        }
        return if (best == Double.NEGATIVE_INFINITY) null else best
    }

    /**
     * Reading past the end of a channel yields "not recorded" rather than throwing.
     *
     * JavaScript gives the web reader `undefined` there and its `Number.isNaN` check skips it;
     * an out-of-bounds read in Kotlin would crash instead, which is a different behaviour for
     * the same malformed object. Channels are the same length in every object the writer
     * produces, so this only matters for one that is not.
     */
    private fun DoubleArray.at(index: Int): Double =
        if (index in indices) this[index] else Double.NaN
}

internal data class RangeStats(
    val from: Int,
    val to: Int,
    val samples: Int,
    val elapsedSeconds: Double,
    val movingSeconds: Double?,
    val distanceM: Double?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val avgHrBpm: Double?,
    val maxHrBpm: Double?,
    val avgPowerW: Double?,
    val avgCadenceRpm: Double?,
    val elevationGainM: Double?,
    val elevationLossM: Double?,
)
