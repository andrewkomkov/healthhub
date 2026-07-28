package dev.healthhub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.core.util.Consumer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.healthhub.core.designsystem.HealthHubTheme
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.NavContribution
import androidx.compose.runtime.CompositionLocalProvider
import dev.healthhub.core.navigation.LocalNavMenu
import dev.healthhub.core.navigation.NavHostNavigator
import dev.healthhub.core.network.TokenStore
import javax.inject.Inject

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A sign-in that started in the browser lands here before anything is drawn.
        consumeAuthCallback(intent)

        setContent {
            HealthHubTheme {
                AppRoot(
                    contributions = contributions,
                    startDestination = if (tokens.deviceToken() != null) {
                        Destination.Feed
                    } else {
                        Destination.Auth
                    },
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

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            CompositionLocalProvider(LocalNavMenu provides menu) {
                NavHost(
                    navController = controller,
                    startDestination = startDestination.route,
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
