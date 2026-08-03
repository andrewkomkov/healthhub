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
import androidx.compose.ui.res.stringResource
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.healthconnect.ExerciseRouteContract
import dev.healthhub.core.model.RoutePoint
import dev.healthhub.core.ui.SectionCard
import dev.healthhub.core.ui.R as CoreR

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
            title = stringResource(R.string.route_thin_title),
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
            title = stringResource(R.string.route_hidden_title),
            body = stringResource(R.string.route_hidden_body),
        )

        RouteImportState.Checking -> Explain(
            title = stringResource(R.string.route_checking_title),
            body = stringResource(R.string.route_checking_body),
        )

        RouteImportState.Absent -> Explain(
            title = stringResource(R.string.route_absent_title),
            body = stringResource(R.string.route_absent_body),
        )

        RouteImportState.NotOnThisPhone -> Explain(
            title = stringResource(R.string.route_elsewhere_title),
            body = stringResource(R.string.route_elsewhere_body),
        )

        RouteImportState.Unsupported -> Explain(
            title = stringResource(R.string.route_unsupported_title),
            body = stringResource(R.string.route_unsupported_body),
        )

        is RouteImportState.Archived -> Explain(
            title = stringResource(R.string.route_archived_title),
            body = stringResource(
                if (state.duplicateOf != null) {
                    R.string.route_archived_duplicate_body
                } else {
                    R.string.route_archived_manual_body
                },
            ),
        )

        is RouteImportState.Offered -> Explain(
            title = stringResource(R.string.route_offered_title),
            body = stringResource(R.string.route_offered_body),
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
                Text(stringResource(R.string.route_import))
            }
        }

        RouteImportState.Importing -> Explain(
            title = stringResource(R.string.route_importing_title),
            body = stringResource(R.string.route_importing_body),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Sized from the spacing scale rather than left at the component default,
                // which is a 48 dp indicator beside a line of body text.
                CircularProgressIndicator(Modifier.size(Spacing.xl))
                Text(stringResource(R.string.route_working), style = MaterialTheme.typography.bodySmall)
            }
        }

        is RouteImportState.Imported -> Explain(title = stringResource(R.string.route_imported_title), body = state.message)

        is RouteImportState.Declined -> Explain(
            title = stringResource(R.string.route_declined_title),
            body = stringResource(R.string.route_declined_body),
        ) {
            Button(onClick = { launcher.launch(state.sessionId) }) { Text(stringResource(CoreR.string.action_try_again)) }
        }

        is RouteImportState.Failed -> Explain(
            title = stringResource(R.string.route_failed_title),
            body = state.message,
        ) {
            val sessionId = state.sessionId
            if (sessionId != null) {
                Button(onClick = { launcher.launch(sessionId) }) { Text(stringResource(CoreR.string.action_try_again)) }
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
    Explain(title = stringResource(R.string.route_imported_title), body = message)
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
