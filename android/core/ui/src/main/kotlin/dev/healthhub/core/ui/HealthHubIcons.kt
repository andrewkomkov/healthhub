package dev.healthhub.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownhillSkiing
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.IceSkating
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Rowing
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Snowboarding
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import dev.healthhub.core.model.Sport

/**
 * The app's icon vocabulary: one glyph per concept, named after the concept.
 *
 * Screens ask for `HealthHubIcons.Pace`, never for `Icons.Rounded.Speed`. Two reasons, and
 * neither is tidiness. The first is that the same measurement was reaching different screens
 * as different marks — the feed drew a stopwatch for elapsed time where the detail screen drew
 * a clock, and an athlete comparing the two cannot tell whether they are looking at the same
 * quantity. The second is `web/src/core/m3e/Icon.tsx`, which draws these paths by hand because
 * an icon font costs more than the browser's whole bundle: a metric added to one client has to
 * be added to the other or the two stop reading as one product. A named list here is the thing
 * that can be held against that file.
 *
 * The Rounded set throughout — it is the one that matches Expressive's shape scale.
 */
object HealthHubIcons {

    /* -------------------------------------------------------------- what a workout is made of */

    val Distance: ImageVector = Icons.Rounded.Straighten
    val Duration: ImageVector = Icons.Rounded.Timer
    val Pace: ImageVector = Icons.Rounded.Speed
    val HeartRate: ImageVector = Icons.Rounded.Favorite
    val Elevation: ImageVector = Icons.Rounded.Terrain
    val Power: ImageVector = Icons.Rounded.Bolt
    val Cadence: ImageVector = Icons.Rounded.RotateRight
    val Calories: ImageVector = Icons.Rounded.LocalFireDepartment

    /* ------------------------------------------------------------------------- what a card says */

    /** This workout has a track worth looking at. */
    val Route: ImageVector = Icons.Rounded.Map

    /** More than one app recorded this workout. Nothing was deleted; the rest are in the archive. */
    val MultipleSources: ImageVector = Icons.Rounded.Layers

    /* --------------------------------------------------------------------------- where you can go */

    val Activities: ImageVector = Icons.AutoMirrored.Rounded.DirectionsRun
    val Health: ImageVector = Icons.Rounded.MonitorHeart
    val Sleep: ImageVector = Icons.Rounded.Bedtime
    val SyncNow: ImageVector = Icons.Rounded.Sync
    val Settings: ImageVector = Icons.Rounded.Settings
    val Sources: ImageVector = Icons.Rounded.Apps
    val Archive: ImageVector = Icons.Rounded.Inventory2
    val About: ImageVector = Icons.Rounded.Info
    val Updates: ImageVector = Icons.Rounded.SystemUpdate

    /* ------------------------------------------------------------------------ when something is off */

    val Warning: ImageVector = Icons.Rounded.Warning
    val Offline: ImageVector = Icons.Rounded.CloudOff
    val Back: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack
    val Overflow: ImageVector = Icons.Rounded.MoreVert

    /**
     * The mark for a sport, from the slug stored on the activity.
     *
     * Keyed off [Sport] rather than off the raw string so an unlisted slug lands on the same
     * `OTHER` the model already maps it to — a card for a sport nobody has drawn an icon for
     * gets the generic mark rather than no mark and a hole where every other card has one.
     */
    fun forSport(slug: String): ImageVector = when (Sport.fromSlug(slug)) {
        Sport.RUNNING, Sport.TRAIL_RUNNING -> Icons.AutoMirrored.Rounded.DirectionsRun
        Sport.WALKING -> Icons.AutoMirrored.Rounded.DirectionsWalk
        Sport.HIKING -> Icons.Rounded.Hiking
        Sport.CYCLING, Sport.MOUNTAIN_BIKING, Sport.EBIKING ->
            Icons.AutoMirrored.Rounded.DirectionsBike
        Sport.SWIMMING -> Icons.Rounded.Pool
        Sport.ROWING -> Icons.Rounded.Rowing
        Sport.SKIING -> Icons.Rounded.DownhillSkiing
        Sport.SNOWBOARDING -> Icons.Rounded.Snowboarding
        Sport.SKATING -> Icons.Rounded.IceSkating
        Sport.STRENGTH -> Icons.Rounded.FitnessCenter
        Sport.YOGA -> Icons.Rounded.SelfImprovement
        Sport.OTHER -> Icons.Rounded.Bolt
    }
}
