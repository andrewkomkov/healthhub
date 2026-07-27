package dev.healthhub.core.model

/**
 * The domain model shared by every module.
 *
 * Deliberately free of Android and of any serialization annotation: core:network owns the
 * wire format, core:database owns the storage format, and neither gets to reach in here and
 * shape the domain around its own convenience.
 */

/** One workout. Mirrors what the feed lists and the detail screen opens. */
data class Activity(
    val id: String,
    val sourceUid: String,
    val sport: Sport,
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
    val bounds: Bounds? = null,
    val sampleCount: Int = 0,
    val channels: List<String> = emptyList(),
    val splits: List<Split> = emptyList(),
    val zones: List<Zone> = emptyList(),
)

data class Bounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

/**
 * One GPS fix, independent of who recorded it.
 *
 * A track reaches the app by two different roads — inside an exercise session during a normal
 * sync, or one activity at a time through the platform's route-consent screen (R-015) — and
 * the ingest path must not be able to tell which. This is the type both roads arrive as.
 */
data class RoutePoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
)

data class Split(
    val index: Int,
    val unit: DistanceUnit,
    val distanceM: Double,
    val elapsedSeconds: Double,
    val movingSeconds: Double? = null,
    val avgSpeedMps: Double? = null,
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    val avgHrBpm: Int? = null,
    val avgPowerW: Double? = null,
)

data class Zone(
    val kind: ZoneKind,
    val index: Int,
    val lowerBound: Double,
    val upperBound: Double?,
    val seconds: Double,
)

enum class ZoneKind { HR, POWER }

enum class DistanceUnit(val metres: Double, val wire: String) {
    KILOMETRE(1_000.0, "km"),
    MILE(1_609.344, "mi"),
}

enum class UnitSystem { METRIC, IMPERIAL }

/**
 * Sports are a closed set with a stable slug, because the slug is a filter key and a colour
 * key. Anything Health Connect reports that is not listed maps to [OTHER] and is recorded in
 * the sync report rather than dropped (Principle VI).
 */
enum class Sport(val slug: String, val usesPace: Boolean) {
    RUNNING("running", usesPace = true),
    TRAIL_RUNNING("trail_running", usesPace = true),
    WALKING("walking", usesPace = true),
    HIKING("hiking", usesPace = true),
    CYCLING("cycling", usesPace = false),
    MOUNTAIN_BIKING("mountain_biking", usesPace = false),
    EBIKING("ebiking", usesPace = false),
    SWIMMING("swimming", usesPace = true),
    ROWING("rowing", usesPace = false),
    SKIING("skiing", usesPace = false),
    SNOWBOARDING("snowboarding", usesPace = false),
    SKATING("skating", usesPace = false),
    STRENGTH("strength", usesPace = false),
    YOGA("yoga", usesPace = false),
    OTHER("other", usesPace = false);

    companion object {
        private val bySlug = entries.associateBy(Sport::slug)
        fun fromSlug(slug: String): Sport = bySlug[slug] ?: OTHER
    }
}

/** The measured quantities a telemetry object can carry. */
enum class Channel(val wire: String) {
    TIME("t"),
    LATITUDE("lat"),
    LONGITUDE("lon"),
    ELEVATION("elevation"),
    HEART_RATE("hr"),
    SPEED("speed"),
    CADENCE("cadence"),
    POWER("power");

    companion object {
        private val byWire = entries.associateBy(Channel::wire)
        fun fromWire(wire: String): Channel? = byWire[wire]
    }
}

data class User(
    val id: String,
    val email: String,
    val displayName: String,
    val unitSystem: UnitSystem,
)

/**
 * The outcome of one sync attempt.
 *
 * [unhandledTypes] is how Principle VI's "fail loudly" rule reaches the athlete: a record
 * type the app did not understand is named here and shown on the sync screen, never silently
 * discarded.
 */
data class SyncReport(
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: SyncStatus = SyncStatus.RUNNING,
    val sessionsSynced: Int = 0,
    val samplesSynced: Long = 0,
    val failures: List<SyncFailure> = emptyList(),
    val unhandledTypes: List<String> = emptyList(),
    val message: String? = null,
)

data class SyncFailure(val sourceUid: String, val reason: String)

enum class SyncStatus { RUNNING, OK, PARTIAL, FAILED }
