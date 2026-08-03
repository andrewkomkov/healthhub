package dev.healthhub.feature.health

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.navigation.BottomBarEntry
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.NavContribution
import dev.healthhub.core.navigation.Navigator
import dev.healthhub.core.navigation.deepLinkFor
import dev.healthhub.core.ui.HealthHubIcons
import javax.inject.Inject

/**
 * Sleep, HRV, resting heart rate and readiness (roadmap session 4, step 4).
 *
 * One route for now. The sub-surfaces are added by declaring more destinations in
 * core:navigation and registering them here — no other module learns about them.
 */
class HealthNavContribution @Inject constructor() : NavContribution {

    override val destination: Destination = Destination.Health

    /**
     * In the bar rather than in the overflow.
     *
     * Health is half of what this app is — the workouts are the other half — and a menu entry
     * behind three dots said the opposite. It declares no [menuEntries] now, because a
     * destination in both would be offered twice on the same screen.
     */
    override val bottomBarEntry = BottomBarEntry(
        label = R.string.health_tab,
        icon = HealthHubIcons.Health,
        order = 20,
    )

    override fun NavGraphBuilder.register(navigator: Navigator) {
        composable(
            route = Destination.Health.route,
            deepLinks = listOf(navDeepLink { uriPattern = deepLinkFor(Destination.Health) }),
        ) {
            HealthScreen()
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
