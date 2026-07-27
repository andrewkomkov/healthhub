package dev.healthhub.feature.sources

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The archive's promise, in words.
 *
 * The one product rule this feature exists to keep is that nothing is ever deleted, and the
 * only place an athlete can check that is the text on the screen. These assertions are the
 * same ones `web/src/features/archive/labels.test.ts` makes, so the two clients cannot drift
 * into wording that means different things.
 */
class LabelsTest {

    @Test
    fun `a recording no other app saw says nothing at all`() {
        assertThat(Labels.alsoRecordedBy(1)).isNull()
        assertThat(Labels.alsoRecordedBy(0)).isNull()
    }

    @Test
    fun `the count excludes this recording and reads as a sentence`() {
        assertThat(Labels.alsoRecordedBy(2)).isEqualTo("Also recorded by 1 other app")
        assertThat(Labels.alsoRecordedBy(4)).isEqualTo("Also recorded by 3 other apps")
    }

    @Test
    fun `no reason ever suggests a deletion`() {
        val phrasings = listOf(
            Labels.archivedBecause("duplicate"),
            Labels.archivedBecause("manual"),
            Labels.archivedBecause(null),
            Labels.archivedBecause("something the server added later"),
        )
        for (phrase in phrasings) {
            assertThat(phrase.lowercase()).doesNotContain("delete")
            assertThat(phrase.lowercase()).doesNotContain("removed")
            assertThat(phrase.lowercase()).doesNotContain("discard")
        }
    }

    @Test
    fun `an unrecognised reason still says something true`() {
        assertThat(Labels.archivedBecause("hidden-by-a-future-rule")).isEqualTo("Set aside")
    }

    @Test
    fun `the lock note is what promises a restore survives the next sync`() {
        assertThat(Labels.lockNote(true)).isEqualTo("Your decision, kept through every future sync")
        assertThat(Labels.lockNote(false)).isNull()
    }

    @Test
    fun `activity counts are singular when there is one`() {
        assertThat(Labels.activityCount(1)).isEqualTo("1 activity")
        assertThat(Labels.activityCount(0)).isEqualTo("0 activities")
        assertThat(Labels.activityCount(87)).isEqualTo("87 activities")
    }

    @Test
    fun `a package name is read back the way the browser reads it`() {
        assertThat(Labels.sourceLabel("com.wahoofitness.bolt")).isEqualTo("Bolt")
        assertThat(Labels.sourceLabel("com.strava")).isEqualTo("Strava")
        // Trailing segments like `.app` name the platform, not the product.
        assertThat(Labels.sourceLabel("com.garmin.android.apps.connectmobile"))
            .isEqualTo("Connectmobile")
        assertThat(Labels.sourceLabel("com.example.app")).isEqualTo("Example")
        assertThat(Labels.sourceLabel(null)).isEqualTo("Unknown app")
        assertThat(Labels.sourceLabel("com.strava", "Strava Beta")).isEqualTo("Strava Beta")
    }
}
