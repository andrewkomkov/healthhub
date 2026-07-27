package dev.healthhub.feature.activity

import com.google.common.truth.Truth.assertThat
import dev.healthhub.core.healthconnect.RouteState
import dev.healthhub.core.model.RoutePoint
import org.junit.jupiter.api.Test

/**
 * The distinction session 3 exists to get right.
 *
 * `READ_EXERCISE_ROUTE` is signature-level and can never be granted to this app, so a track is
 * asked for one workout at a time — which makes "the recording app wrote no positions" and "you
 * have not been asked yet" two different answers with two different sentences. Telling an
 * athlete "permission needed" for a workout that has no track sends them into Health Connect's
 * settings hunting for a switch that is not there.
 *
 * The mapping is a pure function precisely so this can be asserted without a device holding real
 * recordings; what a device still has to confirm is that Health Connect reports `NoData` for the
 * workouts that deserve it.
 */
class RouteImportStateTest {

    private val point = RoutePoint(timeMs = 1_000, lat = 52.0, lon = 13.0, altitudeM = null)

    @Test
    fun `a source that wrote no positions is absence, never a permission problem`() {
        assertThat(decideRouteImport("session-1", RouteState.Absent))
            .isEqualTo(RouteImportState.Absent)
    }

    @Test
    fun `a track behind the platform's confirmation is offered, with nothing in hand`() {
        val state = decideRouteImport("session-1", RouteState.ConsentRequired)

        assertThat(state).isEqualTo(RouteImportState.Offered("session-1"))
        assertThat((state as RouteImportState.Offered).points).isNull()
    }

    @Test
    fun `a track already in hand is offered without a second confirmation`() {
        val state = decideRouteImport("session-1", RouteState.Available(listOf(point)))

        assertThat(state).isInstanceOf(RouteImportState.Offered::class.java)
        assertThat((state as RouteImportState.Offered).points).containsExactly(point)
    }

    @Test
    fun `a session that hands over an empty track recorded nothing after all`() {
        assertThat(decideRouteImport("session-1", RouteState.Available(emptyList())))
            .isEqualTo(RouteImportState.Absent)
    }

    @Test
    fun `a workout synced from another phone says so instead of offering an import`() {
        // Nothing to ask Health Connect about: the session is not on this device, so the answer
        // is neither absence nor consent.
        assertThat(decideRouteImport(sessionId = null, route = null))
            .isEqualTo(RouteImportState.NotOnThisPhone)
    }
}
