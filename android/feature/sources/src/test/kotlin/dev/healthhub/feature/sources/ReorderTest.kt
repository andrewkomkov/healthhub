package dev.healthhub.feature.sources

import com.google.common.truth.Truth.assertThat
import dev.healthhub.core.network.SourceDto
import org.junit.jupiter.api.Test

/**
 * The ordering rules, held to the same behaviour as `web/src/features/sources/reorder.test.ts`.
 *
 * Both clients write to the same `PUT /api/sources`, and the array order *is* the priority —
 * an off-by-one here does not throw, it silently promotes the wrong app and shows up months
 * later as a ride with the wrong distance in the feed.
 */
class ReorderTest {

    private val list = listOf("a", "b", "c", "d")

    @Test
    fun `moves an item down and shifts the rest up`() {
        assertThat(list.movedItem(0, 2)).containsExactly("b", "c", "a", "d").inOrder()
    }

    @Test
    fun `moves an item up and shifts the rest down`() {
        assertThat(list.movedItem(3, 1)).containsExactly("a", "d", "b", "c").inOrder()
    }

    @Test
    fun `a move to the same slot changes nothing`() {
        assertThat(list.movedItem(2, 2)).containsExactly("a", "b", "c", "d").inOrder()
    }

    @Test
    fun `a target past the end is clamped rather than dropping the item`() {
        assertThat(list.movedItem(0, 99)).containsExactly("b", "c", "d", "a").inOrder()
        assertThat(list.movedItem(3, -99)).containsExactly("d", "a", "b", "c").inOrder()
    }

    @Test
    fun `an out-of-range source index is a no-op, not a crash`() {
        assertThat(list.movedItem(9, 0)).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(emptyList<String>().movedItem(0, 0)).isEmpty()
    }

    @Test
    fun `orderChanged sees a reorder and ignores an identical list`() {
        assertThat(orderChanged(list, list) { it }).isFalse()
        assertThat(orderChanged(list, list.movedItem(0, 1)) { it }).isTrue()
        assertThat(orderChanged(list, list.dropLast(1)) { it }).isTrue()
    }

    @Test
    fun `stepTo clamps at both ends rather than wrapping`() {
        assertThat(stepTo(0, -1, 4)).isEqualTo(0)
        assertThat(stepTo(3, 1, 4)).isEqualTo(3)
        assertThat(stepTo(1, 1, 4)).isEqualTo(2)
        assertThat(stepTo(0, 0, 0)).isEqualTo(0)
    }

    /**
     * The screen leaves on the back gesture while a write is still inside its debounce, and what
     * decides whether that write is still made is this comparison. Keyed on the package alone it
     * answers "nothing changed" for a source the athlete just switched off.
     */
    @Test
    fun `a switch flipped without a reorder is still a change worth storing`() {
        val stored = listOf(
            SourceDto(packageName = "com.wahoofitness.bolt", enabled = true),
            SourceDto(packageName = "com.strava", enabled = true),
        )
        val toggled = listOf(stored[0], stored[1].copy(enabled = false))

        assertThat(orderChanged(toggled, stored) { it.packageName }).isFalse()
        assertThat(orderChanged(toggled, stored) { it.decisionKey() }).isTrue()
        assertThat(orderChanged(stored, stored) { it.decisionKey() }).isFalse()
    }

    @Test
    fun `a reorder is a change under the same key`() {
        val stored = listOf(
            SourceDto(packageName = "com.wahoofitness.bolt"),
            SourceDto(packageName = "com.strava"),
        )
        assertThat(orderChanged(stored.movedItem(0, 1), stored) { it.decisionKey() }).isTrue()
    }
}
