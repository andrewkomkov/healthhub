package dev.healthhub.core.ui

import com.google.common.truth.Truth.assertThat
import dev.healthhub.core.model.UnitSystem
import org.junit.jupiter.api.Test

/**
 * The rules the four copies of this file used to hold apart.
 *
 * These are pinned against `web/src/core/format.ts`, not against what the Kotlin happens to
 * produce: SC-008 is checked by a person reading the phone and the browser side by side, so a
 * change here that the web client does not make is a defect even when both are self-consistent.
 */
class FormatTest {

    @Test
    fun `swimming reads as speed, which one copy of this rule had forgotten`() {
        // The defect this module was created to make unrepresentable: FeedScreen's sport set
        // omitted swimming, so a swim read as a pace in the feed and as a speed on the screen
        // it opened — the same recording, two figures, one tap apart.
        assertThat(Format.usesSpeed("swimming")).isTrue()
        assertThat(Format.usesSpeed("cycling")).isTrue()
        assertThat(Format.usesSpeed("running")).isFalse()
        assertThat(Format.usesSpeed("walking")).isFalse()
    }

    @Test
    fun `a pace of 59 point 6 seconds carries instead of printing 7 colon 60`() {
        // 1000 m at this speed is 419.6 s per km.
        val mps = 1000.0 / 419.6
        assertThat(Format.paceOrSpeed(mps, "running", UnitSystem.METRIC)).isEqualTo("7:00 /km")
    }

    @Test
    fun `pace and speed round the way the web client rounds`() {
        assertThat(Format.paceOrSpeed(3.0, "running", UnitSystem.METRIC)).isEqualTo("5:33 /km")
        assertThat(Format.paceOrSpeed(5.0, "cycling", UnitSystem.METRIC)).isEqualTo("18.0 km/h")
        assertThat(Format.paceOrSpeed(3.0, "running", UnitSystem.IMPERIAL)).isEqualTo("8:56 /mi")
        assertThat(Format.paceOrSpeed(5.0, "cycling", UnitSystem.IMPERIAL)).isEqualTo("11.2 mph")
    }

    @Test
    fun `an unmeasurable figure is a dash, never a zero`() {
        assertThat(Format.paceOrSpeed(null, "running", UnitSystem.METRIC)).isEqualTo(Format.EM_DASH)
        assertThat(Format.paceOrSpeed(0.0, "running", UnitSystem.METRIC)).isEqualTo(Format.EM_DASH)
        assertThat(Format.distance(null, UnitSystem.METRIC)).isEqualTo(Format.EM_DASH)
        assertThat(Format.duration(null as Long?)).isEqualTo(Format.EM_DASH)
    }

    @Test
    fun `a duration under an hour drops the hour field`() {
        assertThat(Format.duration(2756L)).isEqualTo("45:56")
        assertThat(Format.duration(3661L)).isEqualTo("1:01:01")
    }

    @Test
    fun `a night is hours and minutes, not a stopwatch`() {
        assertThat(Format.hoursAndMinutes(27_720)).isEqualTo("7h 42m")
        assertThat(Format.hoursAndMinutes(2_520)).isEqualTo("42m")
    }

    @Test
    fun `distance and elevation convert on the athlete's unit system`() {
        assertThat(Format.distance(41_200.0, UnitSystem.METRIC)).isEqualTo("41.20 km")
        assertThat(Format.distance(41_200.0, UnitSystem.IMPERIAL)).isEqualTo("25.60 mi")
        assertThat(Format.elevation(412.0, UnitSystem.METRIC)).isEqualTo("412 m")
        assertThat(Format.elevation(412.0, UnitSystem.IMPERIAL)).isEqualTo("1352 ft")
    }

    @Test
    fun `a title that is only the sport does not say it twice`() {
        // Health Connect writes no title, so the sport is stored as one and a naive join
        // produced "Walking · Walking" on every card in the feed.
        assertThat(Format.sportAndTitle("walking", "Walking")).isEqualTo("Walking")
        assertThat(Format.sportAndTitle("walking", "walking")).isEqualTo("Walking")
        assertThat(Format.sportAndTitle("walking", "")).isEqualTo("Walking")
        assertThat(Format.sportAndTitle("trail_running", "Dawn loop"))
            .isEqualTo("Trail running · Dawn loop")
    }

    @Test
    fun `a workout is rendered in the timezone it was recorded in`() {
        // 2026-03-14T09:30Z, recorded at +02:00, is half past eleven to the person who ran it,
        // whatever the phone reading it now thinks the time is.
        assertThat(Format.timeOfDay(1_773_480_600_000L, tzOffsetMinutes = 120)).isEqualTo("11:30")
        assertThat(Format.timeOfDay(1_773_480_600_000L, tzOffsetMinutes = 0)).isEqualTo("09:30")
    }
}
