package dev.healthhub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.util.Consumer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.healthhub.core.designsystem.HealthHubNavigationBar
import dev.healthhub.core.designsystem.HealthHubNavigationBarItem
import dev.healthhub.core.designsystem.HealthHubNavigationRail
import dev.healthhub.core.designsystem.HealthHubNavigationRailItem
import dev.healthhub.core.designsystem.HealthHubTheme
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.NavContribution
import androidx.compose.runtime.CompositionLocalProvider
import dev.healthhub.core.navigation.LocalNavMenu
import dev.healthhub.core.navigation.NavHostNavigator
import dev.healthhub.core.network.TokenStore
import dev.healthhub.core.preferences.AppPreferences
import dev.healthhub.core.preferences.ThemeMode
import dev.healthhub.feature.updates.UpdateBanner
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * The only Activity.
 *
 * It contains no feature logic: the graph is assembled from whatever [NavContribution]s are
 * bound into the set, so adding a feature module — a social one, later — wires up its screens
 * without this file changing (Constitution Principle VII).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var contributions: Set<@JvmSuppressWildcards NavContribution>

    @Inject
    lateinit var tokens: TokenStore

    @Inject
    lateinit var preferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A sign-in that started in the browser lands here before anything is drawn.
        consumeAuthCallback(intent)

        setContent {
            // The appearance is the athlete's, read before anything is drawn. Collected rather
            // than read once so flipping the switch on the settings screen repaints the app
            // under the switch, which is the only way to see what the choice actually does.
            val themeMode by preferences.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val dynamicColor by preferences.dynamicColor.collectAsStateWithLifecycle(true)

            HealthHubTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                dynamicColor = dynamicColor,
            ) {
                AppRoot(
                    contributions = contributions,
                    startDestination = if (tokens.deviceToken() != null) {
                        Destination.Feed
                    } else {
                        Destination.Auth
                    },
                    isRegistered = tokens.isRegistered,
                    onAuthCallback = ::consumeAuthCallback,
                )
            }
        }
    }

    /**
     * Completes an Auth0 sign-in.
     *
     * The Worker finishes the flow by redirecting to
     * `healthhub://auth/callback?token=…&device=…`, which is how the device token reaches the
     * app without the browser ever showing it and without this app handling the athlete's
     * password or the client secret.
     */
    private fun consumeAuthCallback(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "healthhub" || data.host != "auth") return false

        val token = data.getQueryParameter("token") ?: return false
        val deviceId = data.getQueryParameter("device") ?: return false
        tokens.saveDeviceToken(token, deviceId)

        // Clear it so a configuration change cannot replay the credential out of the intent.
        intent.data = null
        return true
    }
}

@Composable
private fun AppRoot(
    contributions: Set<NavContribution>,
    startDestination: Destination,
    /** Whether this installation still holds a usable device token. */
    isRegistered: StateFlow<Boolean>,
    onAuthCallback: (Intent?) -> Boolean,
) {
    val controller = rememberNavController()
    val navigator = remember(controller) { NavHostNavigator(controller) }
    val activity = LocalActivity.current

    /*
     * The activity is singleTask, so a deep link arriving while it is already running comes
     * through onNewIntent rather than starting a fresh instance — and NavHost only consumes
     * the intent it was created with. Without this, `am start -d healthhub://sync` on a
     * running app silently does nothing, which breaks both the Auth0 return trip and the
     * ADB navigation surface (Principle VIII).
     */
    DisposableEffect(activity, controller) {
        val listener = Consumer<Intent> { intent ->
            // The auth callback carries a credential rather than a destination, so it is
            // consumed here instead of being routed like an ordinary deep link.
            if (onAuthCallback(intent)) {
                controller.navigate(Destination.Feed.route) {
                    popUpTo(controller.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            } else {
                controller.handleDeepLink(intent)
            }
        }
        (activity as? ComponentActivity)?.addOnNewIntentListener(listener)
        onDispose { (activity as? ComponentActivity)?.removeOnNewIntentListener(listener) }
    }

    // Collected once from the same set the graph is built from, so the menu can only ever offer
    // a screen some module actually registered.
    val menu = remember(contributions) {
        contributions.flatMap { it.menuEntries }.sortedBy { it.order }
    }

    // The bar, from the same set the graph is built from. A feature that wants a place in it
    // says so; nothing in this file names a screen. Before this existed the entry point was
    // declared, documented and read by nobody — the only way to any screen but the feed was an
    // overflow menu behind three dots, which is where an app puts the things it hopes you will
    // not need.
    val bar = remember(contributions) {
        contributions
            .mapNotNull { contribution -> contribution.bottomBarEntry?.let { it to contribution } }
            .sortedBy { (entry, _) -> entry.order }
            .map { (entry, contribution) -> entry to contribution.destination }
    }

    val backStackEntry by controller.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onTopLevel = bar.any { (_, destination) -> destination.route == currentRoute }

    /*
     * No credential, no app.
     *
     * The start destination alone was not enough, and a phone showed why: `healthhub://feed`
     * opens the feed whatever the token store says, so an athlete who is not signed in — or a
     * deep link followed before signing in — landed on a feed reporting "Sign in to continue"
     * with a navigation bar under it and no way to sign in anywhere on the screen.
     *
     * The same gap catches the case that matters more, because it happens to people who did
     * nothing wrong: a device revoked from another client answers 401, `TokenStore` clears the
     * token, and the app would otherwise sit on a screen that can never load again.
     */
    val registered by isRegistered.collectAsStateWithLifecycle()
    LaunchedEffect(registered, currentRoute) {
        if (!registered && currentRoute != null && currentRoute != Destination.Auth.route) {
            navigator.navigateAndClearBackStack(Destination.Auth)
        }
    }

    /*
     * A bar along the bottom, or a rail down the side.
     *
     * Decided from the window's *height*, which is the constraint that actually bites: a
     * landscape phone is 400 dp tall, and a bottom bar plus a floating action button takes a
     * third of it away from a scrolling list. Material answers a compact height with a rail,
     * and the web client has made the same choice at its own breakpoint since it acquired a
     * navigation surface at all — see `AppShell`.
     *
     * Read from the configuration rather than from a window-size-class dependency: this is one
     * threshold, and the artefact that would provide it is not otherwise in the build.
     */
    val compactHeight = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (compactHeight) return@Scaffold
            // Hidden below the top level, where the screen's own back arrow is the way out and
            // a bar offering three sideways moves is noise over content the athlete came for.
            // Animated rather than swapped, so the detail screen does not appear to jump the
            // height of the bar as it opens.
            AnimatedVisibility(
                visible = onTopLevel,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                HealthHubNavigationBar {
                    bar.forEach { (entry, destination) ->
                        HealthHubNavigationBarItem(
                            selected = destination.route == currentRoute,
                            onClick = { navigator.navigateTopLevel(destination) },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            label = { Text(stringResource(entry.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalNavMenu provides menu) {
            Row(modifier = Modifier.padding(padding)) {
                // The rail is inside the content rather than in a Scaffold slot, because
                // `Scaffold` has no side slot — and it sits beside the graph rather than above
                // it so a screen's own app bar stays at the top of its own column.
                AnimatedVisibility(
                    visible = compactHeight && onTopLevel,
                    enter = slideInHorizontally { -it },
                    exit = slideOutHorizontally { -it },
                ) {
                    HealthHubNavigationRail {
                        bar.forEach { (entry, destination) ->
                            HealthHubNavigationRailItem(
                                selected = destination.route == currentRoute,
                                onClick = { navigator.navigateTopLevel(destination) },
                                icon = { Icon(entry.icon, contentDescription = null) },
                                label = { Text(stringResource(entry.label)) },
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                // Above the graph rather than on a screen: the update is about the app, not
                // about whatever the athlete happens to be looking at, and this is also where
                // the quiet twelve-hourly check is started from. It occupies no height until
                // there is something to say.
                UpdateBanner(onOpenUpdates = { navigator.navigate(Destination.Updates) })

                NavHost(
                    navController = controller,
                    startDestination = startDestination.route,
                    modifier = Modifier.weight(1f),
                ) {
                    // Sorted so the graph is built in a deterministic order regardless of how
                    // the injection set happens to be ordered.
                    contributions
                        .sortedBy { it.destination.route }
                        .forEach { contribution ->
                            with(contribution) { register(navigator) }
                        }
                }
                }
            }
        }
    }
}

/**
 * Below this the window is too short for a bottom bar and a floating action button to share it
 * with a list. Material's compact-height class, which a landscape phone falls into.
 */
private const val COMPACT_HEIGHT_DP = 480
