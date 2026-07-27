package dev.healthhub.feature.sync

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

class SyncNavContribution @Inject constructor() : NavContribution {

    override val destination: Destination = Destination.Sync

    override fun NavGraphBuilder.register(navigator: Navigator) {
        composable(
            route = Destination.Sync.route,
            // Every screen is reachable directly over ADB (Principle VIII):
            //   adb shell am start -a android.intent.action.VIEW -d "healthhub://sync"
            deepLinks = listOf(navDeepLink { uriPattern = deepLinkFor(Destination.Sync) }),
        ) {
            SyncScreen(onBack = navigator::back)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncNavigationModule {

    @Binds
    @IntoSet
    abstract fun bindSyncContribution(contribution: SyncNavContribution): NavContribution
}
