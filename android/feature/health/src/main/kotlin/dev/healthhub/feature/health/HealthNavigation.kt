package dev.healthhub.feature.health

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.MenuEntry
import dev.healthhub.core.navigation.NavContribution
import dev.healthhub.core.navigation.Navigator
import dev.healthhub.core.navigation.deepLinkFor
import javax.inject.Inject

/**
 * Sleep, HRV, resting heart rate and readiness (roadmap session 4, step 4).
 *
 * One route for now. The sub-surfaces are added by declaring more destinations in
 * core:navigation and registering them here — no other module learns about them.
 */
class HealthNavContribution @Inject constructor() : NavContribution {

    override val destination: Destination = Destination.Health

    override val menuEntries = listOf(
        MenuEntry("Health", Destination.Health, Icons.Rounded.MonitorHeart, order = 10),
    )

    override fun NavGraphBuilder.register(navigator: Navigator) {
        composable(
            route = Destination.Health.route,
            deepLinks = listOf(navDeepLink { uriPattern = deepLinkFor(Destination.Health) }),
        ) {
            HealthScreen(onBack = navigator::back)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthNavigationModule {

    @Binds
    @IntoSet
    abstract fun bindHealthContribution(contribution: HealthNavContribution): NavContribution
}
