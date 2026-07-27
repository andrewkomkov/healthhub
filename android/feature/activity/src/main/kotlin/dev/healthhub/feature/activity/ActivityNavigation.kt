package dev.healthhub.feature.activity

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.NavContribution
import dev.healthhub.core.navigation.Navigator
import javax.inject.Inject

/**
 * Attaches the detail screen to the graph.
 *
 * The deep link is what makes the screen reachable without touching the glass, which
 * Constitution Principle VIII requires of every screen:
 *
 * ```
 * adb shell am start -a android.intent.action.VIEW -d "healthhub://activity/<id>"
 * ```
 */
class ActivityNavContribution @Inject constructor() : NavContribution {

    override val destination: Destination = Destination.ActivityDetail("")

    override fun NavGraphBuilder.register(navigator: Navigator) {
        composable(
            route = Destination.ActivityDetail.PATTERN,
            arguments = listOf(navArgument(Destination.ActivityDetail.ARG) { type = NavType.StringType }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "healthhub://${Destination.ActivityDetail.PATTERN}" },
            ),
        ) {
            // The id reaches the view model through the SavedStateHandle rather than as an
            // argument, so a process death restores the same activity without this file caring.
            ActivityScreen(onBack = navigator::back)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ActivityNavigationModule {

    @Binds
    @IntoSet
    abstract fun bindActivityContribution(contribution: ActivityNavContribution): NavContribution
}
