package dev.healthhub.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.healthhub.core.designsystem.Spacing

/**
 * The screen a feature shows when it has nothing to show.
 *
 * Every one of these carries a way out. An empty state that only says "no activities" leaves
 * the athlete to guess whether the app is broken, whether they have to grant something, or
 * whether they simply have not been for a run — and the first two are fixable from here.
 * Principle VI, applied to a layout: silence is the failure mode.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // The glyph is decoration: the title says the same thing in words directly below it,
        // and a screen reader should not hear it twice.
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )

        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = Spacing.xs)) {
                Text(actionLabel)
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            TextButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
        }
    }
}

/**
 * The screen a feature shows when it tried and failed.
 *
 * Distinct from [EmptyState] because the two are different sentences and the athlete acts on
 * them differently: one is "you have no workouts yet", the other is "we could not reach the
 * server". Merging them is how an outage comes to look like an empty account.
 */
@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = HealthHubIcons.Warning,
    retryLabel: String = "Try again",
    onRetry: (() -> Unit)? = null,
) {
    EmptyState(
        icon = icon,
        title = title,
        body = message,
        modifier = modifier,
        actionLabel = if (onRetry != null) retryLabel else null,
        onAction = onRetry,
    )
}
