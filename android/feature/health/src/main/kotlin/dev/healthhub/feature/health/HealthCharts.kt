package dev.healthhub.feature.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.healthhub.core.designsystem.GeneratedTokens
import dev.healthhub.core.designsystem.LocalChartChrome
import dev.healthhub.core.designsystem.LocalIsDark
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.designsystem.channelColor

/**
 * The sleep and recovery charts, drawn on a Compose canvas from the generated tokens.
 *
 * No charting library and therefore no library default: every colour, stroke width and radius
 * comes from `packages/design-tokens/tokens.json`, the same file the web client's chart theme is
 * built from (Principle III). Heart-rate variability is drawn in the `hrv` slot and resting heart
 * rate in the `hr` slot, so a channel keeps its hue across every surface in the product.
 *
 * Sleep stages have no channel slot of their own — the token file assigns slots to *telemetry*
 * channels — so they take fixed indices from the same categorical palette. Fixed, not cycled: a
 * night without REM must not repaint deep sleep.
 */

private val STAGE_SLOTS: Map<String, Int> = mapOf(
    "rem" to 0,
    "awake" to 1,
    "light" to 2,
    "sleeping" to 3,
    "awakeInBed" to 4,
    "outOfBed" to 4,
    "deep" to 6,
    "unknown" to 5,
)

/** Display order, deepest at the bottom of a column, so a stack reads like a hypnogram. */
internal val STAGE_ORDER = listOf("awake", "awakeInBed", "outOfBed", "rem", "light", "sleeping", "deep", "unknown")

internal val STAGE_LABELS: Map<String, String> = mapOf(
    "awake" to "Awake",
    "awakeInBed" to "Awake in bed",
    "outOfBed" to "Out of bed",
    "rem" to "REM",
    "light" to "Light",
    "sleeping" to "Asleep",
    "deep" to "Deep",
    "unknown" to "Unscored",
)

@Composable
internal fun stageColor(stage: String): Color {
    val palette = if (LocalIsDark.current) {
        GeneratedTokens.chartSeriesDark
    } else {
        GeneratedTokens.chartSeriesLight
    }
    val slot = STAGE_SLOTS[stage] ?: palette.lastIndex
    return palette[slot % palette.size]
}

/**
 * One night as a proportional bar.
 *
 * Only the stages the source actually reported are drawn. A null total means "never reported",
 * which is not zero — drawing a hairline for it would claim a measurement that was not made.
 */
@Composable
internal fun SleepStageBar(stages: Map<String, Long?>, modifier: Modifier = Modifier) {
    val present = STAGE_ORDER.mapNotNull { stage ->
        stages[stage]?.takeIf { it > 0 }?.let { stage to it }
    }
    if (present.isEmpty()) return

    val total = present.sumOf { it.second }.toFloat()
    val colors = present.map { stageColor(it.first) }
    val description = present.joinToString(", ") {
        "${STAGE_LABELS[it.first] ?: it.first} ${Format.duration(it.second)}"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(STAGE_BAR_HEIGHT)
            .clip(RoundedCornerShape(GeneratedTokens.shapeScale.getValue("small")))
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(GeneratedTokens.fillGap),
    ) {
        present.forEachIndexed { index, (_, seconds) ->
            Box(
                modifier = Modifier
                    .weight(seconds / total)
                    .height(STAGE_BAR_HEIGHT)
                    .clip(RoundedCornerShape(GeneratedTokens.shapeScale.getValue("extraSmall")))
                    .background(colors[index]),
            )
        }
    }
}

/**
 * The last few weeks of nights, one column each, stacked by stage.
 *
 * Columns are proportional to time in bed rather than normalised, so a short night is visibly
 * short. Normalising them all to full height would draw a four-hour night exactly like an
 * eight-hour one, which is the single most useful thing this chart has to say.
 */
@Composable
internal fun SleepTrendChart(nights: List<SleepDto>, modifier: Modifier = Modifier) {
    if (nights.isEmpty()) return

    // Oldest on the left, which is the direction a trend is read in.
    val ordered = remember(nights) { nights.sortedBy { it.startTime }.takeLast(SLEEP_TREND_NIGHTS) }
    val chrome = LocalChartChrome.current
    val stageColors = STAGE_ORDER.associateWith { stageColor(it) }
    val ceiling = remember(ordered) {
        ordered.maxOf { night ->
            maxOf(night.timeInBedSeconds ?: night.totalSeconds, night.totalSeconds, 1L)
        }.toFloat()
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(SLEEP_TREND_HEIGHT)
            .semantics {
                contentDescription = "Sleep over the last ${ordered.size} nights"
            },
    ) {
        val gap = GeneratedTokens.fillGap.toPx()
        val columnWidth = ((size.width + gap) / ordered.size - gap).coerceAtLeast(1f)
        val radius = CornerRadius(GeneratedTokens.dataEndRadius.toPx() / 2)

        ordered.forEachIndexed { index, night ->
            val x = index * (columnWidth + gap)
            var y = size.height

            // The stages the source reported, deepest first from the bottom.
            val bands = STAGE_ORDER.reversed().mapNotNull { stage ->
                night.stages[stage]?.takeIf { it > 0 }?.let { stage to it }
            }

            if (bands.isEmpty()) {
                // A duration and nothing else is a real recording. Drawn in the muted ink so it
                // is legible as "no stage detail" rather than as a stage nobody can name.
                val height = size.height * (night.totalSeconds / ceiling)
                drawRoundRect(
                    color = chrome.inkMuted.copy(alpha = UNSCORED_ALPHA),
                    topLeft = Offset(x, size.height - height),
                    size = Size(columnWidth, height),
                    cornerRadius = radius,
                )
                return@forEachIndexed
            }

            bands.forEach { (stage, seconds) ->
                val height = size.height * (seconds / ceiling)
                y -= height
                drawRoundRect(
                    color = stageColors[stage] ?: chrome.inkMuted,
                    topLeft = Offset(x, y),
                    size = Size(columnWidth, height),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/**
 * A daily trend with the athlete's own baseline drawn through it.
 *
 * The baseline is the whole point of the chart. An HRV of 46 ms means nothing on its own; 46
 * against a median of 62 is the entire message, so the dashed line is drawn even when it lands
 * outside the visible range of the last two weeks.
 */
@Composable
internal fun TrendChart(
    days: List<DayValue>,
    baseline: Double?,
    color: Color,
    modifier: Modifier = Modifier,
    label: String = "",
) {
    if (days.size < 2) return

    val chrome = LocalChartChrome.current
    val values = days.map { it.value }
    val low = minOf(values.min(), baseline ?: values.min())
    val high = maxOf(values.max(), baseline ?: values.max())
    // A flat series still needs a range, or every point lands on one row.
    val span = (high - low).takeIf { it > 0 } ?: 1.0

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(TREND_HEIGHT)
            .semantics {
                contentDescription = "$label over ${days.size} days"
            },
    ) {
        fun yFor(value: Double) =
            (size.height - ((value - low) / span * size.height)).toFloat()

        baseline?.let {
            // Dashed, at the token stroke width, so the baseline reads as a reference rather
            // than as a second series.
            val dash = GeneratedTokens.lineWidth.toPx() * DASH_RATIO
            drawLine(
                color = chrome.gridline,
                start = Offset(0f, yFor(it)),
                end = Offset(size.width, yFor(it)),
                strokeWidth = GeneratedTokens.lineWidth.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
            )
        }

        val step = size.width / (days.size - 1)
        val path = Path().apply {
            days.forEachIndexed { index, day ->
                val x = index * step
                val y = yFor(day.value)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = GeneratedTokens.lineWidth.toPx()),
        )

        // The most recent reading gets a dot: it is the one the readiness card is talking about.
        drawCircle(
            color = color,
            radius = GeneratedTokens.dataEndRadius.toPx(),
            center = Offset(size.width, yFor(days.last().value)),
        )
    }
}

/** A legend row, so the colours in a stacked bar are nameable rather than decorative. */
@Composable
internal fun StageLegend(stages: Set<String>, modifier: Modifier = Modifier) {
    val present = STAGE_ORDER.filter { it in stages }
    if (present.isEmpty()) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        present.forEach { stage ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(GeneratedTokens.markerMinSize)
                        .clip(RoundedCornerShape(GeneratedTokens.shapeScale.getValue("extraSmall")))
                        .background(stageColor(stage)),
                )
                Text(
                    STAGE_LABELS[stage] ?: stage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Channel colours, taken from the same slots the telemetry charts use. */
@Composable
internal fun hrvColor(): Color = channelColor("hrv")

@Composable
internal fun restingHrColor(): Color = channelColor("hr")

private val STAGE_BAR_HEIGHT = 20.dp
private val SLEEP_TREND_HEIGHT = 120.dp
private val TREND_HEIGHT = 96.dp
private const val SLEEP_TREND_NIGHTS = 21
private const val UNSCORED_ALPHA = 0.35f
private const val DASH_RATIO = 4f

/** A column of stacked bars needs a legend built from what is actually on screen. */
internal fun stagesPresent(nights: List<SleepDto>): Set<String> =
    nights.flatMap { night -> night.stages.filterValues { (it ?: 0) > 0 }.keys }.toSet()

/** Convenience for the "last night" card, which wants a column rather than a row. */
@Composable
internal fun SleepStageBreakdown(night: SleepDto, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SleepStageBar(night.stages)
        StageLegend(night.stages.filterValues { (it ?: 0) > 0 }.keys)
    }
}
