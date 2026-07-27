package dev.healthhub.feature.feed

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

class FeedNavContribution @Inject constructor() : NavContribution {

    override val destination: Destination = Destination.Feed

    override fun NavGraphBuilder.register(navigator: Navigator) {
        composable(
            route = Destination.Feed.route,
            deepLinks = listOf(navDeepLink { uriPattern = deepLinkFor(Destination.Feed) }),
        ) {
            FeedScreen(
                // Routes are values owned by core:navigation, so this feature can send the
                // athlete to a screen that another module serves without depending on it.
                onOpenActivity = { id -> navigator.navigate(Destination.ActivityDetail(id)) },
                onOpenSync = { navigator.navigate(Destination.Sync) },
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FeedNavigationModule {

    @Binds
    @IntoSet
    abstract fun bindFeedContribution(contribution: FeedNavContribution): NavContribution
}
