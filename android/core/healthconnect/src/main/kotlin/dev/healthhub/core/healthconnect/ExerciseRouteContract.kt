package dev.healthhub.core.healthconnect

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import dev.healthhub.core.model.RoutePoint

/**
 * What one session has to say about a GPS track.
 *
 * Three answers, and the whole point of the type is that they cannot be collapsed into two.
 * "No route" and "not allowed to read the route" look identical on screen if the code treats
 * them the same, and the athlete then goes looking through Health Connect for a permission
 * that would not have helped — because there was nothing to read (R-015).
 */
sealed interface RouteState {

    /** The track is in hand: either the session carried it, or consent has just been given. */
    data class Available(val points: List<RoutePoint>) : RouteState

    /** A track exists, and the platform will release it only for this one named workout. */
    data object ConsentRequired : RouteState

    /** The recording app wrote no positions. Nothing to ask for, and no permission to chase. */
    data object Absent : RouteState
}

/**
 * The per-activity route request, as a plain contract over [RoutePoint].
 *
 * Wraps `ExerciseRouteRequestContract` so that no screen has to import a Health Connect type to
 * ask for a track. The SDK's contract already chooses between the platform action (API 34 and
 * above) and the installed Health Connect APK, which is a distinction one of the two test
 * devices cares about; building the intent by hand would work on the Pixel and silently fail on
 * the Samsung.
 *
 * A null result means the athlete dismissed the confirmation, or the provider refused. It does
 * **not** mean the workout has no route — [RouteState] is what answers that, and it is known
 * before the dialog is ever shown.
 */
class ExerciseRouteContract : ActivityResultContract<String, List<RoutePoint>?>() {

    private val delegate = ExerciseRouteRequestContract()

    override fun createIntent(context: Context, input: String): Intent =
        delegate.createIntent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): List<RoutePoint>? =
        delegate.parseResult(resultCode, intent)?.route?.map {
            RoutePoint(
                timeMs = it.time.toEpochMilli(),
                lat = it.latitude,
                lon = it.longitude,
                altitudeM = it.altitude?.inMeters,
            )
        }
}
