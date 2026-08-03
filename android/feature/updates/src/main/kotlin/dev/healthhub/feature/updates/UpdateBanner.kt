package dev.healthhub.feature.updates

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.Spacing

/**
 * The one line that tells an athlete a newer version exists.
 *
 * Hosted above the navigation graph rather than on a screen, because the app it is talking
 * about is the whole app, and because the check runs from here: this composable is the only
 * thing guaranteed to be on screen whatever the athlete is looking at.
 *
 * It is a bar with two buttons and no dialog. An update is not urgent enough to interrupt
 * someone opening their ride.
 */
@Composable
fun UpdateBanner(
    onOpenUpdates: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UpdatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The quiet check: at most once every twelve hours, and only while switched on. Guarded
    // inside the repository rather than here, so navigation cannot turn it into a poll.
    LaunchedEffect(Unit) { viewModel.checkIfDue() }

    AnimatedVisibility(visible = state.bannerUpdate != null) {
        val update = state.bannerUpdate
        Card(
            modifier = modifier.fillMaxWidth().padding(Spacing.md),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(
                    start = Spacing.lg,
                    end = Spacing.sm,
                    top = Spacing.sm,
                    bottom = Spacing.sm,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "HealthHub ${update?.version ?: ""} is out",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.updates_you_are_on, state.currentVersion),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = viewModel::dismiss) { Text("Later") }
                TextButton(onClick = onOpenUpdates) { Text("View") }
            }
        }
    }
}
