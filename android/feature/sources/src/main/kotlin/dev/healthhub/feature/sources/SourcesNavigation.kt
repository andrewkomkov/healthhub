package dev.healthhub.feature.sources

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.NavContribution
import dev.healthhub.core.navigation.Navigator
import dev.healthhub.core.navigation.deepLinkFor
import javax.inject.Inject

/**
 * Source priority and the archive (roadmap session 2, step 3).
 *
 * Two screens, one contribution: reordering sources is what decides which recording an
 * archived duplicate was demoted in favour of, so they are the same piece of work and share
 * a module.
 *
 * Both are reachable without touching the glass, which Constitution Principle VIII requires:
 *
 * ```
 * adb shell am start -a android.intent.action.VIEW -d "healthhub://sources"
 * adb shell am start -a android.intent.action.VIEW -d "healthhub://archive"
 * ```
 */
class SourcesNavContribution @Inject constructor() : NavContribution {

    override val destination: Destination = Destination.Sources

    override fun NavGraphBuilder.register(navigator: Navigator) {
        composable(
            route = Destination.Sources.route,
            deepLinks = listOf(navDeepLink { uriPattern = deepLinkFor(Destination.Sources) }),
        ) {
            SourcesScreen(
                onOpenArchive = { navigator.navigate(Destination.Archive) },
                onBack = navigator::back,
            )
        }

        composable(
            route = Destination.Archive.route,
            deepLinks = listOf(navDeepLink { uriPattern = deepLinkFor(Destination.Archive) }),
        ) {
            ArchiveScreen(
                // A route is a value owned by core:navigation, so the archive can send the
                // athlete to a workout without depending on the module that draws it.
                onOpenActivity = { id -> navigator.navigate(Destination.ActivityDetail(id)) },
                onBack = navigator::back,
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SourcesNavigationModule {

    @Binds
    @IntoSet
    abstract fun bindSourcesContribution(contribution: SourcesNavContribution): NavContribution
}
