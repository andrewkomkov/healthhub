package dev.healthhub.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.NavContribution
import dev.healthhub.core.navigation.Navigator
import dev.healthhub.core.navigation.deepLinkFor
import javax.inject.Inject

/**
 * The modularity proof (SC-012, tasks T067).
 *
 * This module is deliberately worthless as a feature: one screen, no state, no dependency
 * beyond what every feature module gets. Its whole job is to be the smallest thing that can
 * demonstrate the claim — a capability area attaches to the app by declaring a destination in
 * core:navigation, binding one contribution, and adding one line to :app. No existing feature
 * module is touched. If that ever stops being true, this module is what notices.
 */
class AboutNavContribution @Inject constructor() : NavContribution {

    override val destination: Destination = Destination.About

    override fun NavGraphBuilder.register(navigator: Navigator) {
        composable(
            route = Destination.About.route,
            deepLinks = listOf(navDeepLink { uriPattern = deepLinkFor(Destination.About) }),
        ) {
            AboutScreen(onBack = navigator::back)
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("About HealthHub", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Strava-class analytics over Health Connect. Every metric on this device, " +
                "nothing computed on a server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AboutNavigationModule {

    @Binds
    @IntoSet
    abstract fun bindAboutContribution(contribution: AboutNavContribution): NavContribution
}
