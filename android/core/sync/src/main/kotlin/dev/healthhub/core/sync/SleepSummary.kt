package dev.healthhub.core.sync

import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Instant
import java.time.ZoneId

/**
 * A night of sleep, reduced to the summary the edge stores and the hypnogram it files in R2.
 *
 * All of it is computed here, on the phone (Principle I). The Worker stores the stage totals
 * verbatim exactly as it stores splits and zones, and there is no sleep-quality index or
 * seven-day average anywhere on the API — the readiness surface derives those from these rows.
 *
 * The arithmetic is split from the Health Connect types on purpose: [summarise] takes plain
 * numbers so it can be tested without constructing an SDK record, and [of] is the thin adapter
 * that maps one `SleepSessionRecord` onto it.
 */
object SleepSummary {

    /** One interval of the hypnogram, in the stage vocabulary the API contract names. */
    data class Stage(val stage: String, val startTime: Long, val endTime: Long)

    data class Night(
        val sourceUid: String,
        val sourcePackage: String?,
        val title: String?,
        val startTime: Long,
        val endTime: Long,
        /** The offset at *waking*: the edge files a night under the local date it ended. */
        val tzOffsetMinutes: Int,
        val totalSeconds: Long,
        val timeInBedSeconds: Long,
        /**
         * Seconds per stage, holding **only stages this source actually reported**. An absent
         * key uploads as null, which the contract defines as "never reported" — not the same
         * claim as zero, and a recorder that does not distinguish REM must not appear to have
         * measured none of it.
         */
        val stageSeconds: Map<String, Long>,
        val stages: List<Stage>,
    )

    /** Health Connect's stage enum in the names `POST /api/health-records/sleep` accepts. */
    fun stageName(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> AWAKE
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> AWAKE_IN_BED
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> OUT_OF_BED
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> SLEEPING
        SleepSessionRecord.STAGE_TYPE_LIGHT -> LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> REM
        else -> UNKNOWN
    }

    fun of(record: SleepSessionRecord): Night {
        val startMs = record.startTime.toEpochMilli()
        val endMs = record.endTime.toEpochMilli()

        val stages = record.stages
            .map { Stage(stageName(it.stage), it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
            .sortedBy { it.startTime }

        // The offset at the *end* of the night. The night is named for the morning it finished,
        // so a 23:40 and a 00:20 bedtime on consecutive evenings do not collide on one column,
        // and sending the bedtime's offset would misfile a night that crossed a DST boundary.
        val offset = record.endZoneOffset
            ?: record.startZoneOffset
            ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(endMs))

        return summarise(
            sourceUid = record.metadata.id,
            sourcePackage = record.metadata.dataOrigin.packageName,
            title = record.title,
            startTime = startMs,
            endTime = endMs,
            tzOffsetMinutes = offset.totalSeconds / 60,
            stages = stages,
        )
    }

    /**
     * The whole of the analysis, over plain numbers.
     *
     * Two rules earn their place here:
     *
     *  - **Intervals are clipped to the session and never counted twice.** Sources write
     *    overlapping stages, and a night that adds up to nine hours in an eight-hour window is
     *    the sleep equivalent of the 89.59 km ride that came from summing across sources.
     *  - **Zero is not a measurement.** A source that reports no stages at all has recorded a
     *    duration and nothing else, so `totalSeconds` falls back to time in bed rather than
     *    claiming the athlete slept for none of it.
     */
    fun summarise(
        sourceUid: String,
        sourcePackage: String?,
        title: String?,
        startTime: Long,
        endTime: Long,
        tzOffsetMinutes: Int,
        stages: List<Stage>,
    ): Night {
        val timeInBedSeconds = ((endTime - startTime).coerceAtLeast(0)) / 1000

        val clipped = stages
            .map {
                Stage(
                    it.stage,
                    it.startTime.coerceIn(startTime, endTime),
                    it.endTime.coerceIn(startTime, endTime),
                )
            }
            .filter { it.endTime > it.startTime }
            .sortedBy { it.startTime }

        // Overlap goes to the interval that started first: the later one is the correction a
        // source appended, and double counting it inflates the night.
        val stageSeconds = mutableMapOf<String, Long>()
        var covered = startTime
        for (stage in clipped) {
            val from = maxOf(stage.startTime, covered)
            if (stage.endTime <= from) continue
            stageSeconds[stage.stage] =
                (stageSeconds[stage.stage] ?: 0L) + (stage.endTime - from) / 1000
            covered = stage.endTime
        }

        val asleep = ASLEEP_STAGES.sumOf { stageSeconds[it] ?: 0L }
        val awake = AWAKE_STAGES.sumOf { stageSeconds[it] ?: 0L }

        val totalSeconds = when {
            // A source that separated sleep from wakefulness has already answered the question.
            ASLEEP_STAGES.any { it in stageSeconds } -> asleep
            // One that only marked the interruptions still has: in bed, minus those.
            awake > 0 -> (timeInBedSeconds - awake).coerceAtLeast(0)
            else -> timeInBedSeconds
        }

        return Night(
            sourceUid = sourceUid,
            sourcePackage = sourcePackage,
            title = title,
            startTime = startTime,
            endTime = endTime,
            tzOffsetMinutes = tzOffsetMinutes,
            totalSeconds = totalSeconds,
            timeInBedSeconds = timeInBedSeconds,
            stageSeconds = stageSeconds,
            stages = clipped,
        )
    }

    const val AWAKE = "awake"
    const val AWAKE_IN_BED = "awakeInBed"
    const val OUT_OF_BED = "outOfBed"
    const val SLEEPING = "sleeping"
    const val LIGHT = "light"
    const val DEEP = "deep"
    const val REM = "rem"
    const val UNKNOWN = "unknown"

    /** Stages that count as sleep. `sleeping` is what a source reports when it cannot say more. */
    val ASLEEP_STAGES = listOf(SLEEPING, LIGHT, DEEP, REM)
    val AWAKE_STAGES = listOf(AWAKE, AWAKE_IN_BED, OUT_OF_BED)
}
