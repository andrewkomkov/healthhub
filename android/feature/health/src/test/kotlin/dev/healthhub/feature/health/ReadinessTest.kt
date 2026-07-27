package dev.healthhub.feature.health

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Readiness, held to the three claims the card makes.
 *
 * The one that matters most is the last: with no baseline the score is *absent*, not average.
 * A number that looks like an answer when there is no evidence behind it is the failure this
 * whole surface would be judged on, and it is invisible in a screenshot.
 */
class ReadinessTest {

    private val night = 8L * 3600

    @Test
    fun `a day at the athlete's own normal lands mid-scale`() {
        val score = Readiness.of(
            Readiness.Input(
                hrvToday = 60.0,
                hrvBaseline = 60.0,
                restingHrToday = 50.0,
                restingHrBaseline = 50.0,
                sleptSeconds = night,
            ),
        )

        // Not 100: matching your median is an ordinary day, and a scale whose midpoint is
        // unreachable is one nobody reads twice.
        assertThat(score.value).isNotNull()
        assertThat(score.value!!).isIn(55..85)
        assertThat(score.components).hasSize(3)
    }

    @Test
    fun `suppressed variability and an elevated pulse score low`() {
        val score = Readiness.of(
            Readiness.Input(
                hrvToday = 40.0,
                hrvBaseline = 60.0,
                restingHrToday = 56.0,
                restingHrBaseline = 50.0,
                sleptSeconds = 5L * 3600,
            ),
        )

        assertThat(score.value!!).isLessThan(30)
    }

    @Test
    fun `a strong morning after a full night scores high`() {
        val score = Readiness.of(
            Readiness.Input(
                hrvToday = 72.0,
                hrvBaseline = 60.0,
                restingHrToday = 46.0,
                restingHrBaseline = 50.0,
                sleptSeconds = 9L * 3600,
            ),
        )

        assertThat(score.value!!).isGreaterThan(90)
    }

    @Test
    fun `weights over what is present rather than penalising what is absent`() {
        val sleepOnly = Readiness.of(Readiness.Input(sleptSeconds = night))

        assertThat(sleepOnly.value).isEqualTo(100)
        assertThat(sleepOnly.components.map { it.label }).containsExactly("Sleep")
        // And it says which baselines it does not have, rather than implying it used them.
        assertThat(sleepOnly.note).contains("heart-rate variability")
    }

    @Test
    fun `no baseline means no score`() {
        val nothing = Readiness.of(Readiness.Input())

        assertThat(nothing.value).isNull()
        assertThat(nothing.components).isEmpty()
        assertThat(nothing.note).contains("week")
    }

    @Test
    fun `a baseline of zero is not a baseline`() {
        val score = Readiness.of(Readiness.Input(hrvToday = 60.0, hrvBaseline = 0.0))

        assertThat(score.value).isNull()
    }
}
