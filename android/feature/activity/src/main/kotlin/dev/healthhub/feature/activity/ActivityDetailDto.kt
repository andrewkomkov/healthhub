package dev.healthhub.feature.activity

import dev.healthhub.core.network.SplitDto
import dev.healthhub.core.network.ZoneDto
import kotlinx.serialization.Serializable

/**
 * `GET /api/activities/:id`.
 *
 * The wire shape of the detail response, which the feed's DTO does not cover: splits, zones,
 * the channel list and which telemetry variants exist. Every figure here was computed on a
 * phone at ingest time and is read verbatim — the Worker stores these columns and never
 * recomputes one (Constitution Principle I), which is the whole reason the two clients agree.
 */
@Serializable
data class ActivityDetailDto(
    val id: String,
    val sport: String,
    val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long = 0,
    val tzOffsetMinutes: Int = 0,
    val elapsedSeconds: Long = 0,
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
    val sourcePackage: String? = null,
    /**
     * The Health Connect record id this activity was ingested from, when the server knows it.
     *
     * Null for an activity uploaded before the field existed, which is why [RouteImporter] still
     * carries the start-time fallback. Prefer this: two apps recording the same walk produce two
     * sessions sharing a start instant and a source package, and matching on those picks one of
     * the pair at random — which is how a route import landed on the duplicate of the activity
     * the athlete was looking at.
     */
    val sourceUid: String? = null,
    val sourceCount: Int = 1,
    /**
     * "active" or "archived". Read here because importing a route re-uploads the workout, and
     * an upload carries the duplicate verdict with it — so the screen has to know when a
     * recording is one the athlete's source order set aside.
     */
    val visibility: String = "active",
    val archivedReason: String? = null,
    val visibilityLocked: Boolean = false,
    val sampleCount: Int = 0,
    val channels: List<String> = emptyList(),
    val telemetry: TelemetryAvailabilityDto = TelemetryAvailabilityDto(),
    val splits: List<SplitDto> = emptyList(),
    val zones: List<ZoneDto> = emptyList(),
)

@Serializable
data class TelemetryAvailabilityDto(
    val full: Boolean = false,
    val preview: Boolean = false,
    val bytes: Long? = null,
)

@Serializable
data class ActivityDetailEnvelope(val activity: ActivityDetailDto)
