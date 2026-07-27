package dev.healthhub.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The night arithmetic, against the two shapes a real recorder actually produces.
 *
 * Both failures this covers are silent ones: overlapping stages inflate a night the way summing
 * across sources inflated an 89.59 km ride, and a source that writes no stages at all would
 * otherwise report that the athlete slept for zero seconds.
 */
class SleepSummaryTest {

    private val bedtime = 1_753_560_600_000L
    private val hour = 3_600_000L

    private fun night(stages: List<SleepSummary.Stage>, hours: Long = 8) = SleepSummary.summarise(
        sourceUid = "uid",
        sourcePackage = "com.example.watch",
        title = "Sleep",
        startTime = bedtime,
        endTime = bedtime + hours * hour,
        tzOffsetMinutes = 180,
        stages = stages,
    )

    @Test
    fun `sums the stages a source reported and leaves the others absent`() {
        val summary = night(
            listOf(
                SleepSummary.Stage(SleepSummary.LIGHT, bedtime, bedtime + 4 * hour),
                SleepSummary.Stage(SleepSummary.DEEP, bedtime + 4 * hour, bedtime + 6 * hour),
                SleepSummary.Stage(SleepSummary.REM, bedtime + 6 * hour, bedtime + 8 * hour),
            ),
        )

        assertThat(summary.stageSeconds[SleepSummary.LIGHT]).isEqualTo(4 * 3600)
        assertThat(summary.stageSeconds[SleepSummary.DEEP]).isEqualTo(2 * 3600)
        assertThat(summary.stageSeconds[SleepSummary.REM]).isEqualTo(2 * 3600)
        // Never reported is not zero: an absent key uploads as null, and a recorder that does
        // not distinguish wakefulness must not appear to have measured none of it.
        assertThat(summary.stageSeconds).doesNotContainKey(SleepSummary.AWAKE)
        assertThat(summary.totalSeconds).isEqualTo(8 * 3600)
        assertThat(summary.timeInBedSeconds).isEqualTo(8 * 3600)
    }

    @Test
    fun `does not count an overlapping correction twice`() {
        val summary = night(
            listOf(
                SleepSummary.Stage(SleepSummary.LIGHT, bedtime, bedtime + 5 * hour),
                // The same hour again, relabelled by a later write.
                SleepSummary.Stage(SleepSummary.DEEP, bedtime + 4 * hour, bedtime + 6 * hour),
            ),
        )

        assertThat(summary.stageSeconds[SleepSummary.LIGHT]).isEqualTo(5 * 3600)
        assertThat(summary.stageSeconds[SleepSummary.DEEP]).isEqualTo(1 * 3600)
        assertThat(summary.totalSeconds).isEqualTo(6 * 3600)
    }

    @Test
    fun `clips a stage that runs past the end of the session`() {
        val summary = night(
            listOf(SleepSummary.Stage(SleepSummary.SLEEPING, bedtime - hour, bedtime + 20 * hour)),
        )

        assertThat(summary.stageSeconds[SleepSummary.SLEEPING]).isEqualTo(8 * 3600)
    }

    @Test
    fun `a night with no stages still slept`() {
        val summary = night(emptyList(), hours = 7)

        assertThat(summary.stageSeconds).isEmpty()
        assertThat(summary.totalSeconds).isEqualTo(7 * 3600)
    }

    @Test
    fun `a source that only marked the interruptions subtracts them`() {
        val summary = night(
            listOf(SleepSummary.Stage(SleepSummary.AWAKE, bedtime + hour, bedtime + 2 * hour)),
        )

        assertThat(summary.totalSeconds).isEqualTo(7 * 3600)
    }
}
