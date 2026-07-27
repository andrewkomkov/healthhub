package dev.healthhub.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
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
}

data class BottomBarEntry(
    val label: String,
    val icon: ImageVector,
    /** Lower sorts earlier. Explicit so bar order does not depend on injection order. */
    val order: Int,
)

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
        )
    }
}

/**
 * Navigation actions, so a feature does not need the NavHostController itself and cannot
 * reach into another feature's graph.
 */
interface Navigator {
    fun navigate(destination: Destination)
    fun navigateAndClearBackStack(destination: Destination)
    fun back()
}

class NavHostNavigator(private val controller: NavHostController) : Navigator {

    override fun navigate(destination: Destination) {
        controller.navigate(destination.route)
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
