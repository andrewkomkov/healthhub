package dev.healthhub.feature.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.healthhub.core.designsystem.GeneratedTokens
import dev.healthhub.core.designsystem.LocalChartChrome
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.designsystem.channelColor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The stacked, aligned, cursor-synced chart stack on the activity detail screen.
 *
 * One panel per channel rather than one chart with several y axes: channels have incomparable
 * ranges — 150 bpm and 4 m/s share no axis worth drawing — and stacking them keeps every series
 * readable at full height while a single x axis and a single cursor make the stack behave as one
 * chart under the finger.
 *
 * Everything is drawn on a Compose canvas from `core:designsystem` primitives. There is no
 * charting library and therefore no library default anywhere in it: ink, gridlines, stroke width
 * and the per-channel colour all come from the generated tokens, which is the file the web
 * client's chart theme is built from too (Constitution Principle III). A channel keeps its slot
 * in the palette, so heart rate is the same colour on the phone and in the browser.
 *
 * Two gestures, because a phone has no hover. A drag scrubs the cursor, which is also what moves
 * the marker on the map. A press held still for the long-press timeout arms a range selection,
 * and dragging from there reports the statistics for that stretch.
 */
internal class ChartPanel(
    /** Channel name; also picks the colour, so a channel keeps its hue across both clients. */
    val key: String,
    val label: String,
    val values: DoubleArray,
    val format: (Double) -> String,
    /** Shown in the header when the cursor is away — usually the activity average. */
    val summary: String?,
)

private val PANEL_HEIGHT = 104.dp
private val AXIS_GUTTER = 44.dp

@Composable
internal fun TelemetryCharts(
    x: DoubleArray,
    xFormat: (Double) -> String,
    panels: List<ChartPanel>,
    cursor: MutableState<Int?>,
    selection: MutableState<IntRange?>,
    modifier: Modifier = Modifier,
) {
    if (panels.isEmpty() || x.size < 2) return

    val chrome = LocalChartChrome.current
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = chrome.inkMuted)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val gutterPx = with(density) { AXIS_GUTTER.toPx() }
        val plotWidthPx = (widthPx - gutterPx).coerceAtLeast(1f)
        val columns = (plotWidthPx / 2f).roundToInt().coerceIn(2, ChartSeries.MAX_COLUMNS)

        // The sample under a horizontal position, shared by every panel so the stack reads as
        // one chart. `x` is monotonic in both axis modes, which is what makes this exact.
        val indexAt: (Float) -> Int = remember(x, gutterPx, plotWidthPx) {
            { position ->
                val fraction = ((position - gutterPx) / plotWidthPx).coerceIn(0f, 1f)
                TelemetryAnalysis.nearestIndex(x, x[0] + (x[x.size - 1] - x[0]) * fraction)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scrubbable(x.size, indexAt, cursor, selection, haptics),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            panels.forEach { panel ->
                ChartPanelView(
                    panel = panel,
                    x = x,
                    columns = columns,
                    gutterPx = gutterPx,
                    plotWidthPx = plotWidthPx,
                    cursor = cursor,
                    selection = selection,
                    measurer = measurer,
                    labelStyle = labelStyle,
                )
            }

            XAxisLabels(x = x, xFormat = xFormat, gutter = AXIS_GUTTER)
        }
    }
}

/**
 * One panel: a direct-labelled header and the plot.
 *
 * The readout lives in the header beside its own colour swatch rather than in a legend, so the
 * colour sits next to the thing it describes instead of in a key elsewhere on the screen.
 */
@Composable
private fun ChartPanelView(
    panel: ChartPanel,
    x: DoubleArray,
    columns: Int,
    gutterPx: Float,
    plotWidthPx: Float,
    cursor: State<Int?>,
    selection: State<IntRange?>,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val chrome = LocalChartChrome.current
    val colour = channelColor(panel.key)
    val selectionColour = MaterialTheme.colorScheme.primary
    val series = remember(panel.values, x, columns) { ChartSeries.build(x, panel.values, columns) }
    val lineWidthPx = with(LocalDensity.current) { GeneratedTokens.lineWidth.toPx() }
    val dotRadiusPx = with(LocalDensity.current) { GeneratedTokens.dataEndRadius.toPx() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(GeneratedTokens.markerMinSize)
                        .clip(CircleShape)
                        .background(colour),
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(panel.label, style = MaterialTheme.typography.labelMedium)
            }
            PanelReadout(panel = panel, cursor = cursor)
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(PANEL_HEIGHT)
                .semantics { contentDescription = "${panel.label} chart" },
        ) {
            drawPanel(
                series = series,
                x = x,
                colour = colour,
                chromeGridline = chrome.gridline,
                chromeAxis = chrome.axis,
                chromeInkMuted = chrome.inkMuted,
                chromeSurface = chrome.surface,
                selectionColour = selectionColour,
                gutterPx = gutterPx,
                plotWidthPx = plotWidthPx,
                cursorIndex = cursor.value,
                selectionRange = selection.value,
                format = panel.format,
                measurer = measurer,
                labelStyle = labelStyle,
                lineWidthPx = lineWidthPx,
                dotRadiusPx = dotRadiusPx,
            )
        }
    }
}

/**
 * Isolated so that moving the cursor recomposes one line of text per panel rather than the whole
 * screen. Everything else the cursor touches is read in the draw phase and only repaints.
 */
@Composable
private fun PanelReadout(panel: ChartPanel, cursor: State<Int?>) {
    val index = cursor.value
    val value = if (index == null) Double.NaN else panel.values.getOrElse(index) { Double.NaN }
    Text(
        text = if (value.isNaN()) panel.summary ?: Format.EM_DASH else panel.format(value),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun XAxisLabels(x: DoubleArray, xFormat: (Double) -> String, gutter: Dp) {
    val chrome = LocalChartChrome.current
    val first = x[0]
    val last = x[x.size - 1]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = gutter),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf(first, (first + last) / 2, last).forEach { value ->
            Text(
                text = xFormat(value),
                style = MaterialTheme.typography.labelSmall,
                color = chrome.inkMuted,
            )
        }
    }
}

/* --------------------------------------------------------------------------- drawing */

/** Where a sample sits along the drawn x axis, 0..1. Correct for time and for distance. */
private fun fractionOf(x: DoubleArray, index: Int): Float {
    val span = x[x.size - 1] - x[0]
    if (span <= 0) return 0f
    val clamped = index.coerceIn(0, x.size - 1)
    return (((x[clamped] - x[0]) / span).coerceIn(0.0, 1.0)).toFloat()
}

@Suppress("LongParameterList", "LongMethod")
private fun DrawScope.drawPanel(
    series: ChartSeries,
    x: DoubleArray,
    colour: Color,
    chromeGridline: Color,
    chromeAxis: Color,
    chromeInkMuted: Color,
    chromeSurface: Color,
    selectionColour: Color,
    gutterPx: Float,
    plotWidthPx: Float,
    cursorIndex: Int?,
    selectionRange: IntRange?,
    format: (Double) -> String,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    lineWidthPx: Float,
    dotRadiusPx: Float,
) {
    if (!series.hasData) return

    val top = lineWidthPx
    val bottom = size.height - lineWidthPx
    val plotHeight = (bottom - top).coerceAtLeast(1f)

    // A flat channel — a turbo trainer holding 200 W — would otherwise divide by zero and be
    // drawn on the top edge; giving it a span puts the line through the middle instead.
    val rawSpan = (series.max - series.min).takeIf { it > 0 } ?: 1.0
    val low = series.min - rawSpan * 0.05
    val high = series.max + rawSpan * 0.05
    val range = high - low

    fun yOf(value: Float): Float = (bottom - ((value - low) / range) * plotHeight).toFloat()
    fun xOf(fraction: Float): Float = gutterPx + fraction * plotWidthPx

    for (step in listOf(0f, 0.5f, 1f)) {
        val y = top + plotHeight * step
        drawLine(
            chromeGridline,
            Offset(gutterPx, y),
            Offset(gutterPx + plotWidthPx, y),
            strokeWidth = 1f,
        )
    }
    drawLine(chromeAxis, Offset(gutterPx, top), Offset(gutterPx, bottom), strokeWidth = 1f)

    if (selectionRange != null) {
        val left = xOf(fractionOf(x, selectionRange.first))
        val right = xOf(fractionOf(x, selectionRange.last))
        drawRect(
            color = selectionColour.copy(alpha = SELECTION_ALPHA),
            topLeft = Offset(left, top),
            size = Size((right - left).coerceAtLeast(1f), plotHeight),
        )
    }

    val path = Path()
    var open = false
    for (column in 0 until series.columns) {
        if (!series.present[column]) {
            // A NaN sample is a gap, never a zero: the path breaks rather than carrying straight
            // across a tunnel from the last good sample.
            open = false
            continue
        }
        val px = xOf(series.at[column])
        val highY = yOf(series.high[column])
        val lowY = yOf(series.low[column])
        if (open) path.lineTo(px, highY) else path.moveTo(px, highY)
        open = true
        path.lineTo(px, lowY)
    }
    drawPath(path, colour, style = Stroke(width = lineWidthPx))

    if (cursorIndex != null) {
        val fraction = fractionOf(x, cursorIndex)
        val px = xOf(fraction)
        drawLine(chromeInkMuted, Offset(px, top), Offset(px, bottom), strokeWidth = 1f)

        val column = (fraction * series.columns).toInt().coerceIn(0, series.columns - 1)
        if (series.present[column]) {
            val centre = Offset(px, yOf((series.high[column] + series.low[column]) / 2f))
            drawCircle(chromeSurface, radius = dotRadiusPx + lineWidthPx, center = centre)
            drawCircle(colour, radius = dotRadiusPx, center = centre)
        }
    }

    val topLabel = measurer.measure(format(high), labelStyle)
    val bottomLabel = measurer.measure(format(low), labelStyle)
    drawText(topLabel, topLeft = Offset(gutterPx - topLabel.size.width - LABEL_GAP_PX, top))
    drawText(
        bottomLabel,
        topLeft = Offset(
            gutterPx - bottomLabel.size.width - LABEL_GAP_PX,
            bottom - bottomLabel.size.height,
        ),
    )
}

private const val SELECTION_ALPHA = 0.16f
private const val LABEL_GAP_PX = 4f

/* -------------------------------------------------------------------------- gestures */

private enum class Gesture { TAP, SCRUB, SCROLL }

/**
 * Scrub with a drag, select a range with a press held still.
 *
 * A phone has no hover, so both readings come out of one finger. Deciding by *time* rather than
 * by a mode switch is what lets the athlete drag the cursor along a climb and then, without
 * leaving the chart, hold to mark where the climb started.
 *
 * A drag that is mostly vertical is the page being scrolled, and it is deliberately left
 * unconsumed: a chart stack that swallows vertical drags traps the athlete on it.
 */
private fun Modifier.scrubbable(
    sampleCount: Int,
    indexAt: (Float) -> Int,
    cursor: MutableState<Int?>,
    selection: MutableState<IntRange?>,
    haptics: HapticFeedback,
): Modifier = pointerInput(sampleCount, indexAt) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)

        val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result = Gesture.TAP
            var settled = false
            while (!settled) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    settled = true
                    continue
                }
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) {
                    result = Gesture.SCROLL
                    settled = true
                } else if (abs(dx) > viewConfiguration.touchSlop) {
                    result = Gesture.SCRUB
                    settled = true
                }
            }
            result
        }

        when (outcome) {
            // Timed out with the finger down and still: the athlete is marking a range.
            null -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val anchor = indexAt(down.position.x)
                cursor.value = anchor
                selection.value = anchor..anchor
                drag(down.id) { change ->
                    val other = indexAt(change.position.x)
                    selection.value = minOf(anchor, other)..maxOf(anchor, other)
                    cursor.value = other
                    change.consume()
                }
            }

            Gesture.SCRUB -> drag(down.id) { change ->
                cursor.value = indexAt(change.position.x)
                change.consume()
            }

            // A tap parks the cursor where it landed, which is the phone's answer to hover.
            Gesture.TAP -> cursor.value = indexAt(down.position.x)

            Gesture.SCROLL -> Unit
        }
    }
}
