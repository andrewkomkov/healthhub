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

    /**
     * How far two independent measurements of one distance may disagree before neither is
     * allowed to stand in for the other.
     *
     * The band is the one the two detail screens already use to reconcile the integrated track
     * against the stored total (`TelemetryAnalysis`, `web/src/core/telemetry/analysis.ts`), and
     * it lives here so there is one number rather than three.
     */
    const val MIN_DISTANCE_CORRECTION = 0.8
    const val MAX_DISTANCE_CORRECTION = 1.25

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
        val cap = sampleGapCapSeconds(timeMs)
        var moving = 0.0
        for (i in 1 until timeMs.size) {
            val dt = (timeMs[i] - timeMs[i - 1]) / 1000.0
            if (dt <= 0 || dt > cap) continue
            val speed = speedMps.getOrElse(i) { Double.NaN }
            if (!speed.isNaN() && speed >= MOVING_SPEED_THRESHOLD_MPS) moving += dt
        }
        return moving
    }

    /**
     * The longest interval that still counts as a *sample* rather than as a *gap*, for this
     * recording.
     *
     * The rule used to be a flat 30 seconds, on the reasonable theory that a long silence is
     * not evidence of movement. It is reasonable at 1 Hz and wrong at anything else. Google Fit
     * samples a walk about once every 77 seconds, so on a real 46-minute walk *every* interval
     * exceeded 30 s, every one was skipped, and the stored moving time was 2:56 — while the
     * detail screen's range statistics, which applied no cap at all, reported 27:35 over the
     * same samples. Two numbers for one walk, on one screen, which is what SC-008 forbids.
     *
     * So the threshold follows the source's own cadence: [GAP_MULTIPLE] times the median
     * interval, floored at the old 30 seconds. Three consequences worth stating, because each
     * one is a decision:
     *
     * - **a 1 Hz recording is unchanged.** Its median interval is a second, three of those is
     *   three seconds, and the floor keeps the cap at 30 — so every activity already synced
     *   from a dense source keeps exactly the moving time it has;
     * - **the median, not the mean.** A ride with one nine-minute coffee stop in it has a mean
     *   interval dragged upwards by that stop, and would then count the stop as movement. The
     *   median is what the source does *usually*, which is the question being asked;
     * - **a genuine pause is still excluded.** At 77-second cadence the cap is about four
     *   minutes; the coffee stop is still a gap, and so is a tunnel, a paused recording, and
     *   the hour between two workouts a source wrote into one session.
     *
     * Both detail screens apply this to their range statistics too — `TelemetryAnalysis` and
     * `web/src/core/telemetry/analysis.ts`. They used to apply no cap, which was recorded as a
     * deliberate divergence and was really the other half of this defect.
     */
    fun sampleGapCapSeconds(timeMs: DoubleArray): Double {
        if (timeMs.size < 2) return MAX_SAMPLE_GAP_SECONDS

        val intervals = DoubleArray(timeMs.size - 1)
        var count = 0
        for (i in 1 until timeMs.size) {
            val dt = (timeMs[i] - timeMs[i - 1]) / 1000.0
            if (dt > 0) intervals[count++] = dt
        }
        // Too few intervals to have a cadence. Three samples one second and then five minutes
        // apart have a median of two and a half minutes, and calling that "how often this
        // source writes" would turn the pause into movement. Below this many, the honest
        // answer is that the recording does not say, and the conservative floor stands.
        if (count < MIN_INTERVALS_FOR_CADENCE) return MAX_SAMPLE_GAP_SECONDS

        val sorted = intervals.copyOf(count)
        sorted.sort()
        val median = if (count % 2 == 1) {
            sorted[count / 2]
        } else {
            (sorted[count / 2 - 1] + sorted[count / 2]) / 2
        }

        return maxOf(MAX_SAMPLE_GAP_SECONDS, median * GAP_MULTIPLE)
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
     *
     * The same cadence-relative gap cap as [movingSeconds], and for the same reason: a sparse
     * source's every interval used to exceed the flat 30-second threshold, so its zone
     * distribution came out as all zeroes and the chart drew an empty bar chart for a workout
     * with a full heart-rate trace on the screen above it.
     */
    fun zones(
        timeMs: DoubleArray,
        values: DoubleArray,
        bounds: List<Double>,
        kind: ZoneKind,
    ): List<Zone> {
        require(bounds.isNotEmpty()) { "at least one zone boundary is required" }

        val cap = sampleGapCapSeconds(timeMs)
        val seconds = DoubleArray(bounds.size)
        for (i in 1 until timeMs.size) {
            val dt = (timeMs[i] - timeMs[i - 1]) / 1000.0
            if (dt <= 0 || dt > cap) continue
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

    /** Whether a distance is the GPS track's, the source's own aggregate, or nothing at all. */
    enum class DistanceOrigin { GPS, RECORDED, NONE }

    /** Whether the chosen distance survives being checked against speed over elapsed time. */
    enum class DistanceAgreement { AGREES, DISAGREES, UNKNOWN }

    data class DistanceVerdict(
        val distanceM: Double?,
        val origin: DistanceOrigin,
        val agreement: DistanceAgreement,
    )

    /**
     * Which distance to believe when a GPS track and a source's own aggregate both exist.
     *
     * Health Connect is a hub, and this is where the trap it sets is disarmed. A ride recorded
     * by a bike computer and by a phone app carries two `DistanceRecord` sets over the same
     * minutes; a route imported later covers whatever *its* recorder saw, which may be the
     * whole ride or the ten minutes before the battery went. Preferring the track blindly once
     * shrank a 42 km ride to the part that had a fix, and summing instead reported 89.59 km.
     *
     * So neither candidate is trusted on its own: each is checked against [expectedM], the
     * distance implied by average speed over elapsed time, and the first one that reconciles
     * wins.
     *
     * Plenty of recorders write a distance aggregate and no speed channel, so [expectedM] is
     * often null. That must not become "the track wins": the partial-track case is *more*
     * likely on exactly those workouts, and preferring it unchecked is the shrink-a-ride bug
     * with the guard removed. Without a third figure the two candidates are compared to each
     * other over the same band: the track wins on precision when it agrees with the source's own
     * summary, and the summary wins when they disagree, because it is the recording app's claim
     * about the whole of its own session while the track is only what one recorder saw. The
     * agreement is still [DistanceAgreement.UNKNOWN] either way — no independent check ran.
     */
    fun reconcileDistance(
        gpsM: Double?,
        recordedM: Double?,
        expectedM: Double?,
    ): DistanceVerdict {
        val gps = gpsM?.takeIf { it.isFinite() && it > 0 }
        val recorded = recordedM?.takeIf { it.isFinite() && it > 0 }
        val expected = expectedM?.takeIf { it.isFinite() && it > 0 }

        if (gps == null && recorded == null) {
            return DistanceVerdict(null, DistanceOrigin.NONE, DistanceAgreement.UNKNOWN)
        }

        if (expected == null) {
            val preferTrack = gps != null && (recorded == null || agreesWithin(gps, recorded))
            return if (preferTrack) {
                DistanceVerdict(gps, DistanceOrigin.GPS, DistanceAgreement.UNKNOWN)
            } else {
                DistanceVerdict(recorded, DistanceOrigin.RECORDED, DistanceAgreement.UNKNOWN)
            }
        }

        if (gps != null && agreesWithin(gps, expected)) {
            return DistanceVerdict(gps, DistanceOrigin.GPS, DistanceAgreement.AGREES)
        }
        if (recorded != null && agreesWithin(recorded, expected)) {
            return DistanceVerdict(recorded, DistanceOrigin.RECORDED, DistanceAgreement.AGREES)
        }

        // Neither reconciles. Something is still shown — a workout with no distance at all is a
        // worse answer than an imperfect one — but the closer figure is used and the caller is
        // told, so nothing downstream can present it as verified.
        val gpsError = gps?.let { disagreement(it, expected) } ?: Double.MAX_VALUE
        val recordedError = recorded?.let { disagreement(it, expected) } ?: Double.MAX_VALUE
        return if (gpsError <= recordedError) {
            DistanceVerdict(gps, DistanceOrigin.GPS, DistanceAgreement.DISAGREES)
        } else {
            DistanceVerdict(recorded, DistanceOrigin.RECORDED, DistanceAgreement.DISAGREES)
        }
    }

    /**
     * The distance an average speed implies over a span, or null without one.
     *
     * Elapsed rather than moving time, and the mean of the whole speed channel rather than of
     * its moving samples: both include the stops, so the two errors cancel instead of stacking.
     */
    fun expectedDistance(avgSpeedMps: Double?, elapsedSeconds: Double): Double? {
        if (avgSpeedMps == null || !avgSpeedMps.isFinite() || avgSpeedMps <= 0) return null
        if (elapsedSeconds <= 0) return null
        return avgSpeedMps * elapsedSeconds
    }

    private fun agreesWithin(candidate: Double, expected: Double): Boolean {
        val correction = expected / candidate
        return correction > MIN_DISTANCE_CORRECTION && correction < MAX_DISTANCE_CORRECTION
    }

    private fun disagreement(candidate: Double, expected: Double): Double =
        kotlin.math.max(candidate, expected) / kotlin.math.min(candidate, expected)

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

    /**
     * The average speed a workout is *read* at: distance over the time actually spent moving.
     *
     * Not the mean of the speed channel, which is what this used to be, and the difference is
     * not academic. A sampled mean weights a minute standing at a traffic light exactly like a
     * minute at thirty — the detail screen's range statistics have said so in a comment for
     * months and computed it the right way for a selection, while the *summary* the same screen
     * draws above them came from the channel mean.
     *
     * What that produced, found on a Pixel: a 1.43 km walk with elapsed 22:59, moving 6:04 and
     * an average pace of 18:06 /km. 1.43 km at 18:06 /km is 25:53 — **longer than the elapsed
     * time on the tile beside it**. Three figures on one screen, each from a different place,
     * and one of them arithmetically impossible against the others. That is exactly what SC-008
     * forbids, and it is why this is one function rather than a `mean` call at each site.
     *
     * Preference order, and each step is a decision:
     *
     * 1. **distance over moving time** — what an athlete means by average pace, and what makes
     *    the tiles on one screen reconcile with each other;
     * 2. **distance over elapsed** when moving time is unknown, which is the honest reading for
     *    a source that recorded no usable speed channel;
     * 3. **the channel mean** only when there is no distance at all. It is the misleading
     *    figure, but a treadmill session with a speed trace and no distance has nothing else,
     *    and removing the number entirely would be a worse answer than a soft one.
     */
    fun averageSpeed(
        distanceM: Double?,
        movingSeconds: Double?,
        elapsedSeconds: Double?,
        channelMeanMps: Double? = null,
    ): Double? {
        if (distanceM != null && distanceM > 0) {
            val seconds = movingSeconds?.takeIf { it > 0 } ?: elapsedSeconds?.takeIf { it > 0 }
            if (seconds != null) return distanceM / seconds
        }
        return channelMeanMps
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
     * The floor under [sampleGapCapSeconds], and the whole rule until this file learnt about
     * cadence.
     *
     * A sample interval longer than this is a recording gap — the watch was paused, or the app
     * was killed. It stays as a floor rather than becoming a pure multiple of the cadence so
     * that a 1 Hz recording keeps the threshold it has always had: every activity already
     * synced from a dense source must keep the moving time it was stored with, or fixing this
     * would silently rewrite a history.
     */
    const val MAX_SAMPLE_GAP_SECONDS = 30.0

    /**
     * How many of a source's own sample intervals make a gap.
     *
     * Three is a judgement, and it is the loosest one that still excludes a real pause: a
     * source writing every 77 seconds gets a cap just under four minutes, so a coffee stop is
     * still not movement, while two consecutive samples always are. Two would put ordinary
     * jitter — 77 s, then 160 s because the phone slept — on the wrong side of the line.
     */
    const val GAP_MULTIPLE = 3.0

    /**
     * How many intervals it takes before a median is a cadence rather than an accident.
     *
     * Ten is deliberately cautious. The case this rule is for — a phone app writing every
     * minute or so — has dozens of intervals across any workout worth opening; the case it must
     * not break is a handful of samples with one long pause among them, where a median says
     * more about the pause than about the source.
     */
    const val MIN_INTERVALS_FOR_CADENCE = 10
}
