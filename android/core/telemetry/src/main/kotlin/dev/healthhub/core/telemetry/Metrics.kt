package dev.healthhub.core.telemetry

import dev.healthhub.core.model.DistanceUnit
import dev.healthhub.core.model.Split
import dev.healthhub.core.model.Zone
import dev.healthhub.core.model.ZoneKind
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every derived number in the product is computed here, on the phone, at ingest time.
 *
 * This is Constitution Principle I made concrete. It is also what keeps the two clients
 * agreeing (SC-008): the browser never recomputes splits or zones, it reads what this
 * produced, so there is only one implementation to be right or wrong.
 *
 * All inputs are parallel arrays aligned by index against `time`. `NaN` means "not recorded"
 * and is skipped rather than treated as zero.
 */
object Metrics {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Below this, the athlete is stopped: a traffic light, not a slow kilometre. */
    private const val MOVING_SPEED_THRESHOLD_MPS = 0.5

    /**
     * Barometric and GPS altitude both jitter by a metre or two while standing still.
     * Without a threshold, a two-hour ride accumulates hundreds of phantom metres of climb.
     */
    private const val ELEVATION_NOISE_THRESHOLD_M = 1.0

    /** Great-circle distance. Haversine is accurate to well under a metre at these scales. */
    fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * Cumulative distance along the track.
     *
     * A gap — a sample where either endpoint has no fix — contributes nothing rather than a
     * straight line across it, so a tunnel does not inflate the total.
     */
    fun cumulativeDistance(lat: DoubleArray, lon: DoubleArray): DoubleArray {
        val out = DoubleArray(lat.size)
        var total = 0.0
        for (i in 1 until lat.size) {
            val previousValid = !lat[i - 1].isNaN() && !lon[i - 1].isNaN()
            val currentValid = !lat[i].isNaN() && !lon[i].isNaN()
            if (previousValid && currentValid) {
                total += haversineMetres(lat[i - 1], lon[i - 1], lat[i], lon[i])
            }
            out[i] = total
        }
        return out
    }

    /** Seconds spent actually moving, which is what a pace should be computed against. */
    fun movingSeconds(timeMs: DoubleArray, speedMps: DoubleArray): Double {
        var moving = 0.0
        for (i in 1 until timeMs.size) {
            val dt = (timeMs[i] - timeMs[i - 1]) / 1000.0
            if (dt <= 0 || dt > MAX_SAMPLE_GAP_SECONDS) continue
            val speed = speedMps.getOrElse(i) { Double.NaN }
            if (!speed.isNaN() && speed >= MOVING_SPEED_THRESHOLD_MPS) moving += dt
        }
        return moving
    }

    data class ElevationChange(val gainM: Double, val lossM: Double)

    fun elevationChange(elevation: DoubleArray): ElevationChange {
        var gain = 0.0
        var loss = 0.0
        var reference = Double.NaN

        for (value in elevation) {
            if (value.isNaN()) continue
            if (reference.isNaN()) {
                reference = value
                continue
            }
            val delta = value - reference
            if (abs(delta) < ELEVATION_NOISE_THRESHOLD_M) continue
            if (delta > 0) gain += delta else loss += -delta
            reference = value
        }
        return ElevationChange(gain, loss)
    }

    /**
     * Per-unit splits.
     *
     * Boundaries are interpolated: a kilometre almost never falls exactly on a sample, and
     * snapping to the nearest one makes early splits systematically fast and late ones slow.
     */
    fun splits(
        timeMs: DoubleArray,
        cumulativeDistanceM: DoubleArray,
        unit: DistanceUnit,
        speedMps: DoubleArray? = null,
        elevation: DoubleArray? = null,
        heartRate: DoubleArray? = null,
        power: DoubleArray? = null,
    ): List<Split> {
        if (cumulativeDistanceM.isEmpty()) return emptyList()

        val out = mutableListOf<Split>()
        val total = cumulativeDistanceM.last()
        var boundary = unit.metres
        var startIndex = 0
        var startTime = timeMs.firstOrNull() ?: return emptyList()
        var index = 0

        while (boundary <= total && index < cumulativeDistanceM.size) {
            while (index < cumulativeDistanceM.size && cumulativeDistanceM[index] < boundary) index++
            if (index >= cumulativeDistanceM.size) break

            val crossingTime = interpolateTime(timeMs, cumulativeDistanceM, index, boundary)
            val range = startIndex..index

            out += Split(
                index = out.size,
                unit = unit,
                distanceM = unit.metres,
                elapsedSeconds = (crossingTime - startTime) / 1000.0,
                movingSeconds = speedMps?.let {
                    movingSeconds(timeMs.sliceArray(range), it.sliceArray(range))
                },
                avgSpeedMps = unit.metres / ((crossingTime - startTime) / 1000.0),
                elevationGainM = elevation?.let { elevationChange(it.sliceArray(range)).gainM },
                elevationLossM = elevation?.let { elevationChange(it.sliceArray(range)).lossM },
                avgHrBpm = heartRate?.let { mean(it.sliceArray(range))?.toInt() },
                avgPowerW = power?.let { mean(it.sliceArray(range)) },
            )

            startIndex = index
            startTime = crossingTime
            boundary += unit.metres
        }

        return out
    }

    private fun interpolateTime(
        timeMs: DoubleArray,
        distance: DoubleArray,
        index: Int,
        boundary: Double,
    ): Double {
        if (index == 0) return timeMs[0]
        val spanDistance = distance[index] - distance[index - 1]
        if (spanDistance <= 0) return timeMs[index]
        val fraction = (boundary - distance[index - 1]) / spanDistance
        return timeMs[index - 1] + (timeMs[index] - timeMs[index - 1]) * fraction
    }

    /**
     * Time-in-zone.
     *
     * Weighted by the interval each sample represents, not by sample count — a device that
     * records irregularly would otherwise report a distribution that reflects its sampling
     * behaviour rather than the athlete's effort.
     */
    fun zones(
        timeMs: DoubleArray,
        values: DoubleArray,
        bounds: List<Double>,
        kind: ZoneKind,
    ): List<Zone> {
        require(bounds.isNotEmpty()) { "at least one zone boundary is required" }

        val seconds = DoubleArray(bounds.size)
        for (i in 1 until timeMs.size) {
            val dt = (timeMs[i] - timeMs[i - 1]) / 1000.0
            if (dt <= 0 || dt > MAX_SAMPLE_GAP_SECONDS) continue
            val value = values.getOrElse(i) { Double.NaN }
            if (value.isNaN()) continue

            var zone = 0
            for (b in bounds.indices) if (value >= bounds[b]) zone = b
            seconds[zone] += dt
        }

        return bounds.indices.map { i ->
            Zone(
                kind = kind,
                index = i + 1,
                lowerBound = bounds[i],
                upperBound = bounds.getOrNull(i + 1),
                seconds = seconds[i],
            )
        }
    }

    fun mean(values: DoubleArray): Double? {
        var sum = 0.0
        var count = 0
        for (value in values) {
            if (value.isNaN()) continue
            sum += value
            count++
        }
        return if (count == 0) null else sum / count
    }

    fun max(values: DoubleArray): Double? {
        var best = Double.NaN
        for (value in values) {
            if (value.isNaN()) continue
            if (best.isNaN() || value > best) best = value
        }
        return if (best.isNaN()) null else best
    }

    /**
     * A sample interval longer than this is a recording gap — the watch was paused, or the
     * app was killed. Counting it as elapsed effort would be wrong.
     */
    private const val MAX_SAMPLE_GAP_SECONDS = 30.0
}
