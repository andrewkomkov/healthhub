package dev.healthhub.core.sync

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.WeightRecord
import dev.healthhub.core.database.StagingDao
import dev.healthhub.core.database.SyncStateEntity
import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.healthconnect.HealthDomain
import dev.healthhub.core.healthconnect.HealthFeatures
import dev.healthhub.core.healthconnect.HealthRecordRegistry
import dev.healthhub.core.model.SyncFailure
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The daily-grain half of a sync: sleep, and the scalar measurements that make up recovery.
 *
 * It is a separate pass from the workout sync for one reason — this data exists on days with no
 * workout on them. Hanging it off the session windows would silently lose every reading taken on
 * a rest day, which is most of them.
 *
 * **The window is thirty days, not one.** The workout pass uses a day because a day of heart-rate
 * samples is as much as should be held in memory at once; a month of resting heart rates is
 * thirty rows. What both passes have in common is the shape that matters: *one read per record
 * type per window, never one per record*. Eight reads per session is what exhausted Health
 * Connect's quota around session 75 with "API call quota exceeded", and seven new types on a
 * per-record loop would reproduce it immediately. At a month per window a full year's backfill
 * of every domain costs eighty-four calls.
 *
 * A night arrives whole: `SleepSessionRecord` carries its own stage intervals, so the hypnogram
 * is free.
 */
@Singleton
class HealthRecordSync @Inject constructor(
    private val healthConnect: HealthConnectSource,
    private val features: HealthFeatures,
    private val uploader: HealthRecordUploader,
    private val staging: StagingDao,
) {

    /**
     * What one pass did, and — just as importantly — what it did not do and why.
     *
     * [skipped] is the entire reason this type is not just a pair of counters. A domain that was
     * never read because the athlete has it switched off, or because its permission is not
     * granted, has to reach the sync report by name. Silence is the failure Principle VI exists
     * to prevent, and "no sleep data" looks identical to "sleep is broken" from the outside.
     */
    data class Outcome(
        val measurementsSynced: Int = 0,
        val nightsSynced: Int = 0,
        val hypnogramsSynced: Int = 0,
        val windowsRead: Int = 0,
        val readCalls: Int = 0,
        val failures: List<SyncFailure> = emptyList(),
        val skipped: List<String> = emptyList(),
    )

    /**
     * Reads and uploads everything in the enabled domains between [from] and [to].
     *
     * Each domain carries its own cursor, so switching sleep on a year after installing
     * backfills sleep without re-reading a year of body weight — and a domain whose upload
     * failed does not advance, while its neighbours do.
     */
    suspend fun sync(
        from: Instant,
        to: Instant,
        onStage: (String) -> Unit = {},
    ): Outcome {
        val enabled = features.enabled.value
        val failures = mutableListOf<SyncFailure>()
        val skipped = mutableListOf<String>()

        // Named before anything is read: a domain that is off has to be visible in the report
        // of the sync that ignored it, not discovered later by its absence.
        features.disabled().forEach { skipped += "disabled:${it.slug}" }

        val granted = runCatching { healthConnect.grantedPermissions() }.getOrDefault(emptySet())

        var measurements = 0
        var nights = 0
        var hypnograms = 0
        var windows = 0
        var reads = 0

        for (domain in DAILY_DOMAINS) {
            if (domain !in enabled) continue

            val required = HealthRecordRegistry.forDomains(setOf(domain)).map { it.permission }
            val ungranted = required.filterNot { it in granted }
            if (ungranted.isNotEmpty()) {
                // Not read at all rather than read and failed: a request the provider will
                // refuse still costs a call against the quota, and the report says the same
                // thing either way.
                ungranted.forEach { skipped += "permission:$it" }
                continue
            }

            val start = cursorFor(domain) ?: from
            if (!start.isBefore(to)) continue

            var domainFailed = false
            for (window in windowsBetween(start, to)) {
                onStage("${domain.label} · ${window.start}")
                windows += 1

                val outcome = runCatching {
                    when (domain) {
                        HealthDomain.SLEEP -> {
                            reads += 1
                            syncSleep(window.start, window.end)
                        }

                        else -> {
                            val types = HealthRecordRegistry.measurementsIn(setOf(domain))
                            reads += types.size
                            WindowTally(measurements = syncMeasurements(domain, window.start, window.end))
                        }
                    }
                }

                outcome
                    .onSuccess {
                        measurements += it.measurements
                        nights += it.nights
                        hypnograms += it.hypnograms
                    }
                    .onFailure { error ->
                        domainFailed = true
                        failures += SyncFailure(
                            sourceUid = "${domain.slug}@${window.start}",
                            reason = error.message ?: error::class.simpleName.orEmpty(),
                        )
                    }
            }

            // Same invariant as the workout cursor: it moves only behind a confirmed upload.
            // Reversing that loses a window of readings on any interrupted sync.
            if (!domainFailed) recordCursor(domain, to)
        }

        return Outcome(
            measurementsSynced = measurements,
            nightsSynced = nights,
            hypnogramsSynced = hypnograms,
            windowsRead = windows,
            readCalls = reads,
            failures = failures,
            skipped = skipped,
        )
    }

    // Signing out drops these cursors with every other one: SyncEngine.resetForNewAccount
    // clears the whole sync_state table, and a cursor left behind would start the next
    // account's first health sync at "now" and import nothing.

    private class WindowTally(
        val measurements: Int = 0,
        val nights: Int = 0,
        val hypnograms: Int = 0,
    )

    private suspend fun syncSleep(from: Instant, to: Instant): WindowTally {
        val records = healthConnect.readSleepSessions(from, to)
        if (records.isEmpty()) return WindowTally()

        var nights = 0
        var hypnograms = 0
        for (record in records) {
            val night = SleepSummary.of(record)
            val id = uploader.putSleep(
                SleepUploadDto(
                    sourceUid = night.sourceUid,
                    sourcePackage = night.sourcePackage,
                    title = night.title,
                    startTime = night.startTime,
                    endTime = night.endTime,
                    tzOffsetMinutes = night.tzOffsetMinutes,
                    totalSeconds = night.totalSeconds,
                    timeInBedSeconds = night.timeInBedSeconds,
                    stages = night.stageSeconds,
                    stageCount = night.stages.size,
                ),
            )
            nights += 1

            // A night with no stage detail is a real recording — plenty of sources write only a
            // duration — and uploading an empty object would make the screen claim a hypnogram
            // it does not have.
            if (night.stages.isNotEmpty()) {
                uploader.putHypnogram(
                    id,
                    night.stages.map { StageDto(it.stage, it.startTime, it.endTime) },
                )
                hypnograms += 1
            }
        }
        return WindowTally(nights = nights, hypnograms = hypnograms)
    }

    /**
     * Every scalar type in one domain, read once each for the window and uploaded in one batch.
     *
     * Readings from different apps are all kept: two apps reporting this morning's resting heart
     * rate are two measurements, each idempotent on its own Health Connect id, and picking
     * between them is the screen's job. That is the opposite of the workout rule only in
     * appearance — what that rule forbids is *summing* across sources, and nothing here sums.
     */
    private suspend fun syncMeasurements(
        domain: HealthDomain,
        from: Instant,
        to: Instant,
    ): Int {
        val kinds = HealthRecordRegistry.measurementsIn(setOf(domain)).mapNotNull { it.measurementKind }
        val batch = mutableListOf<MeasurementDto>()

        for (kind in kinds) {
            batch += when (kind) {
                HealthRecordRegistry.Kind.HRV_RMSSD ->
                    healthConnect.readHrv(from, to).map { it.asMeasurement(kind) }

                HealthRecordRegistry.Kind.RESTING_HEART_RATE ->
                    healthConnect.readRestingHeartRate(from, to).map { it.asMeasurement(kind) }

                HealthRecordRegistry.Kind.OXYGEN_SATURATION ->
                    healthConnect.readOxygenSaturation(from, to).map { it.asMeasurement(kind) }

                HealthRecordRegistry.Kind.WEIGHT ->
                    healthConnect.readWeight(from, to).map { it.asMeasurement(kind) }

                HealthRecordRegistry.Kind.BODY_FAT ->
                    healthConnect.readBodyFat(from, to).map { it.asMeasurement(kind) }

                HealthRecordRegistry.Kind.BLOOD_PRESSURE ->
                    healthConnect.readBloodPressure(from, to).map { it.asMeasurement(kind) }

                // Unreachable while the registry and this `when` agree. If they ever stop
                // agreeing, the type is named rather than skipped in silence.
                else -> error("No reader for measurement kind '$kind'")
            }
        }

        if (batch.isEmpty()) return 0
        return uploader.putMeasurements(batch)
    }

    private fun HeartRateVariabilityRmssdRecord.asMeasurement(kind: String) = measurement(
        kind = kind,
        sourceUid = metadata.id,
        sourcePackage = metadata.dataOrigin.packageName,
        at = time,
        offset = zoneOffset,
        value = heartRateVariabilityMillis,
    )

    private fun RestingHeartRateRecord.asMeasurement(kind: String) = measurement(
        kind = kind,
        sourceUid = metadata.id,
        sourcePackage = metadata.dataOrigin.packageName,
        at = time,
        offset = zoneOffset,
        value = beatsPerMinute.toDouble(),
    )

    private fun OxygenSaturationRecord.asMeasurement(kind: String) = measurement(
        kind = kind,
        sourceUid = metadata.id,
        sourcePackage = metadata.dataOrigin.packageName,
        at = time,
        offset = zoneOffset,
        value = percentage.value,
    )

    private fun WeightRecord.asMeasurement(kind: String) = measurement(
        kind = kind,
        sourceUid = metadata.id,
        sourcePackage = metadata.dataOrigin.packageName,
        at = time,
        offset = zoneOffset,
        value = weight.inKilograms,
    )

    private fun BodyFatRecord.asMeasurement(kind: String) = measurement(
        kind = kind,
        sourceUid = metadata.id,
        sourcePackage = metadata.dataOrigin.packageName,
        at = time,
        offset = zoneOffset,
        value = percentage.value,
    )

    /** The one type that needs both numbers: value is systolic, secondary is diastolic. */
    private fun BloodPressureRecord.asMeasurement(kind: String) = measurement(
        kind = kind,
        sourceUid = metadata.id,
        sourcePackage = metadata.dataOrigin.packageName,
        at = time,
        offset = zoneOffset,
        value = systolic.inMillimetersOfMercury,
        secondary = diastolic.inMillimetersOfMercury,
    )

    private fun measurement(
        kind: String,
        sourceUid: String,
        sourcePackage: String?,
        at: Instant,
        offset: ZoneOffset?,
        value: Double,
        secondary: Double? = null,
    ) = MeasurementDto(
        sourceUid = sourceUid,
        sourcePackage = sourcePackage,
        kind = kind,
        measuredAt = at.toEpochMilli(),
        tzOffsetMinutes = (offset ?: ZoneId.systemDefault().rules.getOffset(at)).totalSeconds / 60,
        value = value,
        secondaryValue = secondary,
        unit = UNITS.getValue(kind),
    )

    private data class Window(val start: Instant, val end: Instant)

    private fun windowsBetween(from: Instant, to: Instant): List<Window> {
        val windows = mutableListOf<Window>()
        var cursor = from
        while (cursor.isBefore(to)) {
            val end = minOf(cursor.plusMillis(WINDOW_MS), to)
            windows += Window(cursor, end)
            cursor = end
        }
        return windows
    }

    private suspend fun cursorFor(domain: HealthDomain): Instant? =
        staging.state(cursorKey(domain))?.syncedUntil?.let(Instant::ofEpochMilli)

    private suspend fun recordCursor(domain: HealthDomain, until: Instant) {
        staging.putState(
            SyncStateEntity(
                recordType = cursorKey(domain),
                changeToken = null,
                syncedUntil = until.toEpochMilli(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun cursorKey(domain: HealthDomain) = "health:${domain.slug}"

    private companion object {
        /** Everything except workouts, which the session pass already covers. */
        val DAILY_DOMAINS = listOf(
            HealthDomain.SLEEP,
            HealthDomain.RECOVERY,
            HealthDomain.BODY,
            HealthDomain.VITALS,
        )

        const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        val UNITS: Map<String, String> = HealthRecordRegistry.entries
            .mapNotNull { entry -> entry.measurementKind?.let { it to entry.unit.orEmpty() } }
            .toMap()
    }
}
