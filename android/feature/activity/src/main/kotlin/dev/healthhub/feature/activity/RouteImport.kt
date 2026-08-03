package dev.healthhub.feature.activity

import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.healthconnect.RouteState
import dev.healthhub.core.model.RoutePoint
import dev.healthhub.core.sync.SyncEngine
import dev.healthhub.core.telemetry.Metrics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the detail screen can say about this workout's GPS track.
 *
 * There are four different reasons a route is not on screen, and the whole design of this file
 * is that they never collapse into one apologetic sentence:
 *
 *  - the recording app stored no positions ([Absent]) — nothing to ask for, no setting to find;
 *  - a track exists and the platform will release it for this one workout ([Offered]);
 *  - the workout is on the server but its session is not on this phone ([NotOnThisPhone]);
 *  - this phone has no Health Connect at all ([Unsupported]).
 *
 * The first two are the pair that matters. `READ_EXERCISE_ROUTE` is signature-level and can
 * never be granted to this app (R-015), so an athlete told "permission needed" for a workout
 * that simply has no track will go looking through Health Connect's settings for a switch that
 * does not exist, and will not find it, because it is not there.
 */
sealed interface RouteImportState {

    /** The route is already drawn, or the screen has not looked yet. */
    data object Hidden : RouteImportState

    data object Checking : RouteImportState

    /**
     * A track exists and can be asked for. [points] is non-null only in the rare case where the
     * session already handed the track over, in which case no confirmation is needed.
     */
    data class Offered(val sessionId: String, val points: List<RoutePoint>? = null) :
        RouteImportState

    /** The source recorded no positions. Data absence, not a permission problem. */
    data object Absent : RouteImportState

    data object NotOnThisPhone : RouteImportState

    data object Unsupported : RouteImportState

    /**
     * Archived recordings are left alone: re-uploading one would put it back in the feed.
     *
     * [duplicateOf] separates the two ways a recording gets here, and they are different
     * sentences: the sync decided this was another app's version of the same workout, or the
     * athlete set it aside by hand. Telling somebody their own decision was a duplicate verdict
     * is the kind of small lie this screen exists not to tell. Null means it was theirs.
     */
    data class Archived(val duplicateOf: String?) : RouteImportState

    data object Importing : RouteImportState

    data class Imported(val message: String) : RouteImportState

    /** The consent screen came back with nothing. Asking again is one tap away. */
    data class Declined(val sessionId: String) : RouteImportState

    data class Failed(val sessionId: String?, val message: String) : RouteImportState
}

/**
 * The per-activity route flow, joining what Health Connect will say to what the ingest path
 * will do.
 *
 * Neither half is implemented here. Availability comes from `core:healthconnect`, and the
 * re-ingest is `SyncEngine.importRoute`, which is the same code path a sync takes — so an
 * imported track produces exactly the workout a sync would have produced if the platform had
 * ever let one through.
 */
@Singleton
class RouteImporter @Inject constructor(
    private val healthConnect: HealthConnectSource,
    private val sync: SyncEngine,
) {

    /**
     * Works out which of the four answers applies, without asking the athlete anything.
     *
     * One Health Connect read, over a window an hour or two wide, and only for a workout whose
     * route is not already on screen.
     */
    suspend fun inspect(activity: ActivityDetailDto): RouteImportState {
        if (activity.visibility != "active") {
            return RouteImportState.Archived(activity.duplicateOf)
        }
        if (healthConnect.availability != HealthConnectSource.Availability.AVAILABLE) {
            return RouteImportState.Unsupported
        }

        // The id the activity was ingested under, when the server knows it. Everything else is a
        // guess: the same walk recorded by two apps produces two sessions with one start instant
        // between them, and picking by start time picks one of the pair arbitrarily. That is not
        // a cosmetic difference — the import re-ingests whichever session it matched and upserts
        // on *that* session's id, so a wrong match silently attaches the track to the duplicate
        // and leaves the activity on screen still asking to import one.
        val session = runCatching {
            activity.sourceUid?.let { healthConnect.readSession(it) }
                // Only for activities uploaded before the server returned the field.
                ?: healthConnect.findSession(
                    startTimeMs = activity.startTime,
                    endTimeMs = activity.endTime.takeIf { it > activity.startTime }
                        ?: (activity.startTime + activity.elapsedSeconds * 1_000),
                    sourcePackage = activity.sourcePackage,
                )
        }.getOrNull() ?: return decideRouteImport(sessionId = null, route = null)

        return decideRouteImport(session.metadata.id, healthConnect.routeState(session))
    }

    /** Re-ingests the workout with the track the athlete just granted. */
    suspend fun import(sessionId: String, points: List<RoutePoint>): RouteImportState {
        if (points.isEmpty()) return RouteImportState.Absent

        return runCatching { sync.importRoute(sessionId, points) }
            .fold(
                onSuccess = { ingested -> outcome(points.size, ingested, sessionId) },
                onFailure = { error ->
                    RouteImportState.Failed(
                        sessionId,
                        error.message ?: "The route could not be imported.",
                    )
                },
            )
    }

    /**
     * What the ingest actually decided, said in words.
     *
     * Written from the verdict rather than from what was hoped for: a track that covers only part
     * of the ride does not become the activity's distance, and saying so is the difference
     * between a number the athlete can trust and one they have to go and check.
     */
    private fun outcome(
        points: Int,
        ingested: SyncEngine.Ingested,
        sessionId: String,
    ): RouteImportState {
        // The points were granted and then landed outside the session's own window, so nothing
        // was drawn. Reporting success here would leave a card saying "route imported" above a
        // screen with no map on it.
        if (ingested.polylinePoints == 0) {
            return RouteImportState.Failed(
                sessionId,
                "Health Connect returned $points positions, but none of them fall inside this " +
                    "workout's start and end, so there is no track to draw.",
            )
        }

        val imported = "Imported $points GPS points."
        val message = when {
            ingested.distanceOrigin != Metrics.DistanceOrigin.GPS ->
                "$imported The route covers less of this workout than its source recorded, so " +
                    "the distance is still the one the source reported."

            ingested.distanceAgreement == Metrics.DistanceAgreement.DISAGREES ->
                "$imported Distance now comes from the track, and it does not match the " +
                    "recorded average speed — treat it as approximate."

            ingested.distanceAgreement == Metrics.DistanceAgreement.UNKNOWN ->
                "$imported Distance, splits and the route now come from the track. This " +
                    "recording has no speed channel, so nothing independent confirmed the total."

            else -> "$imported Distance, splits and the route now come from the track."
        }
        return RouteImportState.Imported(message)
    }
}

/**
 * The four-way answer, as a function of what Health Connect said and nothing else.
 *
 * Pulled out of [RouteImporter.inspect] so the distinction this session exists to get right —
 * "nothing was recorded" against "you have not been asked yet" — can be tested without a device
 * holding real workouts. [sessionId] is null when this phone's Health Connect has never seen the
 * workout, in which case [route] is unknown rather than absent.
 */
internal fun decideRouteImport(sessionId: String?, route: RouteState?): RouteImportState {
    if (sessionId == null) return RouteImportState.NotOnThisPhone

    return when (route) {
        // A session that says it holds a track and then hands over an empty one recorded no
        // positions after all, whatever it claimed.
        is RouteState.Available ->
            if (route.points.isEmpty()) {
                RouteImportState.Absent
            } else {
                RouteImportState.Offered(sessionId, route.points)
            }

        RouteState.ConsentRequired -> RouteImportState.Offered(sessionId)
        RouteState.Absent -> RouteImportState.Absent
        null -> RouteImportState.NotOnThisPhone
    }
}
