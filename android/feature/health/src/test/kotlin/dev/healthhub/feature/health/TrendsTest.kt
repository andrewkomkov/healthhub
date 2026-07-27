package dev.healthhub.feature.health

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The trend arithmetic.
 *
 * The first test is the sleep-and-recovery version of the 89.59 km ride: Health Connect is a
 * hub, two apps report this morning's resting heart rate, and a chart that adds them draws a
 * resting pulse of 96 for someone whose pulse is 48.
 */
class TrendsTest {

    private fun reading(date: String, value: Double, kind: String = "resting_heart_rate") =
        MeasurementDto(
            id = "$date-$value",
            kind = kind,
            measuredAt = 0,
            localDate = date,
            value = value,
            unit = "bpm",
        )

    @Test
    fun `two apps reporting one morning is one value, not their sum`() {
        val days = Trends.daily(
            listOf(reading("2026-07-20", 48.0), reading("2026-07-20", 50.0)),
        )

        assertThat(days).hasSize(1)
        assertThat(days.single().value).isEqualTo(49.0)
    }

    @Test
    fun `days come back oldest first`() {
        val days = Trends.daily(
            listOf(
                reading("2026-07-22", 3.0),
                reading("2026-07-20", 1.0),
                reading("2026-07-21", 2.0),
            ),
        )

        assertThat(days.map { it.date })
            .containsExactly("2026-07-20", "2026-07-21", "2026-07-22").inOrder()
        assertThat(Trends.latest(days)?.value).isEqualTo(3.0)
    }

    @Test
    fun `a reading with no local date is not a day`() {
        assertThat(Trends.daily(listOf(reading("", 48.0)))).isEmpty()
    }

    @Test
    fun `the baseline excludes today`() {
        // Nine ordinary days, then one that is nothing like them.
        val days = (1..9).map { DayValue("2026-07-%02d".format(it), 50.0) } +
            DayValue("2026-07-10", 90.0)

        assertThat(Trends.baseline(days, window = 60)).isEqualTo(50.0)
    }

    @Test
    fun `too little history is no baseline at all`() {
        val days = (1..5).map { DayValue("2026-07-0$it", 50.0) }

        assertThat(Trends.baseline(days, window = 60)).isNull()
    }

    @Test
    fun `a median ignores the single outlier a mean would follow`() {
        assertThat(Trends.median(listOf(50.0, 51.0, 49.0, 200.0, 50.0))).isEqualTo(50.0)
    }

    @Test
    fun `deviation is signed against the baseline`() {
        assertThat(Trends.deviationPercent(55.0, 50.0)).isWithin(0.001).of(10.0)
        assertThat(Trends.deviationPercent(45.0, 50.0)).isWithin(0.001).of(-10.0)
        assertThat(Trends.deviationPercent(45.0, null)).isNull()
        assertThat(Trends.deviationPercent(null, 50.0)).isNull()
    }
}
