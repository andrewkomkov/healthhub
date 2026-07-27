package dev.healthhub.feature.health

import kotlinx.serialization.Serializable

/**
 * What `GET /api/health-records/…` returns, as this module reads it.
 *
 * The edge stores what the phone derived and hands it back unchanged: there is no readiness
 * score, no rolling baseline and no seven-day average on the wire, because none of those is
 * computed at the edge (Principle I). Everything the screens show beyond these fields is
 * derived in [Trends] and [Readiness], here on the device.
 */
@Serializable
data class MeasurementDto(
    val id: String,
    val kind: String,
    val sourcePackage: String? = null,
    val measuredAt: Long,
    val tzOffsetMinutes: Int = 0,
    /** The athlete's own calendar day, computed on the edge from `measuredAt` and the offset. */
    val localDate: String = "",
    val value: Double,
    /** Diastolic, for blood pressure. Null for every other kind. */
    val secondaryValue: Double? = null,
    val unit: String = "",
)

@Serializable
data class MeasurementPageDto(
    val measurements: List<MeasurementDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class SleepDto(
    val id: String,
    val sourcePackage: String? = null,
    val title: String? = null,
    val startTime: Long,
    val endTime: Long,
    val tzOffsetMinutes: Int = 0,
    /** The morning, not the bedtime — the night is named for the day it ended. */
    val localDate: String = "",
    val totalSeconds: Long = 0,
    val timeInBedSeconds: Long? = null,
    /**
     * Seconds per stage. A null value means this source never reported that stage, which is
     * not the same claim as zero — the screen leaves it out rather than drawing an empty band.
     */
    val stages: Map<String, Long?> = emptyMap(),
    val stageCount: Int = 0,
    val hypnogram: HypnogramRefDto? = null,
)

@Serializable
data class HypnogramRefDto(val stored: Boolean = false, val bytes: Long? = null)

@Serializable
data class SleepPageDto(
    val sleeps: List<SleepDto> = emptyList(),
    val nextCursor: String? = null,
)
