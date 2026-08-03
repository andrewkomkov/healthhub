package dev.healthhub.feature.settings

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

class SettingsNavContribution @Inject constructor() : NavContribution {

    override val destination: Destination = Destination.Settings

    /**
     * Third in the bar. Not in the overflow menu as well — the menu is drawn on the feed, and
     * a destination offered in both is offered twice on one screen.
     */
    override val bottomBarEntry = BottomBarEntry(
        label = R.string.settings_tab,
        icon = HealthHubIcons.Settings,
        order = 30,
    )

    override fun NavGraphBuilder.register(navigator: Navigator) {
        composable(
            route = Destination.Settings.route,
            deepLinks = listOf(navDeepLink { uriPattern = deepLinkFor(Destination.Settings) }),
        ) {
            SettingsScreen(
                onOpen = navigator::navigate,
                // The whole back stack goes with the account. Popping to sign-in without
                // clearing it would leave the previous athlete's feed one back press away.
                onSignedOut = { navigator.navigateAndClearBackStack(Destination.Auth) },
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsNavigationModule {

    @Binds
    @IntoSet
    abstract fun bindSettingsContribution(contribution: SettingsNavContribution): NavContribution
}
