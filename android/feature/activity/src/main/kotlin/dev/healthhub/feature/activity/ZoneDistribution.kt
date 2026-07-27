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
import androidx.compose.ui.unit.dp
import dev.healthhub.core.designsystem.GeneratedTokens
import dev.healthhub.core.designsystem.LocalIsDark
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.network.ZoneDto
import kotlin.math.roundToInt

/**
 * Time in heart-rate and power zones, read verbatim from what the phone computed.
 *
 * Zones are ordinal — zone 4 is harder than zone 3 — so they are coloured by *intensity* of the
 * channel's own hue rather than from the categorical series palette. Eight unrelated hues here
 * would imply the zones are unrelated categories, which is the opposite of what they are.
 *
 * Both clients now draw the ramp from `chart.sequential` in the token file — `theme.ordinal` in
 * the browser, [GeneratedTokens.sequentialStep] here — so a zone bar is literally the same step
 * on both screens rather than merely the same idea. This used to mix the channel's own hue with
 * the surface, which made zone 3 a different colour in the two clients and quietly broke
 * Constitution Principle III. The ramp is single-hue on purpose: the channel is already named in
 * the heading above the bars, so the colour is free to carry intensity instead of identity.
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
            val dark = LocalIsDark.current

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
                    colour = GeneratedTokens.sequentialStep(position, rows.size, dark),
                )
            }
        }
    }
}

/**
 * Taller than a hairline on purpose. The Expressive shape scale wants a track you can see the
 * radius of; at 12 dp the fully rounded ends read as a chamfer instead of a capsule.
 */
private val ROW_HEIGHT = 16.dp
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
