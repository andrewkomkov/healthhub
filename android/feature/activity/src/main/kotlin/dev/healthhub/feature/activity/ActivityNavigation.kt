package dev.healthhub.feature.activity

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
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.navArgument
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.NavContribution
import dev.healthhub.core.navigation.Navigator
import javax.inject.Inject

/**
 * The detail surface — map, multi-series charts, splits and zones — lands with the next
 * slice. The contribution exists now so the route resolves and the deep link works.
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
        ) { entry ->
            val id = entry.arguments?.getString(Destination.ActivityDetail.ARG).orEmpty()
            ActivityPlaceholder(activityId = id, onBack = navigator::back)
        }
    }
}

@Composable
private fun ActivityPlaceholder(activityId: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Activity detail", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The map, charts and splits for $activityId arrive with the next slice of work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ActivityNavigationModule {

    @Binds
    @IntoSet
    abstract fun bindActivityContribution(contribution: ActivityNavContribution): NavContribution
}
