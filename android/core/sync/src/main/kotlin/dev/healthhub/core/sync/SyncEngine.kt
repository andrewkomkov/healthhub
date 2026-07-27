package dev.healthhub.core.sync

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.healthhub.core.database.PendingActivity
import dev.healthhub.core.database.StagingDao
import dev.healthhub.core.database.SyncStateEntity
import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.model.DistanceUnit
import dev.healthhub.core.model.Sport
import dev.healthhub.core.model.SyncFailure
import dev.healthhub.core.model.SyncReport
import dev.healthhub.core.model.SyncStatus
import dev.healthhub.core.model.ZoneKind
import dev.healthhub.core.network.ActivityUploadRequest
import dev.healthhub.core.network.CursorDto
import dev.healthhub.core.network.HealthHubApi
import dev.healthhub.core.network.SplitDto
import dev.healthhub.core.network.SyncFailureDto
import dev.healthhub.core.network.SyncReportRequest
import dev.healthhub.core.network.ZoneDto
import dev.healthhub.core.telemetry.Hht
import dev.healthhub.core.telemetry.HhtChannel
import dev.healthhub.core.telemetry.HhtHeader
import dev.healthhub.core.telemetry.HhtType
import dev.healthhub.core.telemetry.HhtWriter
import dev.healthhub.core.telemetry.Lttb
import dev.healthhub.core.telemetry.Metrics
import dev.healthhub.core.telemetry.Route
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Reads workouts out of Health Connect, computes every derived metric here on the phone, and
 * ships the result.
 *
 * Two invariants hold this together:
 *
 *  - **The cursor advances only after an upload is confirmed.** Reverse that and an
 *    interrupted sync loses data silently, which is the worst failure this app can have.
 *  - **Nothing is computed twice.** Splits, zones and averages are produced once, here, and
 *    the server stores them verbatim — so the phone and the browser can never disagree
 *    (SC-008), and the edge stays free of analysis (Principle I).
 */
@Singleton
class SyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthConnect: HealthConnectSource,
    private val api: HealthHubApi,
    private val staging: StagingDao,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress

    sealed interface Progress {
        data object Idle : Progress
        data class Running(val done: Int, val total: Int, val stage: String) : Progress
        data class Finished(val report: SyncReport) : Progress
    }

    /**
     * One sync pass.
     *
     * [since] bounds the window; on a first run it is the beginning of recorded history, and
     * afterwards the last confirmed position.
     */
    suspend fun sync(since: Instant? = null): SyncReport {
        val startedAt = System.currentTimeMillis()

        if (healthConnect.availability != HealthConnectSource.Availability.AVAILABLE) {
            return finish(
                SyncReport(
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    status = SyncStatus.FAILED,
                    message = "Health Connect is not available on this device.",
                ),
            )
        }

        val missing = healthConnect.missingPermissions()
        val from = since ?: lastSyncedUntil() ?: Instant.now().minusSeconds(DEFAULT_BACKFILL_SECONDS)
        val to = Instant.now()

        _progress.value = Progress.Running(0, 0, "Reading Health Connect")

        val sessions = runCatching { healthConnect.readSessions(from, to) }.getOrElse { error ->
            return finish(
                SyncReport(
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    status = SyncStatus.FAILED,
                    message = error.message ?: "Could not read workouts.",
                ),
            )
        }

        val failures = mutableListOf<SyncFailure>()
        val unhandled = sortedSetOf<String>()
        var synced = 0
        var samples = 0L

        /*
         * Sessions are processed in time-ordered windows, and each window reads every channel
         * once for the whole window rather than once per session.
         *
         * The naive shape — eight reads per session — exhausts Health Connect's API quota
         * partway through a real history: a 75-session backfill issued 600 calls and the
         * provider started rejecting them with "API call quota exceeded". Batching by window
         * turns that into eight calls per window regardless of how many sessions it holds,
         * while keeping only one window's samples in memory at a time.
         */
        // One workout recorded by several apps is decided here, where every candidate is
        // in memory, and shipped as a verdict the edge simply stores.
        val verdicts = SessionGrouping.classify(sessions)

        val windows = sessions
            .sortedBy { it.startTime }
            .groupBy { it.startTime.toEpochMilli() / WINDOW_MS }

        var processed = 0
        for ((_, windowSessions) in windows) {
            val windowStart = windowSessions.minOf { it.startTime }
            val windowEnd = windowSessions.maxOf { it.endTime }

            val bulk = runCatching { readWindow(windowStart, windowEnd) }.getOrElse { error ->
                windowSessions.forEach { session ->
                    failures += SyncFailure(
                        sourceUid = session.metadata.id,
                        reason = error.message ?: error::class.simpleName.orEmpty(),
                    )
                }
                processed += windowSessions.size
                continue
            }

            for (session in windowSessions) {
                _progress.value =
                    Progress.Running(processed, sessions.size, session.title ?: "Workout")
                runCatching { ingest(session, bulk, verdicts[session.metadata.id]) }
                    .onSuccess { count ->
                        synced += 1
                        samples += count
                    }
                    .onFailure { error ->
                        failures += SyncFailure(
                            sourceUid = session.metadata.id,
                            reason = error.message ?: error::class.simpleName.orEmpty(),
                        )
                    }
                processed += 1
            }
        }

        // Anything the registry does not cover is named, not swallowed (Principle VI).
        if (missing.isNotEmpty()) unhandled += missing.map { "permission:$it" }

        // Only now, with every upload confirmed, is it safe to move the cursor forward.
        if (failures.isEmpty()) {
            recordCursor(to)
        }

        val report = SyncReport(
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            status = when {
                failures.isEmpty() -> SyncStatus.OK
                synced > 0 -> SyncStatus.PARTIAL
                else -> SyncStatus.FAILED
            },
            sessionsSynced = synced,
            samplesSynced = samples,
            failures = failures,
            unhandledTypes = unhandled.toList(),
        )

        runCatching { api.postReport(report.toRequest()) }
        return finish(report)
    }

    /** Everything read once for a window, then sliced per session in memory. */
    private class WindowData(
        val heartRate: List<androidx.health.connect.client.records.HeartRateRecord>,
        val speed: List<androidx.health.connect.client.records.SpeedRecord>,
        val power: List<androidx.health.connect.client.records.PowerRecord>,
        val cyclingCadence: List<androidx.health.connect.client.records.CyclingPedalingCadenceRecord>,
        val stepsCadence: List<androidx.health.connect.client.records.StepsCadenceRecord>,
        val distance: List<androidx.health.connect.client.records.DistanceRecord>,
        val calories: List<androidx.health.connect.client.records.TotalCaloriesBurnedRecord>,
        val elevation: List<androidx.health.connect.client.records.ElevationGainedRecord>,
    )

    private suspend fun readWindow(from: Instant, to: Instant) = WindowData(
        heartRate = healthConnect.readHeartRate(from, to),
        speed = healthConnect.readSpeed(from, to),
        power = healthConnect.readPower(from, to),
        cyclingCadence = healthConnect.readCyclingCadence(from, to),
        stepsCadence = healthConnect.readStepsCadence(from, to),
        distance = healthConnect.readDistance(from, to),
        calories = healthConnect.readCalories(from, to),
        elevation = healthConnect.readElevation(from, to),
    )

    /** Computes one session's metrics from the window's data, writes telemetry and uploads. */
    private suspend fun ingest(
        session: ExerciseSessionRecord,
        window: WindowData,
        verdict: SessionGrouping.Verdict?,
    ): Long {
        val from = session.startTime
        val to = session.endTime

        // Records are already in memory; overlap is a comparison, not another API call.
        // The predicate is repeated per type because Health Connect's IntervalRecord
        // interface is internal to the SDK, so there is no shared supertype to write against.
        fun overlaps(recordStart: Instant, recordEnd: Instant) =
            !recordEnd.isBefore(from) && !recordStart.isAfter(to)

        val grid = ActivityAssembler.build(
            session = session,
            heartRate = window.heartRate.filter { overlaps(it.startTime, it.endTime) },
            speed = window.speed.filter { overlaps(it.startTime, it.endTime) },
            power = window.power.filter { overlaps(it.startTime, it.endTime) },
            cyclingCadence = window.cyclingCadence.filter { overlaps(it.startTime, it.endTime) },
            stepsCadence = window.stepsCadence.filter { overlaps(it.startTime, it.endTime) },
        )

        val time = grid.get("t") ?: DoubleArray(0)
        val lat = grid.get("lat")
        val lon = grid.get("lon")
        val speed = grid.get("speed")
        val elevation = grid.get("elevation")
        val heartRate = grid.get("hr")
        val power = grid.get("power")
        val cadence = grid.get("cadence")

        val cumulative = if (lat != null && lon != null) {
            Metrics.cumulativeDistance(lat, lon)
        } else {
            null
        }

        // Health Connect records distance, calories and elevation as their own aggregates,
        // independent of any GPS track. Indoor workouts and any session recorded without the
        // route permission have exactly these and nothing else — deriving distance only from
        // GPS would report a treadmill run as zero kilometres, which is worse than useless.
        val recordedDistanceM = window.distance
            .filter { overlaps(it.startTime, it.endTime) }
            .sumOf { it.distance.inMeters }
            .takeIf { it > 0 }
        val recordedCaloriesKcal = window.calories
            .filter { overlaps(it.startTime, it.endTime) }
            .sumOf { it.energy.inKilocalories }
            .takeIf { it > 0 }
        val recordedElevationGainM = window.elevation
            .filter { overlaps(it.startTime, it.endTime) }
            .sumOf { it.elevation.inMeters }
            .takeIf { it != 0.0 }

        val gpsDistanceM = cumulative?.lastOrNull()?.takeIf { it > 0 }
        // The GPS track is the more precise measurement when it exists; the recorded
        // aggregate is the fallback, not the other way round.
        val distanceM = gpsDistanceM ?: recordedDistanceM

        val elevationChange = elevation?.let { Metrics.elevationChange(it) }
        val simplified = if (lat != null && lon != null) Route.simplify(lat, lon) else emptyList()

        val splits = if (cumulative != null) {
            Metrics.splits(
                timeMs = time,
                cumulativeDistanceM = cumulative,
                unit = DistanceUnit.KILOMETRE,
                speedMps = speed,
                elevation = elevation,
                heartRate = heartRate,
                power = power,
            )
        } else {
            emptyList()
        }

        val zones = if (heartRate != null) {
            Metrics.zones(time, heartRate, DEFAULT_HR_ZONE_BOUNDS, ZoneKind.HR)
        } else {
            emptyList()
        }

        val fullFile = writeTelemetry(session.metadata.id, grid, "full")
        val previewFile = writePreview(session.metadata.id, grid)

        val request = ActivityUploadRequest(
            sourceUid = session.metadata.id,
            sport = session.exerciseType.toSport().slug,
            title = session.title ?: session.exerciseType.toSport().slug.replaceFirstChar { it.uppercase() },
            description = session.notes,
            startTime = session.startTime.toEpochMilli(),
            endTime = session.endTime.toEpochMilli(),
            tzOffsetMinutes = tzOffsetMinutes(session),
            elapsedSeconds = (session.endTime.epochSecond - session.startTime.epochSecond),
            // Zero is not a measurement here: a session whose speed channel never crosses the
            // moving threshold has *unknown* moving time, not none, and reporting 0 makes the
            // feed show "0:00" for a real workout.
            movingSeconds = speed
                ?.let { Metrics.movingSeconds(time, it).toLong() }
                ?.takeIf { it > 0 },
            distanceM = distanceM,
            elevationGainM = elevationChange?.gainM ?: recordedElevationGainM,
            elevationLossM = elevationChange?.lossM,
            caloriesKcal = recordedCaloriesKcal,
            avgSpeedMps = speed?.let { Metrics.mean(it) }
                // Without a speed channel, average speed still follows from distance over
                // moving time — which is what the feed card shows as pace.
                ?: distanceM?.let { metres ->
                    val seconds = (session.endTime.epochSecond - session.startTime.epochSecond)
                    if (seconds > 0) metres / seconds else null
                },
            maxSpeedMps = speed?.let { Metrics.max(it) },
            avgHrBpm = heartRate?.let { Metrics.mean(it)?.toInt() },
            maxHrBpm = heartRate?.let { Metrics.max(it)?.toInt() },
            avgCadenceRpm = cadence?.let { Metrics.mean(it) },
            avgPowerW = power?.let { Metrics.mean(it) },
            maxPowerW = power?.let { Metrics.max(it) },
            hasGps = simplified.isNotEmpty(),
            routePolyline = if (simplified.isNotEmpty()) Route.encodePolyline(simplified) else null,
            bounds = if (lat != null && lon != null) {
                Route.bounds(lat, lon)?.let { listOf(it.minLat, it.minLon, it.maxLat, it.maxLon) }
            } else {
                null
            },
            sampleCount = grid.size,
            channels = grid.channels.keys.toList(),
            sourcePackage = session.metadata.dataOrigin.packageName,
            duplicateOf = verdict?.duplicateOf,
            sourceCount = verdict?.sourceCount ?: 1,
            splits = splits.map { split ->
                SplitDto(
                    unit = split.unit.wire,
                    idx = split.index,
                    distanceM = split.distanceM,
                    elapsedSeconds = split.elapsedSeconds,
                    movingSeconds = split.movingSeconds,
                    avgSpeedMps = split.avgSpeedMps,
                    elevationGainM = split.elevationGainM,
                    elevationLossM = split.elevationLossM,
                    avgHrBpm = split.avgHrBpm,
                    avgPowerW = split.avgPowerW,
                )
            },
            zones = zones.map { zone ->
                ZoneDto(
                    kind = if (zone.kind == ZoneKind.HR) "hr" else "power",
                    zoneIndex = zone.index,
                    lowerBound = zone.lowerBound,
                    upperBound = zone.upperBound,
                    seconds = zone.seconds,
                )
            },
        )

        // Staged before uploading, so an upload interrupted mid-flight resumes from disk
        // rather than re-reading and re-computing the whole session.
        staging.stage(
            PendingActivity(
                sourceUid = request.sourceUid,
                payload = json.encodeToString(request),
                fullTelemetryPath = fullFile.absolutePath,
                previewTelemetryPath = previewFile?.absolutePath,
                sampleCount = grid.size,
                createdAt = System.currentTimeMillis(),
            ),
        )

        val uploaded = api.uploadActivity(request)
        previewFile?.let { api.uploadTelemetry(uploaded.id, it, "preview", gzipped = true) }
        api.uploadTelemetry(uploaded.id, fullFile, "full", gzipped = true)

        staging.clearPending(request.sourceUid)
        fullFile.delete()
        previewFile?.delete()

        return grid.size.toLong()
    }

    /**
     * Writes a `.hht` straight to disk, gzipped.
     *
     * Never assembled in memory first: a five-hour ride is on the order of a million samples
     * per channel, and buffering that is exactly how this gets killed on a phone (SC-003).
     */
    private fun writeTelemetry(
        sourceUid: String,
        grid: ActivityAssembler.Grid,
        variant: String,
    ): File {
        val file = File(telemetryDir(), "${sourceUid.hashCode()}-$variant.hht.gz")
        val channels = grid.channels.keys.map { name -> HhtChannel(name, typeFor(name), unitFor(name)) }

        GZIPOutputStream(file.outputStream().buffered()).use { gzip ->
            HhtWriter(
                gzip,
                HhtHeader(
                    activityId = sourceUid,
                    startTime = 0,
                    count = grid.size,
                    channels = channels,
                ),
            ).use { writer ->
                for (channel in channels) {
                    val values = grid.get(channel.name) ?: continue
                    writer.channel(channel) { sink -> sink.writeAll(channel.type, values) }
                }
            }
        }
        return file
    }

    /** The ~2,000-point preview the detail screen paints while full resolution arrives. */
    private fun writePreview(sourceUid: String, grid: ActivityAssembler.Grid): File? {
        if (grid.size <= PREVIEW_POINTS) return null

        val time = grid.get("t") ?: return null
        // Chosen on whichever channel carries the most shape, so peaks survive the reduction.
        val reference = grid.get("elevation") ?: grid.get("speed") ?: grid.get("hr") ?: time
        val keep = Lttb.downsample(time, reference, PREVIEW_POINTS)

        val reduced = ActivityAssembler.Grid(LongArray(keep.size) { grid.timestamps[keep[it]] })
        for ((name, values) in grid.channels) {
            reduced.put(name, DoubleArray(keep.size) { values[keep[it]] })
        }
        return writeTelemetry(sourceUid, reduced, "preview")
    }

    private fun HhtWriter.ChannelSink.writeAll(type: HhtType, values: DoubleArray) {
        when (type) {
            HhtType.F64 -> write(values)
            HhtType.F32 -> write(FloatArray(values.size) { values[it].toFloat() })
            HhtType.U16 -> write(
                IntArray(values.size) {
                    if (values[it].isNaN()) Hht.U16_NONE else values[it].toInt().coerceIn(0, 65_534)
                },
            )
            HhtType.U32 -> write(
                LongArray(values.size) {
                    if (values[it].isNaN()) Hht.U32_NONE else values[it].toLong()
                },
            )
        }
    }

    private fun telemetryDir(): File =
        File(context.cacheDir, "telemetry").apply { mkdirs() }

    private suspend fun lastSyncedUntil(): Instant? =
        staging.state(CURSOR_KEY)?.syncedUntil?.let(Instant::ofEpochMilli)

    private suspend fun recordCursor(until: Instant) {
        staging.putState(
            SyncStateEntity(
                recordType = CURSOR_KEY,
                changeToken = null,
                syncedUntil = until.toEpochMilli(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        runCatching {
            api.putCursors(
                listOf(CursorDto(recordType = CURSOR_KEY, syncedUntil = until.toEpochMilli())),
            )
        }
    }

    private fun finish(report: SyncReport): SyncReport {
        _progress.value = Progress.Finished(report)
        return report
    }

    private fun SyncReport.toRequest() = SyncReportRequest(
        startedAt = startedAt,
        finishedAt = finishedAt,
        status = status.name.lowercase(),
        sessionsSynced = sessionsSynced,
        samplesSynced = samplesSynced,
        failures = failures.map { SyncFailureDto(it.sourceUid, it.reason) },
        unhandledTypes = unhandledTypes,
        message = message,
    )

    private fun tzOffsetMinutes(session: ExerciseSessionRecord): Int {
        val offset = session.startZoneOffset
            ?: ZoneId.systemDefault().rules.getOffset(session.startTime)
        return offset.totalSeconds / 60
    }

    private companion object {
        const val CURSOR_KEY = "ExerciseSession"
        const val PREVIEW_POINTS = 2_000

        /**
         * How much history one batch of reads covers. A day keeps each window's samples
         * small enough to hold in memory while collapsing a day's worth of sessions into
         * eight API calls instead of eight per session.
         */
        const val WINDOW_MS = 24L * 60 * 60 * 1000
        /** First run reaches back a year; anything older is a deliberate full backfill. */
        const val DEFAULT_BACKFILL_SECONDS = 365L * 24 * 60 * 60

        /** Placeholder zone boundaries until the athlete configures their own. */
        val DEFAULT_HR_ZONE_BOUNDS = listOf(0.0, 114.0, 133.0, 152.0, 171.0)

        fun typeFor(channel: String): HhtType = when (channel) {
            "lat", "lon" -> HhtType.F64
            "t" -> HhtType.U32
            "hr", "power" -> HhtType.U16
            else -> HhtType.F32
        }

        fun unitFor(channel: String): String = when (channel) {
            "t" -> "ms"
            "lat", "lon" -> "deg"
            "elevation" -> "m"
            "hr" -> "bpm"
            "speed" -> "m/s"
            "cadence" -> "rpm"
            "power" -> "W"
            else -> ""
        }
    }
}

/** Health Connect exercise types mapped onto the product's closed sport set. */
private fun Int.toSport(): Sport = when (this) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> Sport.RUNNING
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> Sport.RUNNING
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> Sport.WALKING
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> Sport.HIKING
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> Sport.CYCLING
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> Sport.CYCLING
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> Sport.SWIMMING
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> Sport.SWIMMING
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> Sport.ROWING
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> Sport.ROWING
    ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> Sport.SKIING
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> Sport.SNOWBOARDING
    ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> Sport.SKATING
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> Sport.STRENGTH
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> Sport.STRENGTH
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> Sport.YOGA
    else -> Sport.OTHER
}
