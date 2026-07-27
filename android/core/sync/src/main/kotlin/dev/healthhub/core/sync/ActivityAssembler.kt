package dev.healthhub.core.sync

import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import dev.healthhub.core.model.RoutePoint
import java.time.Instant

/**
 * Turns Health Connect's per-quantity record streams into the aligned sample grid the `.hht`
 * format needs.
 *
 * The grid is the **union of every timestamp actually recorded**, not a fixed 1 Hz
 * resampling. Resampling would be simpler and smaller, but it would quietly discard samples
 * whenever a device records faster than the grid — and Constitution Principle VI says the
 * ingest is lossless. A channel that has no sample at a given instant gets a sentinel, which
 * readers skip.
 */
object ActivityAssembler {

    /** Aligned columns for one session, all of length [timestamps].size. */
    class Grid(val timestamps: LongArray) {
        val channels = linkedMapOf<String, DoubleArray>()

        val size: Int get() = timestamps.size

        fun put(name: String, values: DoubleArray) {
            require(values.size == timestamps.size) {
                "channel $name has ${values.size} values, grid has ${timestamps.size}"
            }
            channels[name] = values
        }

        fun has(name: String): Boolean = channels.containsKey(name)

        fun get(name: String): DoubleArray? = channels[name]
    }

    /** One measured sample: a timestamp and a value. */
    data class Sample(val at: Long, val value: Double)

    /**
     * [route] overrides whatever track the session itself carries.
     *
     * A session read during a normal sync almost never carries one: bulk route access is
     * signature-level and reserved for Google's own app (R-015), so `exerciseRouteResult` comes
     * back as `ConsentRequired` and the grid has no `lat`/`lon`. When the athlete later grants
     * one activity's track through the platform's consent screen, the points arrive out of band
     * and are handed in here — so a re-ingest with an imported route produces exactly the grid a
     * sync would have produced if the track had been readable all along.
     */
    fun build(
        session: ExerciseSessionRecord,
        heartRate: List<HeartRateRecord>,
        speed: List<SpeedRecord>,
        power: List<PowerRecord>,
        cyclingCadence: List<CyclingPedalingCadenceRecord>,
        stepsCadence: List<StepsCadenceRecord>,
        route: List<RoutePoint>? = null,
    ): Grid {
        val start = session.startTime
        val end = session.endTime

        val hrSamples = heartRate
            .flatMap { it.samples }
            .filter { it.time.within(start, end) }
            .map { Sample(it.time.toEpochMilli(), it.beatsPerMinute.toDouble()) }

        val speedSamples = speed
            .flatMap { it.samples }
            .filter { it.time.within(start, end) }
            .map { Sample(it.time.toEpochMilli(), it.speed.inMetersPerSecond) }

        val powerSamples = power
            .flatMap { it.samples }
            .filter { it.time.within(start, end) }
            .map { Sample(it.time.toEpochMilli(), it.power.inWatts) }

        val cadenceSamples = (
            cyclingCadence
                .flatMap { it.samples }
                .filter { it.time.within(start, end) }
                .map { Sample(it.time.toEpochMilli(), it.revolutionsPerMinute) } +
                stepsCadence
                    .flatMap { it.samples }
                    .filter { it.time.within(start, end) }
                    .map { Sample(it.time.toEpochMilli(), it.rate) }
            ).sortedBy { it.at }

        val track = (route ?: session.recordedRoute())
            .filter { Instant.ofEpochMilli(it.timeMs).within(start, end) }
            .sortedBy { it.timeMs }

        // The union of every recorded instant — this is what makes the grid lossless.
        val instants = sortedSetOf<Long>()
        hrSamples.forEach { instants += it.at }
        speedSamples.forEach { instants += it.at }
        powerSamples.forEach { instants += it.at }
        cadenceSamples.forEach { instants += it.at }
        track.forEach { instants += it.timeMs }

        // A session with no samples at all still produces a valid, empty grid rather than
        // failing — an indoor workout with only a duration is a legitimate activity.
        if (instants.isEmpty()) instants += start.toEpochMilli()

        val grid = Grid(instants.toLongArray())

        grid.put("t", DoubleArray(grid.size) { (grid.timestamps[it] - start.toEpochMilli()).toDouble() })
        if (hrSamples.isNotEmpty()) grid.put("hr", align(grid.timestamps, hrSamples))
        if (speedSamples.isNotEmpty()) grid.put("speed", align(grid.timestamps, speedSamples))
        if (powerSamples.isNotEmpty()) grid.put("power", align(grid.timestamps, powerSamples))
        if (cadenceSamples.isNotEmpty()) grid.put("cadence", align(grid.timestamps, cadenceSamples))

        if (track.isNotEmpty()) {
            val lat = track.map { Sample(it.timeMs, it.lat) }
            val lon = track.map { Sample(it.timeMs, it.lon) }
            val elevation = track
                .filter { it.altitudeM != null }
                .map { Sample(it.timeMs, it.altitudeM!!) }

            grid.put("lat", align(grid.timestamps, lat))
            grid.put("lon", align(grid.timestamps, lon))
            if (elevation.isNotEmpty()) grid.put("elevation", align(grid.timestamps, elevation))
        }

        return grid
    }

    /** The track a session carries on its own, which is empty unless the platform granted it. */
    private fun ExerciseSessionRecord.recordedRoute(): List<RoutePoint> =
        (exerciseRouteResult as? ExerciseRouteResult.Data)
            ?.exerciseRoute
            ?.route
            ?.map { RoutePoint(it.time.toEpochMilli(), it.latitude, it.longitude, it.altitude?.inMeters) }
            .orEmpty()

    /**
     * Places samples on the grid.
     *
     * A value lands on its own timestamp exactly; grid instants with no sample nearby get
     * NaN. Values are *not* interpolated across gaps — an interpolated heart rate through a
     * two-minute sensor dropout is a fabricated measurement, and this product's premise is
     * that the data is real.
     */
    private fun align(grid: LongArray, samples: List<Sample>): DoubleArray {
        val out = DoubleArray(grid.size) { Double.NaN }
        if (samples.isEmpty()) return out

        val sorted = samples.sortedBy { it.at }
        var cursor = 0

        for (i in grid.indices) {
            val target = grid[i]
            while (cursor < sorted.size - 1 && sorted[cursor].at < target) cursor++

            val candidate = sorted[cursor]
            if (candidate.at == target) {
                out[i] = candidate.value
                continue
            }
            // Fall back to the previous sample when it is recent enough to still describe
            // this instant — sensors report at their own cadence, not the grid's.
            val previous = sorted.getOrNull(cursor - 1) ?: continue
            if (target - previous.at <= HOLD_TOLERANCE_MS) out[i] = previous.value
        }
        return out
    }

    /** How long a sample keeps describing the present. Beyond this, the channel is silent. */
    private const val HOLD_TOLERANCE_MS = 5_000L

    private fun Instant.within(start: Instant, end: Instant): Boolean =
        !isBefore(start) && !isAfter(end)
}
