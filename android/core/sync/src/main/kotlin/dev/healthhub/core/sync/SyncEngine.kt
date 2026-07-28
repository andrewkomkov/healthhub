package dev.healthhub.core.sync

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.healthhub.core.database.PendingActivity
import dev.healthhub.core.database.StagingDao
import dev.healthhub.core.database.SyncStateEntity
import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.healthconnect.RouteState
import dev.healthhub.core.healthconnect.HealthRecordRegistry
import dev.healthhub.core.model.DistanceUnit
import dev.healthhub.core.model.RoutePoint
import dev.healthhub.core.model.Sport
import dev.healthhub.core.model.SyncFailure
import dev.healthhub.core.model.SyncReport
import dev.healthhub.core.model.SyncStatus
import dev.healthhub.core.model.ZoneKind
import dev.healthhub.core.network.ActivityUploadRequest
import dev.healthhub.core.network.CursorDto
import dev.healthhub.core.network.HealthHubApi
import dev.healthhub.core.network.SeenSource
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
    private val healthRecords: HealthRecordSync,
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
        // Report every app seen writing data, so the athlete can rank them, then apply the
        // ranking they have already set. Discovery and preference are separate: a source
        // appearing for the first time must not disturb an order chosen deliberately.
        val observed = sessions.map { it.metadata.dataOrigin.packageName }.distinct()
        runCatching { api.reportSources(observed.map { SeenSource(packageName = it) }) }

        val preferences = runCatching { api.sources() }.getOrDefault(emptyList())

        // One workout recorded by several apps is decided here, where every candidate is
        // in memory, and shipped as a verdict the edge simply stores.
        val verdicts = SessionGrouping.classify(
            sessions = sessions,
            sourcePriority = preferences.associate { it.packageName to it.priority },
            disabledSources = preferences.filterNot { it.enabled }.map { it.packageName }.toSet(),
        )

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
                    .onSuccess { ingested ->
                        synced += 1
                        samples += ingested.samples
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

        /*
         * The daily-grain pass: sleep, HRV, resting heart rate, blood oxygen, weight, body fat
         * and blood pressure.
         *
         * It runs after the workouts and keeps its own cursors, because this data exists on
         * days with no workout on them — hanging it off the session windows would lose every
         * reading taken on a rest day. Its failures join the report rather than replacing it: a
         * sync that uploaded eleven rides and could not reach the sleep route is partial, not
         * failed.
         */
        // Read before the health pass runs, because the exercise cursor answers one question
        // only — was every one of *those* uploads confirmed. A sleep route that timed out must
        // not make the next sync re-read a year of rides.
        val workoutsClean = failures.isEmpty()

        val health = runCatching {
            healthRecords.sync(from, to) { stage ->
                _progress.value = Progress.Running(processed, sessions.size, stage)
            }
        }.getOrElse { error ->
            HealthRecordSync.Outcome(
                failures = listOf(
                    SyncFailure(
                        sourceUid = "health-records",
                        reason = error.message ?: error::class.simpleName.orEmpty(),
                    ),
                ),
            )
        }
        failures += health.failures

        // Anything the registry does not cover is named, not swallowed (Principle VI).
        if (missing.isNotEmpty()) unhandled += missing.map { "permission:$it" }
        // Domains switched off, and permissions a switched-on domain still needs.
        unhandled += health.skipped
        // And the types this app does not model at all, so "HealthHub shows none of my nutrition
        // data" has an answer on the same screen as the question.
        unhandled += HealthRecordRegistry.notIngested.map { "not-ingested:${it.typeName}" }

        // Only now, with every upload confirmed, is it safe to move the cursor forward. The
        // daily-grain domains keep their own cursors and have already advanced the ones that
        // succeeded; this one is the exercise sessions'.
        if (workoutsClean) {
            recordCursor(to)
        }

        // Only once everything today is uploaded, and only when nothing failed: a quota spent on
        // history is a quota the next sync does not have, and there is no point repairing last
        // March while this morning's ride is still stuck.
        val backfill = if (failures.isEmpty()) {
            runCatching { backfillRoutes() }.getOrNull()
        } else {
            null
        }

        val report = SyncReport(
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            status = when {
                failures.isEmpty() -> SyncStatus.OK
                // Anything at all reaching the server makes this partial rather than failed —
                // including a night of sleep on a day with no workout on it, which is the whole
                // reason the daily-grain pass exists.
                synced > 0 || health.nightsSynced > 0 || health.measurementsSynced > 0 ->
                    SyncStatus.PARTIAL
                else -> SyncStatus.FAILED
            },
            sessionsSynced = synced,
            samplesSynced = samples,
            failures = failures,
            // Ordered by what the athlete can act on. The registry's not-ingested catalogue is
            // two dozen entries long and never changes; burying "permission still needed"
            // underneath it alphabetically would make the one actionable line the last one read.
            unhandledTypes = unhandled.sortedBy { entry ->
                UNHANDLED_ORDER.indexOfFirst { entry.startsWith(it) }.takeIf { it >= 0 }
                    ?: UNHANDLED_ORDER.size
            },
            // Said out loud rather than left to be noticed on a map days later. A backfill that
            // found nothing says nothing — this line appears only when a workout that had no
            // track now has one.
            message = backfill?.takeIf { it.imported > 0 }?.let {
                val more = if (it.more) " More to come on the next sync." else ""
                val tracks = if (it.imported == 1) "track" else "tracks"
                "Filled in ${it.imported} GPS $tracks for older workouts.$more"
            },
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

    /** What one session's ingest produced, for whoever asked for it. */
    data class Ingested(
        val activityId: String,
        val samples: Long,
        /** Vertices in the simplified polyline the feed thumbnail draws; 0 when there is no track. */
        val polylinePoints: Int,
        val distanceM: Double?,
        val distanceOrigin: Metrics.DistanceOrigin,
        val distanceAgreement: Metrics.DistanceAgreement,
    )

    /**
     * Computes one session's metrics from the window's data, writes telemetry and uploads.
     *
     * [route] is the track an athlete granted for this one activity after the fact; without it
     * the session's own (usually absent) route is used, so a sync and a route import run the
     * very same code and cannot produce two different versions of the same workout.
     */
    private suspend fun ingest(
        session: ExerciseSessionRecord,
        window: WindowData,
        verdict: SessionGrouping.Verdict?,
        route: List<RoutePoint>? = null,
    ): Ingested {
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
            route = route,
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
        /*
         * Aggregates are summed **per source, then one source is chosen** — never summed
         * across sources.
         *
         * Health Connect is a hub: a ride recorded by both a bike computer's app and Google
         * Fit produces two independent sets of DistanceRecords covering the same minutes.
         * Adding them up reported an 89.59 km ride that was really ~42 km, and the giveaway
         * was that the distance disagreed with the average speed over the elapsed time.
         *
         * The session's own source wins when it has records, because that is the app the
         * athlete actually recorded with; otherwise the largest single source is used, since
         * a partial recorder should not shrink a complete one.
         */
        val sessionOrigin = session.metadata.dataOrigin.packageName

        fun pickPerSource(values: List<Pair<String, Double>>): Double? {
            if (values.isEmpty()) return null
            val bySource = values.groupBy({ it.first }, { it.second })
                .mapValues { (_, amounts) -> amounts.sum() }
            return (bySource[sessionOrigin] ?: bySource.values.maxOrNull())?.takeIf { it != 0.0 }
        }

        val recordedDistanceM = pickPerSource(
            window.distance
                .filter { overlaps(it.startTime, it.endTime) }
                .map { it.metadata.dataOrigin.packageName to it.distance.inMeters },
        )
        val recordedCaloriesKcal = pickPerSource(
            window.calories
                .filter { overlaps(it.startTime, it.endTime) }
                .map { it.metadata.dataOrigin.packageName to it.energy.inKilocalories },
        )
        val recordedElevationGainM = pickPerSource(
            window.elevation
                .filter { overlaps(it.startTime, it.endTime) }
                .map { it.metadata.dataOrigin.packageName to it.elevation.inMeters },
        )

        val gpsDistanceM = cumulative?.lastOrNull()?.takeIf { it > 0 }
        val elapsedSeconds = session.endTime.epochSecond - session.startTime.epochSecond

        /*
         * The GPS track is the more precise measurement — but only of what it actually saw.
         *
         * A track imported one activity at a time covers whatever its recorder covered, which
         * on a real ride is sometimes the ten minutes before the battery died. Taking it over
         * the source's own aggregate without checking would shrink a 42 km ride to 6 km, which
         * is the same class of error as summing across sources and inflating it to 89.59 km.
         * So both candidates are checked against speed over elapsed time and the one that
         * reconciles wins.
         */
        val distance = Metrics.reconcileDistance(
            gpsM = gpsDistanceM,
            recordedM = recordedDistanceM,
            expectedM = Metrics.expectedDistance(
                avgSpeedMps = speed?.let { Metrics.mean(it) },
                elapsedSeconds = elapsedSeconds.toDouble(),
            ),
        )
        val distanceM = distance.distanceM
        val distanceIsTheTrack = distance.origin == Metrics.DistanceOrigin.GPS

        val elevationChange = elevation?.let { Metrics.elevationChange(it) }
        val simplified = if (lat != null && lon != null) Route.simplify(lat, lon) else emptyList()

        // Splits are per kilometre *of this activity's distance*. When the track is not that
        // distance — a partial route beside a complete aggregate — splitting it would number
        // kilometres the workout does not agree it rode.
        val splits = if (cumulative != null && distanceIsTheTrack) {
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
            elapsedSeconds = elapsedSeconds,
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
                    if (elapsedSeconds > 0) metres / elapsedSeconds else null
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

        return Ingested(
            activityId = uploaded.id,
            samples = grid.size.toLong(),
            polylinePoints = simplified.size,
            distanceM = distanceM,
            distanceOrigin = distance.origin,
            distanceAgreement = distance.agreement,
        )
    }

    /**
     * What a pass of [backfillRoutes] did.
     *
     * `checked` is workouts asked about, not sessions read: a row whose recording is not on this
     * phone costs one lookup and nothing else.
     */
    data class RouteBackfill(
        val checked: Int,
        val imported: Int,
        val points: Int,
        /** Tracks that would have rewritten the workout's distance. Left for the athlete. */
        val disputed: Int,
        /** True while there may be more to do — the budget ran out before the candidates did. */
        val more: Boolean,
    )

    /**
     * Fills in GPS tracks for workouts that were synced without one, a workout at a time.
     *
     * Two things have to be true at once for a track to be missing, and both are ordinary. The
     * platform hands a route over only to an app holding `READ_EXERCISE_ROUTES` — which the
     * athlete grants in Health Connect's own settings and *cannot* be asked for from here — so
     * everything synced before that switch was flipped has no track and never will, because a
     * sync only reads forward from its cursor. That is what this repairs: the recordings are
     * still in Health Connect, the permission is now held, and nobody is going to open two
     * hundred workouts and tap "import" on each.
     *
     * It asks the *server* which workouts are missing a track rather than deciding locally,
     * because that is where the answer lives — a phone that reinstalled has no record of what it
     * once uploaded. `sourceUid` on the feed row is what makes the match exact; matching a row
     * back to a session by start time is ambiguous precisely for the workouts that matter, the
     * ones two apps recorded.
     *
     * Deliberately budgeted rather than exhaustive. Each import is ten Health Connect reads and
     * one upload, and a quota spent here is a quota not available to the next sync — so a pass
     * does a handful, says whether there is more, and the next sync continues. Nothing here is
     * destructive: [importRoute] recomputes the duplicate verdict, so a recording the athlete's
     * source order set aside stays set aside.
     */
    suspend fun backfillRoutes(budget: Int = ROUTE_BACKFILL_BUDGET): RouteBackfill {
        if (healthConnect.availability != HealthConnectSource.Availability.AVAILABLE) {
            return RouteBackfill(0, imported = 0, points = 0, disputed = 0, more = false)
        }

        var checked = 0
        var imported = 0
        var points = 0
        var disputed = 0
        var cursor: String? = null
        var pages = 0

        do {
            val page = runCatching { api.feed(cursor, FEED_PAGE) }.getOrNull() ?: break
            // Rows the phone can act on: no track yet, and an identifier to find the recording
            // by. A row uploaded before the field existed is skipped rather than guessed at.
            val candidates = page.activities.filter { !it.hasGps && !it.sourceUid.isNullOrBlank() }

            for (activity in candidates) {
                if (imported >= budget) {
                    return RouteBackfill(checked, imported, points, disputed, more = true)
                }
                val sourceUid = activity.sourceUid ?: continue
                checked++
                _progress.value = Progress.Running(imported, budget, "Looking for GPS tracks")

                val session = runCatching { healthConnect.readSession(sourceUid) }
                    .getOrNull() ?: continue
                val available = healthConnect.routeState(session) as? RouteState.Available ?: continue
                // One fix is not a line. Importing it would rewrite the telemetry to say the
                // workout has a track and still leave the map with nothing to draw.
                if (available.points.size < MIN_ROUTE_POINTS) continue

                /*
                 * A track that covers a different distance from the one already on the screen is
                 * not a fill-in, it is a correction — and a correction made to two hundred
                 * workouts while nobody is looking is how an athlete's history quietly changes
                 * shape. `Metrics.reconcileDistance` would decide between them, honestly and per
                 * activity, and the detail screen says out loud which it chose. So a disputed
                 * track is left for the athlete to import there, by hand, with that sentence
                 * under it. A track that agrees is only ever adding the line to the map.
                 */
                val stored = activity.distanceM
                val covered = trackMetres(available.points)
                if (stored != null && stored > 0 && covered > 0 &&
                    !agreesOnDistance(covered, stored)
                ) {
                    disputed++
                    continue
                }

                runCatching { importRoute(sourceUid, available.points) }
                    .onSuccess {
                        imported++
                        points += available.points.size
                    }
            }

            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < FEED_PAGE_LIMIT)

        _progress.value = Progress.Idle
        return RouteBackfill(checked, imported, points, disputed, more = cursor != null)
    }

    /** How far the track itself goes, by the same arithmetic the ingest measures a ride with. */
    private fun trackMetres(route: List<RoutePoint>): Double {
        var total = 0.0
        for (i in 1 until route.size) {
            total += Metrics.haversineMetres(
                route[i - 1].lat,
                route[i - 1].lon,
                route[i].lat,
                route[i].lon,
            )
        }
        return total
    }

    /** The reconciliation band, read the same way round as `Metrics.agreesWithin`. */
    private fun agreesOnDistance(candidate: Double, expected: Double): Boolean {
        val correction = expected / candidate
        return correction > Metrics.MIN_DISTANCE_CORRECTION &&
            correction < Metrics.MAX_DISTANCE_CORRECTION
    }

    /**
     * Re-ingests one workout with a GPS track the athlete has just granted.
     *
     * This is the second half of the per-activity route flow (R-015). The first half happens on
     * the detail screen, where the platform's consent screen names the workout and hands back
     * the points; here the session is read again and put through **the same ingest as a sync**,
     * so the polyline, bounds, distance, splits and the rewritten `.hht` are produced by one
     * implementation rather than by a second one that only route imports use.
     *
     * The duplicate verdict is recomputed rather than assumed, because the upload is idempotent
     * on the session id and re-posting without one would resurrect a recording the athlete's
     * source order had already set aside.
     *
     * Ten Health Connect reads, all for one workout the athlete explicitly asked about. That is
     * far above the per-session budget a backfill has to keep to, and deliberately so: the
     * quota is exhausted by hundreds of sessions read automatically, not by one read on a tap.
     */
    suspend fun importRoute(sourceUid: String, route: List<RoutePoint>): Ingested {
        require(route.isNotEmpty()) { "importRoute needs at least one point" }

        val session = healthConnect.readSession(sourceUid)
            ?: throw IllegalStateException("This workout is not in Health Connect on this phone.")

        _progress.value = Progress.Running(0, 1, session.title ?: "Importing route")
        try {
            val window = readWindow(session.startTime, session.endTime)

            // Padded, because the recordings this one may be a duplicate of start and end at
            // slightly different instants — that disagreement is the reason grouping exists.
            val neighbours = healthConnect.readSessions(
                session.startTime.minusSeconds(GROUPING_PAD_SECONDS),
                session.endTime.plusSeconds(GROUPING_PAD_SECONDS),
            )
            val preferences = runCatching { api.sources() }.getOrDefault(emptyList())
            val verdict = SessionGrouping.classify(
                sessions = neighbours.ifEmpty { listOf(session) },
                sourcePriority = preferences.associate { it.packageName to it.priority },
                disabledSources = preferences.filterNot { it.enabled }.map { it.packageName }.toSet(),
            )[session.metadata.id]

            return ingest(session, window, verdict, route)
        } finally {
            _progress.value = Progress.Idle
        }
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

    /**
     * Drops everything tied to the previously signed-in account.
     *
     * The sync cursor is per account: keeping it means the next account's first sync starts
     * from "now" and imports nothing, which presents as a sync button that does nothing.
     */
    suspend fun resetForNewAccount() {
        staging.clearState()
        staging.clearCache()
    }

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
        /**
         * Imports per backfill pass.
         *
         * Ten Health Connect reads and one upload each, so this is the number that decides how
         * much of the quota a sync spends on history rather than on today. Five a sync clears a
         * year of walking in a fortnight of ordinary use, and nobody waits for it.
         */
        const val ROUTE_BACKFILL_BUDGET = 5

        /** Feed pages the backfill will page through before leaving the rest for next time. */
        const val FEED_PAGE_LIMIT = 6
        const val FEED_PAGE = 50

        /** Below this there is no line to draw — `Route.geometry` drops such a segment. */
        const val MIN_ROUTE_POINTS = 2

        const val CURSOR_KEY = "ExerciseSession"
        const val PREVIEW_POINTS = 2_000

        /**
         * How much history one batch of reads covers. A day keeps each window's samples
         * small enough to hold in memory while collapsing a day's worth of sessions into
         * eight API calls instead of eight per session.
         */
        const val WINDOW_MS = 24L * 60 * 60 * 1000

        /**
         * Report ordering: what the athlete can do something about, first.
         *
         * A permission that has not been granted is a tap away from fixed; a domain switched off
         * is a switch away; a type this app does not model is neither, and is there so that its
         * absence is never silent.
         */
        val UNHANDLED_ORDER = listOf("permission:", "disabled:", "not-ingested:")

        /** How far either side of one session its possible duplicates are looked for. */
        const val GROUPING_PAD_SECONDS = 600L
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
