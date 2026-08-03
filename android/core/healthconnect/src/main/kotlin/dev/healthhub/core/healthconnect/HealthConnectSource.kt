package dev.healthhub.core.healthconnect

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.healthhub.core.model.RoutePoint
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.reflect.KClass

/**
 * Everything the app knows about Health Connect.
 *
 * The record types are held in a registry rather than hard-coded call sites — see
 * [HealthRecordRegistry], which now covers workouts, sleep, recovery, body composition and
 * blood pressure. That registry is what makes Constitution Principle VI's "able to ingest all
 * 80+ types" an additive job rather than a rewrite, and its `notIngested` list is what keeps
 * everything it does *not* cover from being silently absent.
 */
@Singleton
class HealthConnectSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val features: HealthFeatures,
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
     * What to ask for right now: the domains the athlete has switched on, and nothing else.
     *
     * Principle IV is enforced here rather than described. A fresh install has only
     * [HealthDomain.WORKOUTS] on, so the first dialogue asks for exercise data; turning on
     * sleep or recovery on the health screen widens this set and the request is made there,
     * next to the sentence explaining why. Asking for all of it at install time is how an
     * athlete decides to grant none of it.
     */
    val permissions: Set<String> get() = permissionsFor(features.enabled.value)

    fun permissionsFor(domains: Set<HealthDomain>): Set<String> = buildSet {
        // Background reading is what lets sync run on a schedule rather than only while the
        // athlete has the app open (FR-005).
        add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        // History access: without it, a first sync can only see the recent past, and the
        // athlete's existing workout history would be invisible (SC-002).
        add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        addAll(HealthRecordRegistry.forDomains(domains).map { it.permission })
        // Note what is NOT here: either of the two route permissions, for two different reasons.
        //
        // Singular android.permission.health.READ_EXERCISE_ROUTE is `prot=signature`, owned by
        // com.google.android.healthconnect.controller, so only Google-signed code can ever hold
        // it. Requesting it produced a permission that could never be granted, which then sat in
        // the sync report forever as an unmet requirement.
        //
        // Plural android.permission.health.READ_EXERCISE_ROUTES is `prot=dangerous` and could be
        // requested — that is exactly why it is left out. Granting it is the athlete saying "any
        // workout's GPS, without asking again", and this app asks per workout instead (R-015).
        // It is *declared* in the manifest, because RouteRequestActivity refuses to draw its
        // confirmation for a caller that has not declared it, and nothing but declaring it makes
        // that dialogue appear. Declaring is not asking; only this set does the asking.
    }

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    suspend fun missingPermissions(): Set<String> = permissions - grantedPermissions()

    /**
     * What one domain still needs, whether or not it is switched on.
     *
     * The health screen asks this before offering a switch, so turning sleep on can request
     * exactly the sleep permission instead of re-prompting for everything already held.
     */
    suspend fun missingPermissionsFor(domain: HealthDomain): Set<String> {
        val granted = grantedPermissions()
        return HealthRecordRegistry.forDomains(setOf(domain))
            .map { it.permission }
            .filterNot { it in granted }
            .toSet()
    }

    /** True when every record type this domain needs is already granted. */
    suspend fun isGranted(domain: HealthDomain): Boolean = missingPermissionsFor(domain).isEmpty()

    fun permissionRequestContract() = PermissionController.createRequestPermissionResultContract()

    /** Reads every workout session in the window, oldest first. */
    /* ------------------------------------------------------------------------- deletions */

    /**
     * What a recording that has gone away looks like to this app.
     *
     * [recordIds] are Health Connect record ids, which is what an activity's `sourceUid` is —
     * so the caller can name the workouts to remove without a second lookup.
     *
     * [token] is the cursor to store for next time. [expired] says the stored one was too old
     * to use, which Health Connect does after roughly a month: the honest response is to take
     * a fresh token and report nothing, because a gap in the change log is a gap, and inventing
     * deletions from it would remove workouts nobody deleted.
     */
    data class Deletions(
        val recordIds: List<String>,
        val token: String,
        val expired: Boolean,
    )

    /**
     * A cursor into the change log, for the record types this app stores.
     *
     * There is no way to ask "what is gone" — only "what has changed since this token", and a
     * token that does not exist yet cannot answer for the past. So the first sync takes a token
     * and reports nothing, and deletions are propagated from the second sync onwards. That is
     * the platform's shape, not a shortcut: `readRecords` returns what exists, and absence is
     * not something a range query can report.
     */
    suspend fun changesToken(): String =
        client.getChangesToken(ChangesTokenRequest(recordTypes = setOf(ExerciseSessionRecord::class)))

    /** Everything the change log has to say since [token], deletions only. */
    suspend fun deletionsSince(token: String): Deletions {
        val removed = mutableListOf<String>()
        var cursor = token

        // The log is paged, and a phone that has not synced for a fortnight has several pages
        // of them. Draining it here rather than leaving a tail for next time is what stops a
        // deletion sitting unpropagated behind an upsertion-heavy page.
        while (true) {
            val response = client.getChanges(cursor)
            if (response.changesTokenExpired) {
                return Deletions(emptyList(), changesToken(), expired = true)
            }
            response.changes.filterIsInstance<DeletionChange>().mapTo(removed) { it.recordId }
            cursor = response.nextChangesToken
            if (!response.hasMore) break
        }

        return Deletions(removed, cursor, expired = false)
    }

    suspend fun readSessions(from: Instant, to: Instant): List<ExerciseSessionRecord> =
        readAll(ExerciseSessionRecord::class, from, to)

    /**
     * One session by the id the activity was uploaded under, or null if this phone has never
     * seen it.
     *
     * Null is a real answer, not a swallowed error: an activity synced from the athlete's other
     * phone is on the server and not in this device's Health Connect, and the detail screen has
     * to be able to say so.
     */
    suspend fun readSession(recordId: String): ExerciseSessionRecord? =
        runCatching { client.readRecord(ExerciseSessionRecord::class, recordId).record }.getOrNull()

    /**
     * Finds the session an uploaded activity came from.
     *
     * The server stores the Health Connect record id as `sourceUid` but does not return it, so
     * the phone matches on what the detail response does carry: the recording app and the start
     * instant, which the upload took verbatim from the session. The window is padded because a
     * `between` filter is not obliged to return a record that merely overlaps its edge.
     */
    suspend fun findSession(
        startTimeMs: Long,
        endTimeMs: Long,
        sourcePackage: String?,
    ): ExerciseSessionRecord? {
        val candidates = readSessions(
            Instant.ofEpochMilli(startTimeMs).minusMillis(MATCH_TOLERANCE_MS),
            Instant.ofEpochMilli(endTimeMs).plusMillis(MATCH_TOLERANCE_MS),
        ).filter { sourcePackage == null || it.metadata.dataOrigin.packageName == sourcePackage }

        return candidates.firstOrNull { it.startTime.toEpochMilli() == startTimeMs }
            ?: candidates
                .filter { abs(it.startTime.toEpochMilli() - startTimeMs) <= MATCH_TOLERANCE_MS }
                .minByOrNull { abs(it.startTime.toEpochMilli() - startTimeMs) }
    }

    /**
     * What this session has to say about a GPS track — which is three different answers, and
     * conflating them is exactly the failure this API exists to prevent.
     *
     * [RouteState.Absent] means the recording app wrote no positions at all. That is data
     * absence, not a permission problem, and presenting it as one sends the athlete hunting
     * through Health Connect's settings for a switch that does not exist.
     */
    fun routeState(session: ExerciseSessionRecord): RouteState =
        when (val result = session.exerciseRouteResult) {
            is ExerciseRouteResult.Data -> RouteState.Available(
                result.exerciseRoute.route.map {
                    RoutePoint(it.time.toEpochMilli(), it.latitude, it.longitude, it.altitude?.inMeters)
                },
            )

            // The session holds a track, and the platform will only hand it over after the
            // athlete confirms this one workout by name.
            is ExerciseRouteResult.ConsentRequired -> RouteState.ConsentRequired

            // Named rather than swept into the `else`: this is the answer the whole per-activity
            // flow is arranged around. The recording app wrote no positions, so there is nothing
            // to consent to and no setting that would produce one.
            is ExerciseRouteResult.NoData -> RouteState.Absent

            // ExerciseRouteResult is an abstract class rather than a sealed one, so the branch
            // is required. A subtype added by a later SDK is treated as "no track" rather than
            // as consent, because inviting an athlete to grant something that may not exist is
            // the failure this type was written to prevent.
            else -> RouteState.Absent
        }

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

    /* ------------------------------------------------------------- daily grain
     *
     * Sleep and the scalar measurements exist on days with no workout at all, so they cannot
     * ride along with the session windows — they get their own pass in core:sync. What they
     * keep is the *shape*: one read per type per window, never one per record. Eight reads per
     * session is what exhausted the provider's quota around session 75, and adding seven types
     * to a per-record loop would reproduce it exactly.
     *
     * A night arrives whole: SleepSessionRecord carries its own stage intervals, so the
     * hypnogram costs no additional call.
     */

    suspend fun readSleepSessions(from: Instant, to: Instant): List<SleepSessionRecord> =
        readAll(SleepSessionRecord::class, from, to)

    suspend fun readHrv(from: Instant, to: Instant): List<HeartRateVariabilityRmssdRecord> =
        readAll(HeartRateVariabilityRmssdRecord::class, from, to)

    suspend fun readRestingHeartRate(from: Instant, to: Instant): List<RestingHeartRateRecord> =
        readAll(RestingHeartRateRecord::class, from, to)

    suspend fun readOxygenSaturation(from: Instant, to: Instant): List<OxygenSaturationRecord> =
        readAll(OxygenSaturationRecord::class, from, to)

    suspend fun readWeight(from: Instant, to: Instant): List<WeightRecord> =
        readAll(WeightRecord::class, from, to)

    suspend fun readBodyFat(from: Instant, to: Instant): List<BodyFatRecord> =
        readAll(BodyFatRecord::class, from, to)

    suspend fun readBloodPressure(from: Instant, to: Instant): List<BloodPressureRecord> =
        readAll(BloodPressureRecord::class, from, to)

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
     * activity detail screen ("Import route"), not in the permission set. Use the contract
     * rather than building [ACTION_REQUEST_EXERCISE_ROUTE] by hand: that action only exists on
     * API 34 and above, and on a device where Health Connect is still an installed APK the
     * request has to travel over the SDK's own service instead. The contract picks the road.
     */
    fun routeRequestContract(): ActivityResultContract<String, List<RoutePoint>?> =
        ExerciseRouteContract()

    companion object {
        private const val PAGE_SIZE = 1000

        /**
         * How far an uploaded activity's start may sit from its session's.
         *
         * They are the same number in every activity this app uploaded — the upload copies the
         * session's instant — so this only ever absorbs the edge behaviour of the time filter.
         */
        private const val MATCH_TOLERANCE_MS = 60_000L

        /** Platform action for requesting a single session's route. */
        const val ACTION_REQUEST_EXERCISE_ROUTE =
            "android.health.connect.action.REQUEST_EXERCISE_ROUTE"
        const val EXTRA_SESSION_ID = "android.health.connect.extra.SESSION_ID"

        /**
         * The workout-domain registry, derived rather than repeated.
         *
         * It used to be the whole registry; it is now one domain of [HealthRecordRegistry], and
         * reading it from there is what stops the two lists disagreeing about which types the
         * workout sync covers.
         */
        val WORKOUT_RECORD_TYPES: List<KClass<out Record>>
            get() = HealthRecordRegistry.forDomains(setOf(HealthDomain.WORKOUTS))
                .map { it.recordType }
    }
}
