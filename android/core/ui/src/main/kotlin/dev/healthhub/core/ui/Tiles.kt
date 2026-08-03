package dev.healthhub.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.healthhub.core.designsystem.ExpressiveShapes
import dev.healthhub.core.designsystem.HealthHubType
import dev.healthhub.core.designsystem.Spacing

/**
 * One figure, in its own container, under its icon and name.
 *
 * The value carries the Expressive *emphasised* weight and the label does not: the thing that
 * has to be readable at a glance is the number, and the Expressive type scale exists precisely
 * so that prominence is a role rather than a hand-picked size. The icon is what makes the tile
 * findable without reading it at all.
 *
 * A grid of these rather than one card holding ten label-and-value pairs. Ten pairs inside one
 * container is a table, and a table is read by scanning a column — but a wrapping row has no
 * column, so the eye has to walk it item by item. Each figure with an edge to stop at is what
 * lets "Avg HR" be found without reading the nine beside it.
 *
 * The whole tile is one node to a screen reader, so TalkBack says "Distance, 41.20 km" instead
 * of stopping on the label and the figure separately, which is how a stat grid becomes twenty
 * swipes.
 */
@Composable
fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label, $value"
        },
        shape = ExpressiveShapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(STAT_ICON_SIZE),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                value,
                style = HealthHubType.headlineLargeEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A figure with its icon and no container.
 *
 * What a feed card is made of. A row of these inside a chip each would be a wall of outlines,
 * and the outlines carry no information the icon does not already carry — so there are none.
 * The icon is decoration and the [label] is a bare figure, so the pair is announced as one
 * phrase built from [name] rather than as a glyph the reader has to guess at.
 */
@Composable
fun MetricLabel(
    icon: ImageVector,
    value: String,
    name: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$name, $value"
        },
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(METRIC_ICON_SIZE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The heading over a group of things, with room for one action on its right.
 *
 * Screens were each drawing their own title `Text` with their own style, and they had drifted
 * to three: `titleMedium`, `titleLarge` and one emphasised. Section headings are one role.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = HealthHubType.titleMediumEmphasized)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        action?.invoke()
    }
}

/**
 * A titled container. The unit a detail screen is assembled from.
 *
 * `surfaceContainerLow` rather than the default surface, because the charts inside are measured
 * for contrast against exactly this role — see the contrast test's note about the chart chrome
 * being the page colour while the chart is drawn inside a card.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
    /** Overridable because a splits table is forty rows and a summary is three paragraphs. */
    spacing: Dp = Spacing.sm,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ExpressiveShapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            if (title != null) {
                SectionHeader(title = title, subtitle = subtitle, action = action)
            }
            content()
        }
    }
}

/** Sized to the label beside it rather than to the touch grid: nothing here is tappable. */
private val STAT_ICON_SIZE = 15.dp
private val METRIC_ICON_SIZE = 16.dp
