package dev.healthhub.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.designsystem.channelColor
import dev.healthhub.core.network.ZoneDto
import kotlin.math.roundToInt

/**
 * Time in heart-rate and power zones, read verbatim from what the phone computed.
 *
 * Zones are ordinal — zone 4 is harder than zone 3 — so they are coloured by *intensity* of the
 * channel's own hue rather than from the categorical series palette. Eight unrelated hues here
 * would imply the zones are unrelated categories, which is the opposite of what they are.
 *
 * The web client draws this from `chart.sequential` in the token file. The Kotlin generator does
 * not emit that ramp (only the CSS side does), so the ramp here is built by mixing the channel's
 * token colour with the surface — same idea, same source of truth for both endpoints, but not
 * literally the same steps. Emitting `chart.sequential` from `build.mjs` would close that gap.
 */
@Composable
internal fun ZoneDistribution(
    zones: List<ZoneDto>,
    modifier: Modifier = Modifier,
) {
    val kinds = listOf("hr", "power").filter { kind -> zones.any { it.kind == kind } }
    if (kinds.isEmpty()) return

    SectionCard(title = "Zones", modifier = modifier) {
        kinds.forEach { kind ->
            val rows = zones.filter { it.kind == kind }.sortedBy { it.zoneIndex }
            val total = rows.sumOf { it.seconds }
            val longest = rows.maxOf { it.seconds }
            val hue = channelColor(kind)
            val floor = MaterialTheme.colorScheme.surfaceContainerHighest

            Text(
                text = if (kind == "hr") "Heart rate" else "Power",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            rows.forEachIndexed { position, zone ->
                ZoneRow(
                    label = "Z${zone.zoneIndex + 1}",
                    bounds = zone.lowerBound.roundToInt().toString() +
                        (zone.upperBound?.let { "–${it.roundToInt()}" } ?: "+"),
                    share = if (longest > 0) (zone.seconds / longest).toFloat() else 0f,
                    percent = if (total > 0) "${(zone.seconds / total * 100).roundToInt()}%" else Format.EM_DASH,
                    seconds = zone.seconds,
                    colour = ordinal(floor, hue, position, rows.size),
                )
            }
        }
    }
}

/**
 * A step on the ordinal ramp: the further into the zones, the closer to the channel's full
 * colour. Confined to the upper part of the mix so the first zone is still visible on the card.
 */
private fun ordinal(floor: Color, hue: Color, index: Int, count: Int): Color {
    val fraction = if (count <= 1) 1f else index.toFloat() / (count - 1)
    return lerp(floor, hue, ORDINAL_FLOOR + (1f - ORDINAL_FLOOR) * fraction)
}

private const val ORDINAL_FLOOR = 0.25f
private val ROW_HEIGHT = 12.dp
private val LABEL_WIDTH = 32.dp
private val BOUND_WIDTH = 64.dp
private val TIME_WIDTH = 64.dp

@Composable
private fun ZoneRow(
    label: String,
    bounds: String,
    share: Float,
    percent: String,
    seconds: Double,
    colour: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(LABEL_WIDTH))
        Text(
            bounds,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(BOUND_WIDTH),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(ROW_HEIGHT)
                .clip(RoundedCornerShape(ROW_HEIGHT))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .height(ROW_HEIGHT)
                    .clip(RoundedCornerShape(ROW_HEIGHT))
                    .background(colour),
            )
        }
        Text(
            Format.duration(seconds),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(TIME_WIDTH),
        )
        Text(percent, style = MaterialTheme.typography.bodySmall)
    }
}
