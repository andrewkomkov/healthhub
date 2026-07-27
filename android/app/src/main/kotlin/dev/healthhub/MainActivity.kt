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

        setContent {
            HealthHubTheme {
                AppRoot(
                    contributions = contributions,
                    startDestination = if (tokens.deviceToken() != null) {
                        Destination.Feed
                    } else {
                        Destination.Auth
                    },
                )
            }
        }
    }
}

@Composable
private fun AppRoot(
    contributions: Set<NavContribution>,
    startDestination: Destination,
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
        val listener = Consumer<Intent> { intent -> controller.handleDeepLink(intent) }
        (activity as? ComponentActivity)?.addOnNewIntentListener(listener)
        onDispose { (activity as? ComponentActivity)?.removeOnNewIntentListener(listener) }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(modifier = Modifier.padding(padding)) {
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
