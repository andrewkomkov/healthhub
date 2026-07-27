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

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String)

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
