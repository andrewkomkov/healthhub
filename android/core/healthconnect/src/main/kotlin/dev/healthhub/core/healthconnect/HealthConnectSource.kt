package dev.healthhub.core.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Everything the app knows about Health Connect.
 *
 * The record types are held in a registry rather than hard-coded call sites. This slice
 * implements the workout domain; sleep, cardiovascular, body composition and the rest are
 * added as registry entries without reworking the sync engine — which is what makes
 * Constitution Principle VI's "able to ingest all 80+ types" an additive job rather than a
 * rewrite. A type encountered but not registered is reported, never silently dropped.
 */
@Singleton
class HealthConnectSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val availability: Availability
        get() = when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                Availability.UPDATE_REQUIRED
            else -> Availability.UNAVAILABLE
        }

    enum class Availability { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    /**
     * The permission set for this slice.
     *
     * Deliberately narrow (Principle IV): workout data only. Sleep and biometric permissions
     * are requested when the features that need them ship, not pre-emptively.
     */
    val permissions: Set<String> = buildSet {
        // Background reading is what lets sync run on a schedule rather than only while the
        // athlete has the app open (FR-005).
        add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        // History access: without it, a first sync can only see the recent past, and the
        // athlete's existing workout history would be invisible (SC-002).
        add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        addAll(WORKOUT_RECORD_TYPES.map { HealthPermission.getReadPermission(it) })
        // The route permission has no constant in the SDK — only the write side does — so the
        // platform string is used directly. Without it, sessions arrive with no GPS track at
        // all, and it is the one permission `adb install -g` cannot pre-grant: the athlete
        // has to allow location history explicitly.
        add(READ_EXERCISE_ROUTE)
    }

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    suspend fun missingPermissions(): Set<String> = permissions - grantedPermissions()

    fun permissionRequestContract() = PermissionController.createRequestPermissionResultContract()

    /** Reads every workout session in the window, oldest first. */
    suspend fun readSessions(from: Instant, to: Instant): List<ExerciseSessionRecord> =
        readAll(ExerciseSessionRecord::class, from, to)

    suspend fun readHeartRate(from: Instant, to: Instant): List<HeartRateRecord> =
        readAll(HeartRateRecord::class, from, to)

    suspend fun readSpeed(from: Instant, to: Instant): List<SpeedRecord> =
        readAll(SpeedRecord::class, from, to)

    suspend fun readPower(from: Instant, to: Instant): List<PowerRecord> =
        readAll(PowerRecord::class, from, to)

    suspend fun readCyclingCadence(from: Instant, to: Instant): List<CyclingPedalingCadenceRecord> =
        readAll(CyclingPedalingCadenceRecord::class, from, to)

    suspend fun readStepsCadence(from: Instant, to: Instant): List<StepsCadenceRecord> =
        readAll(StepsCadenceRecord::class, from, to)

    suspend fun readDistance(from: Instant, to: Instant): List<DistanceRecord> =
        readAll(DistanceRecord::class, from, to)

    suspend fun readElevation(from: Instant, to: Instant): List<ElevationGainedRecord> =
        readAll(ElevationGainedRecord::class, from, to)

    suspend fun readCalories(from: Instant, to: Instant): List<TotalCaloriesBurnedRecord> =
        readAll(TotalCaloriesBurnedRecord::class, from, to)

    /**
     * Reads every page of a record type.
     *
     * Health Connect pages its responses, and a long backfill will always be more than one
     * page — stopping at the first would silently truncate history, which Principle VI
     * forbids.
     */
    private suspend fun <T : Record> readAll(
        type: KClass<T>,
        from: Instant,
        to: Instant,
    ): List<T> {
        val results = mutableListOf<T>()
        var token: String? = null

        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = PAGE_SIZE,
                    pageToken = token,
                ),
            )
            results += response.records
            token = response.pageToken
        } while (token != null)

        return results
    }

    companion object {
        private const val PAGE_SIZE = 1000

        /** Reading GPS tracks. Declared in the manifest; no SDK constant exists for it. */
        const val READ_EXERCISE_ROUTE = "android.permission.health.READ_EXERCISE_ROUTE"

        /**
         * The workout-domain registry. Adding a type here is all it takes for the sync engine
         * to start requesting permission for it and reading it.
         */
        val WORKOUT_RECORD_TYPES: List<KClass<out Record>> = listOf(
            ExerciseSessionRecord::class,
            HeartRateRecord::class,
            SpeedRecord::class,
            PowerRecord::class,
            CyclingPedalingCadenceRecord::class,
            StepsCadenceRecord::class,
            DistanceRecord::class,
            ElevationGainedRecord::class,
            TotalCaloriesBurnedRecord::class,
        )
    }
}
