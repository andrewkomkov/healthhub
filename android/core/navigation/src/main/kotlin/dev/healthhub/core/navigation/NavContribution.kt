package dev.healthhub.core.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/**
 * How a feature module attaches itself to the app.
 *
 * This is the mechanism behind Constitution Principle VII. A feature module binds a
 * [NavContribution] into a multibinding set; the app collects the set and builds the graph
 * from it. Adding a social module later means creating the module and binding one more
 * contribution — no existing file is edited, and nothing in :app names the new feature.
 *
 * Features never reference one another's routes directly. They navigate through [Destination]
 * values that live here, in core, so a route can exist before the module that serves it does.
 */
interface NavContribution {

    /** Where this contribution's screens live in the graph. */
    val destination: Destination

    /** Registers the screens. Called once while the graph is being built. */
    fun NavGraphBuilder.register(navigator: Navigator)

    /** Present when the feature belongs in the bottom navigation bar. */
    val bottomBarEntry: BottomBarEntry?
        get() = null

    /**
     * Where this feature appears in the app's menu, if it does.
     *
     * A list rather than one entry because a module can serve more than one screen worth
     * reaching — `feature:sources` serves both the source order and the archive.
     *
     * This is the second registry the architecture wanted. Before it, the menu was a list of
     * `Destination` values written out by hand in `feature:feed`, and it named a route no module
     * serves: `Destination.Settings` exists in this file, `feature:settings` is an empty module,
     * and tapping Settings threw `IllegalArgumentException: Navigation destination that matches
     * route settings cannot be found`. A menu built from the contributions cannot say that,
     * because only a module that registered a screen can offer a way into it.
     */
    val menuEntries: List<MenuEntry>
        get() = emptyList()
}

data class BottomBarEntry(
    /**
     * A resource id, not a string.
     *
     * `:app` is what draws the bar, and a module that handed it a `String` would have had to
     * translate that string itself — at a point where it has no `Context` and no business
     * knowing what language the phone is in. The id travels; the lookup happens where the
     * composition is. Same reason `MenuEntry` and `ActivityAction` carry ids.
     */
    @StringRes val label: Int,
    val icon: ImageVector,
    /** Lower sorts earlier. Explicit so bar order does not depend on injection order. */
    val order: Int,
)

data class MenuEntry(
    @StringRes val label: Int,
    /** Named separately from the contribution's own destination: one module can offer several. */
    val destination: Destination,
    val icon: ImageVector,
    /** Lower sorts earlier. Explicit so menu order does not depend on injection order. */
    val order: Int,
)

/**
 * The menu, collected by `:app` from every contribution and read by whichever screen draws it.
 *
 * A composition local rather than a parameter threaded through the graph: the feed is built by
 * its own module, and passing this down would mean every screen between here and there knowing
 * about a menu it does not draw.
 */
val LocalNavMenu = staticCompositionLocalOf { emptyList<MenuEntry>() }

/**
 * The app's routes.
 *
 * A sealed hierarchy rather than free-form strings: the compiler then catches a typo in a
 * route, and the ADB deep-link router (Principle VIII) can enumerate every screen it must
 * be able to open.
 */
sealed interface Destination {
    val route: String

    data object Auth : Destination {
        override val route = "auth"
    }

    data object Feed : Destination {
        override val route = "feed"
    }

    data class ActivityDetail(val activityId: String) : Destination {
        override val route = "activity/$activityId"

        companion object {
            const val PATTERN = "activity/{activityId}"
            const val ARG = "activityId"
        }
    }

    data object Sync : Destination {
        override val route = "sync"
    }

    data object Settings : Destination {
        override val route = "settings"
    }

    /** The recordings an automatic decision demoted. Nothing here was deleted. */
    data object Archive : Destination {
        override val route = "archive"
    }

    data object Sources : Destination {
        override val route = "sources"
    }

    data object Health : Destination {
        override val route = "health"
    }

    data object About : Destination {
        override val route = "about"
    }

    /** What version is installed, what version exists, and the way from one to the other. */
    data object Updates : Destination {
        override val route = "updates"
    }

    companion object {
        /** Every route the deep-link router must handle. Kept beside the definitions so a
         *  new screen cannot be added without this list noticing. */
        val allPatterns: List<String> = listOf(
            Auth.route,
            Feed.route,
            ActivityDetail.PATTERN,
            Sync.route,
            Settings.route,
            Archive.route,
            Sources.route,
            Health.route,
            About.route,
            Updates.route,
        )
    }
}

/**
 * Navigation actions, so a feature does not need the NavHostController itself and cannot
 * reach into another feature's graph.
 */
interface Navigator {
    fun navigate(destination: Destination)

    /**
     * Switch to one of the bar's destinations.
     *
     * Different from [navigate] in three ways that together are what makes a navigation bar feel
     * like tabs rather than like a stack of screens: the back stack is popped to the graph's
     * start rather than grown, so tapping four tabs does not cost four presses of back; each
     * tab's own state is saved and restored, so returning to the feed returns to the scroll
     * position it was left at; and the same tab tapped twice does not push a second copy.
     */
    fun navigateTopLevel(destination: Destination)

    fun navigateAndClearBackStack(destination: Destination)
    fun back()
}

class NavHostNavigator(private val controller: NavHostController) : Navigator {

    override fun navigate(destination: Destination) {
        controller.navigate(destination.route)
    }

    override fun navigateTopLevel(destination: Destination) {
        controller.navigate(destination.route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    override fun navigateAndClearBackStack(destination: Destination) {
        controller.navigate(destination.route) {
            popUpTo(controller.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    override fun back() {
        controller.popBackStack()
    }
}

/** The deep-link scheme the ADB control surface drives: `healthhub://feed`, etc. */
const val DEEP_LINK_SCHEME = "healthhub"

fun deepLinkFor(destination: Destination): String = "$DEEP_LINK_SCHEME://${destination.route}"

@Composable
fun NoOp() {
    // Placeholder for previews that need a composable slot.
}
