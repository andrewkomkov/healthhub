package dev.healthhub.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire format, kept separate from the domain model on purpose: the API contract can
 * evolve without dragging the domain along, and a field rename on the server shows up here
 * as one edit rather than rippling through the app.
 */

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String,
    val unitSystem: String,
)

@Serializable
data class UserEnvelope(val user: UserDto)

/**
 * A partial update to the account.
 *
 * `explicitNulls = false` on the shared `Json` is what makes this work: a field left null is
 * omitted from the body entirely, and the Worker leaves the stored value alone rather than
 * writing a null over it — so changing units cannot silently clear a display name.
 */
@Serializable
data class PatchUserRequest(
    val displayName: String? = null,
    val unitSystem: String? = null,
)

/** For the handful of endpoints that take an empty JSON body rather than none. */
@Serializable
data object EmptyBody

/** Health Connect record ids whose workouts the athlete deleted at the source (FR-007). */
@Serializable
data class DeletedActivitiesRequest(val sourceUids: List<String>)

@Serializable
data class DeletedActivitiesResponse(val deleted: Int = 0)

/**
 * What a client-side KDF produced from the password (R-006 amendment).
 *
 * `scheme` names the algorithm and its parameters; `value` is 32 base64-encoded bytes. The
 * Worker hashes it again with a per-user salt and stores that.
 */
@Serializable
data class PasswordProofDto(val scheme: String, val value: String)

/**
 * Sign-in carries both credentials, because the account decides which one counts.
 *
 * An account created before the pre-hash amendment stored a hash of the password itself, and
 * no proof can verify it. Sending the password beside the proof is what lets the Worker verify
 * the old record and rewrite it under the new scheme in the same request; once every account
 * has signed in once, `password` comes out of this class.
 */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val passwordProofs: List<PasswordProofDto>,
)

/** Note what is absent: a new account is born pre-hashed, so the password never leaves here. */
@Serializable
data class RegisterRequest(
    val email: String,
    val displayName: String,
    val passwordProofs: List<PasswordProofDto>,
)

@Serializable
data class DeviceRegistrationRequest(
    val name: String,
    val platform: String = "android",
    val appVersion: String? = null,
)

@Serializable
data class DeviceDto(val id: String, val name: String, val createdAt: Long)

@Serializable
data class DeviceRegistrationResponse(val device: DeviceDto, val token: String)

@Serializable
data class SplitDto(
    val unit: String,
    val idx: Int,
    val distanceM: Double,
    val elapsedSeconds: Double,
    val movingSeconds: Double? = null,
    val avgSpeedMps: Double? = null,
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    val avgHrBpm: Int? = null,
    val avgPowerW: Double? = null,
)

@Serializable
data class ZoneDto(
    val kind: String,
    val zoneIndex: Int,
    val lowerBound: Double,
    val upperBound: Double? = null,
    val seconds: Double,
)

/**
 * One activity, with its metrics already computed on this device.
 *
 * The server stores these verbatim and never recomputes them (Constitution Principle I),
 * which is also what keeps the phone and the browser reporting identical figures.
 */
@Serializable
data class ActivityUploadRequest(
    val sourceUid: String,
    val sport: String,
    val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long,
    val tzOffsetMinutes: Int,
    val elapsedSeconds: Long,
    val movingSeconds: Long? = null,
    val distanceM: Double? = null,
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    val caloriesKcal: Double? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    val avgHrBpm: Int? = null,
    val maxHrBpm: Int? = null,
    val avgCadenceRpm: Double? = null,
    val avgPowerW: Double? = null,
    val maxPowerW: Double? = null,
    val hasGps: Boolean = false,
    val routePolyline: String? = null,
    val bounds: List<Double>? = null,
    val sampleCount: Int = 0,
    val channels: List<String> = emptyList(),
    /** Which app wrote this into Health Connect — Strava, Samsung Health, a bike computer. */
    val sourcePackage: String? = null,
    /** Set when another source's recording of the same workout represents it in the feed. */
    val duplicateOf: String? = null,
    /** How many sources reported this workout. */
    val sourceCount: Int = 1,
    val splits: List<SplitDto> = emptyList(),
    val zones: List<ZoneDto> = emptyList(),
)

@Serializable
data class ActivityUploadResponse(val activity: UploadedActivity)

@Serializable
data class UploadedActivity(val id: String, val telemetryUploadPath: String)

@Serializable
data class FeedActivityDto(
    val id: String,
    /** The Health Connect record behind this row; the route backfill matches on it. */
    val sourceUid: String? = null,
    val sport: String,
    val title: String,
    val startTime: Long,
    val tzOffsetMinutes: Int,
    val elapsedSeconds: Long,
    val movingSeconds: Long? = null,
    val distanceM: Double? = null,
    val elevationGainM: Double? = null,
    val avgSpeedMps: Double? = null,
    val avgHrBpm: Int? = null,
    val hasGps: Boolean = false,
    val routePolyline: String? = null,
    val bounds: List<Double>? = null,
    val sourcePackage: String? = null,
    val sourceCount: Int = 1,
)

@Serializable
data class FeedResponse(val activities: List<FeedActivityDto>, val nextCursor: String? = null)

@Serializable
data class CursorDto(
    val recordType: String,
    val changeToken: String? = null,
    val syncedUntil: Long? = null,
)

@Serializable
data class CursorsEnvelope(val cursors: List<CursorDto>)

@Serializable
data class SyncFailureDto(val sourceUid: String, val reason: String)

@Serializable
data class SyncReportRequest(
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: String,
    val sessionsSynced: Int = 0,
    val samplesSynced: Long = 0,
    val failures: List<SyncFailureDto> = emptyList(),
    val unhandledTypes: List<String> = emptyList(),
    val message: String? = null,
)

/** The Material You palette this phone extracted, so the web client can wear it too. */
@Serializable
data class ThemeUploadRequest(
    val light: Map<String, String>,
    val dark: Map<String, String>,
    val source: String = "dynamic",
)

@Serializable
data class ApiErrorBody(@SerialName("error") val error: ApiErrorDetail)

@Serializable
data class ApiErrorDetail(val code: String, val message: String)

@Serializable
data class SourceDto(
    val packageName: String,
    val priority: Int = 100,
    val enabled: Boolean = true,
    val label: String? = null,
    val activityCount: Int = 0,
)

@Serializable
data class SourcesEnvelope(val sources: List<SourceDto>)

@Serializable
data class SeenSource(val packageName: String, val label: String? = null)

@Serializable
data class SeenSourcesRequest(val packages: List<SeenSource>)
