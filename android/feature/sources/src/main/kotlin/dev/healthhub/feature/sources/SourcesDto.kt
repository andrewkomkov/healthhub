package dev.healthhub.feature.sources

import kotlinx.serialization.Serializable

/**
 * The wire shapes these two screens need that `core:network` does not already carry.
 *
 * The feed DTO stops at the figures a card draws; the archive needs the three columns that
 * describe the *relationship* between recordings — which app wrote this one, how many apps
 * recorded the same workout, and whether the athlete has already overruled the automatic
 * choice. Those are the whole subject of this screen, so they live here rather than being
 * pushed into the shared client for one caller.
 */
@Serializable
data class ArchivedActivityDto(
    val id: String,
    val sport: String,
    val title: String,
    val startTime: Long,
    val tzOffsetMinutes: Int = 0,
    val elapsedSeconds: Long = 0,
    val movingSeconds: Long? = null,
    val distanceM: Double? = null,
    val elevationGainM: Double? = null,
    val avgSpeedMps: Double? = null,
    val avgHrBpm: Int? = null,
    /** Which app recorded this one — Strava, Samsung Health, a bike computer's own app. */
    val sourcePackage: String? = null,
    /** How many apps recorded the workout, this recording included. */
    val sourceCount: Int = 1,
    val visibility: String = "archived",
    /** `duplicate` when the phone set it aside, `manual` when the athlete did. */
    val archivedReason: String? = null,
    /** True once a manual decision took precedence; later syncs must not undo it. */
    val visibilityLocked: Boolean = false,
) {
    companion object {
        const val ACTIVE = "active"
        const val ARCHIVED = "archived"
    }
}

@Serializable
data class ArchivePageDto(
    val activities: List<ArchivedActivityDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class ActivityEnvelopeDto(val activity: ArchivedActivityDto)

/**
 * The body of `PATCH /api/activities/:id` that restores or sets aside one recording.
 *
 * Sending `visibility` on its own is what makes the Worker stamp `visibility_locked = 1`
 * (see `worker/src/routes/activities.ts`), and that flag is the entire promise of this
 * feature: the next sync's `ON CONFLICT` clause reads it and leaves the athlete's choice
 * alone. Title and description are deliberately absent — this request must not carry a field
 * it does not mean to change.
 */
@Serializable
data class VisibilityPatch(val visibility: String)

/**
 * One line of `PUT /api/sources`. The array order is the priority, and `priority` is sent
 * explicitly rather than left to the array index so a truncated request cannot silently
 * reshuffle the tail.
 */
@Serializable
data class SourceOrderEntry(
    val packageName: String,
    val enabled: Boolean,
    val priority: Int,
)

@Serializable
data class SourceOrderRequest(val sources: List<SourceOrderEntry>)
