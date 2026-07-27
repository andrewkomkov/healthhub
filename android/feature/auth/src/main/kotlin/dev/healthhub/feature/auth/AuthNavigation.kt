package dev.healthhub.feature.auth

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.deepLinkFor
import dev.healthhub.core.navigation.NavContribution
import dev.healthhub.core.navigation.Navigator
import javax.inject.Inject

/**
 * How this feature attaches itself to the app.
 *
 * :app never names this class — it collects the contribution set and builds the graph from
 * whatever is in it. That is the whole mechanism behind Constitution Principle VII.
 */
class AuthNavContribution @Inject constructor() : NavContribution {

    override val destination: Destination = Destination.Auth

    override fun NavGraphBuilder.register(navigator: Navigator) {
        composable(
            route = Destination.Auth.route,
            deepLinks = listOf(navDeepLink { uriPattern = deepLinkFor(Destination.Auth) }),
        ) {
            AuthScreen(
                onSignedIn = {
                    // Clear the back stack: pressing back from the feed must not return to a
                    // sign-in screen for an account that is already signed in.
                    navigator.navigateAndClearBackStack(Destination.Feed)
                },
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthNavigationModule {

    @Binds
    @IntoSet
    abstract fun bindAuthContribution(contribution: AuthNavContribution): NavContribution
}
