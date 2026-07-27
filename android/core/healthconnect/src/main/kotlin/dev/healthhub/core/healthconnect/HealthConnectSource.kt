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
        // Note what is NOT here: android.permission.health.READ_EXERCISE_ROUTE.
        //
        // It exists on the platform, but `dumpsys package permission` reports it as
        // `prot=signature`, owned by com.google.android.healthconnect.controller — so only
        // code signed with Google's key can ever hold it. There is no plural
        // READ_EXERCISE_ROUTES on this platform either. Requesting it produced a permission
        // that could never be granted, which then sat in the sync report forever as an
        // unmet requirement. See routesAvailableFor / requestRouteIntent below for the path
        // that is actually open to third-party apps.
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

    /**
     * The only route access open to a third-party app.
     *
     * Health Connect does not hand out bulk GPS access: `READ_EXERCISE_ROUTE` is
     * signature-level and reserved for Google's own Health Connect app. What an app like this
     * one can do is ask for **one specific session's route**, which shows the athlete a
     * confirmation naming that workout, and returns the track once.
     *
     * That is a per-activity action rather than a sync-wide permission, so it belongs on the
     * activity detail screen ("Import route"), not in the permission set.
     */
    fun requestRouteIntent(sessionId: String) = android.content.Intent(ACTION_REQUEST_EXERCISE_ROUTE)
        .putExtra(EXTRA_SESSION_ID, sessionId)

    companion object {
        private const val PAGE_SIZE = 1000

        /** Platform action for requesting a single session's route. */
        const val ACTION_REQUEST_EXERCISE_ROUTE =
            "android.health.connect.action.REQUEST_EXERCISE_ROUTE"
        const val EXTRA_SESSION_ID = "android.health.connect.extra.SESSION_ID"

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
