package dev.healthhub.feature.updates

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.MenuEntry
import dev.healthhub.core.navigation.NavContribution
import dev.healthhub.core.navigation.Navigator
import dev.healthhub.core.navigation.deepLinkFor
import javax.inject.Inject

class UpdatesNavContribution @Inject constructor() : NavContribution {

    override val destination: Destination = Destination.Updates

    // Last in the menu: it is the thing an athlete looks for once, not every day.
    override val menuEntries = listOf(
        MenuEntry("Updates", Destination.Updates, Icons.Rounded.SystemUpdate, order = 80),
    )

    override fun NavGraphBuilder.register(navigator: Navigator) {
        composable(
            route = Destination.Updates.route,
            // Principle VIII: adb shell am start -a android.intent.action.VIEW \
            //   -d "healthhub://updates"
            deepLinks = listOf(navDeepLink { uriPattern = deepLinkFor(Destination.Updates) }),
        ) {
            UpdatesScreen(onBack = navigator::back)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdatesNavigationModule {

    @Binds
    @IntoSet
    abstract fun bindUpdatesContribution(contribution: UpdatesNavContribution): NavContribution
}
