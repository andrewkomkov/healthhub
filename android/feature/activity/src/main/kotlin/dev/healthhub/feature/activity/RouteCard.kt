package dev.healthhub.feature.activity

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.healthconnect.ExerciseRouteContract
import dev.healthhub.core.model.RoutePoint

/**
 * What sits where the map would be, when there is no map.
 *
 * The states below are the point of this screen's route handling. Health Connect will not hand
 * out GPS tracks in bulk — `READ_EXERCISE_ROUTE` is signature-level and owned by Google's own
 * app (R-015) — so a track arrives one workout at a time, through a platform screen that names
 * the workout and asks. That makes "we have not asked yet" and "there is nothing to ask for"
 * two different answers, and an athlete shown the first when the second is true will go looking
 * through settings for a permission that does not exist.
 */
@Composable
internal fun RouteCard(
    state: RouteImportState,
    /**
     * How many positions the telemetry actually carries. Not the same question as whether a
     * track can be *imported*: a recording can hold one fix and no more, which is a track by
     * every check the platform makes and nothing a map can draw.
     */
    fixes: Int,
    onImport: (String, List<RoutePoint>?) -> Unit,
) {
    // The platform's per-activity consent screen. It names the workout, returns the track once,
    // and grants nothing that outlives the call — which is why this is an action on a screen
    // rather than a permission in the manifest. Remembered because the launcher re-registers
    // whenever the contract instance changes, and a re-registration mid-flight loses the result.
    val contract = remember { ExerciseRouteContract() }
    val launcher = rememberLauncherForActivityResult(contract = contract) { points ->
        pendingSession(state)?.let { onImport(it, points) }
    }

    // This composable is only reached when the geometry produced no segment worth drawing, so
    // any position at all means the track is there and unusable — which is a different sentence
    // from every state below. Saying one of those instead sends the athlete into Health Connect
    // looking for a switch that would change nothing.
    if (fixes > 0) {
        Explain(
            title = "Not enough of a track to draw",
            body = if (fixes == 1) {
                "This recording stored a single position and nothing after it, so there is no " +
                    "line to put on a map. Another app's copy of the same workout may have the " +
                    "whole track — the archive is where a copy that lost a duplicate goes."
            } else {
                "This recording's $fixes positions are too far apart to be one track — every " +
                    "leg between them was rejected as an impossible jump."
            },
        )
        return
    }

    when (state) {
        RouteImportState.Hidden -> Explain(
            title = "Route",
            body = "This workout was recorded with GPS. Its track arrives with the telemetry, " +
                "which is still loading.",
        )

        RouteImportState.Checking -> Explain(
            title = "No route yet",
            body = "Looking for a GPS track for this workout…",
        )

        RouteImportState.Absent -> Explain(
            title = "This workout has no GPS track",
            body = "The app that recorded it stored a summary and no positions — an indoor " +
                "session, or a recorder with the GPS off. There is nothing to import, and no " +
                "permission that would change that. Everything the sensors did record is below.",
        )

        RouteImportState.NotOnThisPhone -> Explain(
            title = "No route on this phone",
            body = "This workout was synced from another device, so its Health Connect " +
                "recording is not here to import a track from. Open it on the phone that " +
                "recorded it.",
        )

        RouteImportState.Unsupported -> Explain(
            title = "No route",
            body = "Health Connect is not available on this phone, so there is nowhere to " +
                "import a track from.",
        )

        RouteImportState.Archived -> Explain(
            title = "No route",
            body = "This recording is archived as a duplicate of another app's version of the " +
                "same workout. Restore it first if you want its track — importing one " +
                "re-uploads the workout, and that would quietly put it back in the feed.",
        )

        is RouteImportState.Offered -> Explain(
            title = "This workout has a GPS track",
            body = "Health Connect keeps routes behind a separate confirmation, one workout at " +
                "a time. Importing rebuilds this activity's map, distance and splits from the " +
                "track.",
        ) {
            val ready = state.points
            Button(
                onClick = {
                    // Already in hand on the rare occasion the session carried it: asking again
                    // would show a confirmation for something already granted.
                    if (ready != null) {
                        onImport(state.sessionId, ready)
                    } else {
                        launcher.launch(state.sessionId)
                    }
                },
            ) {
                Text("Import route")
            }
        }

        RouteImportState.Importing -> Explain(
            title = "Importing the route",
            body = "Recomputing the map, distance and splits from the track, then uploading.",
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Sized from the spacing scale rather than left at the component default,
                // which is a 48 dp indicator beside a line of body text.
                CircularProgressIndicator(Modifier.size(Spacing.xl))
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            }
        }

        is RouteImportState.Imported -> Explain(title = "Route imported", body = state.message)

        is RouteImportState.Declined -> Explain(
            title = "The route was not shared",
            body = "Health Connect returned nothing, so the confirmation was dismissed or " +
                "refused. The track is still there; asking again is one tap away.",
        ) {
            Button(onClick = { launcher.launch(state.sessionId) }) { Text("Try again") }
        }

        is RouteImportState.Failed -> Explain(
            title = "The route could not be imported",
            body = state.message,
        ) {
            val sessionId = state.sessionId
            if (sessionId != null) {
                Button(onClick = { launcher.launch(sessionId) }) { Text("Try again") }
            }
        }
    }
}

/**
 * The import's verdict, shown beside the map the import produced.
 *
 * Separate from [RouteCard] because the card is what stands *instead of* a map, and this is the
 * one state that has something to say once there is one.
 */
@Composable
internal fun RouteOutcome(message: String) {
    Explain(title = "Route imported", body = message)
}

/** Which session a returning consent result belongs to, or null if the screen moved on. */
private fun pendingSession(state: RouteImportState): String? = when (state) {
    is RouteImportState.Offered -> state.sessionId
    is RouteImportState.Declined -> state.sessionId
    is RouteImportState.Failed -> state.sessionId
    else -> null
}

@Composable
private fun Explain(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    SectionCard(title = null, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}
