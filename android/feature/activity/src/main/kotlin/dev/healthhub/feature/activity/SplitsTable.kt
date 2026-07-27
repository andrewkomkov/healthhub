package dev.healthhub.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.model.UnitSystem
import dev.healthhub.core.network.SplitDto

/**
 * The splits table, read verbatim from what the phone computed at ingest.
 *
 * Nothing here recalculates a split — not even the "is this one fast" bar, which is scaled from
 * the stored average speeds. One implementation of the metric means one thing to be right or
 * wrong, and it lives in `core:telemetry` (Constitution Principle I).
 */
@Composable
internal fun SplitsTable(
    splits: List<SplitDto>,
    sport: String,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val wanted = if (units == UnitSystem.IMPERIAL) "mi" else "km"
    val rows = splits.filter { it.unit == wanted }
    if (rows.isEmpty()) return

    val fastest = rows.maxOf { it.avgSpeedMps ?: 0.0 }
    val fullSplit = if (wanted == "mi") Format.MILE_M else 1000.0

    SectionCard(title = "Splits", modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            HeaderCell(if (wanted == "mi") "Mile" else "Km", COLUMN_INDEX)
            HeaderCell("Time", COLUMN_TIME)
            HeaderCell(if (Format.usesSpeed(sport)) "Speed" else "Pace", COLUMN_PACE)
            HeaderCell("Elev", COLUMN_ELEVATION)
            HeaderCell("HR", COLUMN_HR)
        }

        rows.forEach { split ->
            // The last split is usually a partial one; saying so beats showing a pace that looks
            // anomalous because it covered 300 m rather than a kilometre.
            val partial = split.distanceM < fullSplit * 0.95
            val share = if (fastest > 0) ((split.avgSpeedMps ?: 0.0) / fastest).toFloat() else 0f

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = "${split.idx + 1}" +
                        if (partial) " · ${Format.distance(split.distanceM, units)}" else ""
                    BodyCell(label, COLUMN_INDEX)
                    BodyCell(Format.duration(split.movingSeconds ?: split.elapsedSeconds), COLUMN_TIME)
                    BodyCell(Format.paceOrSpeed(split.avgSpeedMps, sport, units), COLUMN_PACE)
                    BodyCell(Format.elevation(split.elevationGainM, units), COLUMN_ELEVATION)
                    BodyCell(split.avgHrBpm?.toString() ?: Format.EM_DASH, COLUMN_HR)
                }
                SpeedBar(share)
            }
        }
    }
}

private const val COLUMN_INDEX = 1.1f
private const val COLUMN_TIME = 1f
private const val COLUMN_PACE = 1.4f
private const val COLUMN_ELEVATION = 0.9f
private const val COLUMN_HR = 0.7f

private val BAR_HEIGHT = 4.dp

/** How this split compares to the fastest one, which is the question a splits table answers. */
@Composable
private fun SpeedBar(share: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(RoundedCornerShape(BAR_HEIGHT))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(share.coerceIn(0f, 1f))
                .height(BAR_HEIGHT)
                .clip(RoundedCornerShape(BAR_HEIGHT))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RowScope.BodyCell(text: String, weight: Float) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** The card every section on this screen sits in, so radius and padding have one definition. */
@Composable
internal fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
