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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.healthhub.core.designsystem.ExpressiveShapes
import dev.healthhub.core.designsystem.GeneratedTokens
import dev.healthhub.core.designsystem.HealthHubType
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
 * ## What makes it Expressive rather than merely Material
 *
 * A line on a bare background is a chart a spreadsheet would draw. The Expressive treatment here
 * is four deliberate choices, each of which is a token rather than a taste:
 *
 *  - **The plot has a container.** Every panel is drawn inside a shaped, tonal bed at the
 *    Expressive `largeIncreased` radius, so the series sits *in* something instead of floating on
 *    the card. This is also what lets the axis labels live inside the plot, which is what stopped
 *    them overhanging the card edge.
 *  - **The series has weight.** A round-capped stroke above a vertical gradient wash of the
 *    channel's own colour. The area is what gives a 36-sample walk a shape to read at a glance;
 *    the stroke on top is still the honest min-max envelope, so a one-second spike survives.
 *  - **The cursor is a component, not a hairline.** A full-height rounded scrubber in the primary
 *    colour with a ringed dot on the series, sized from `markerMinSize` so it stays a touch
 *    target rather than a pixel.
 *  - **The readout is direct-labelled and emphasised.** The value sits in a tonal pill tinted
 *    with its own channel colour, next to the name, in the Expressive emphasised type role —
 *    never in a legend somewhere else on the screen.
 *
 * Two gestures, because a phone has no hover. A drag scrubs the cursor, which is also what moves
 * the marker on the map. A press held still for the long-press timeout arms a range selection,
 * and dragging from there reports the statistics for that stretch.
 *
 * The cursor is deliberately **not** spring-animated. The Expressive motion scheme is for state
 * changes; a spring between the finger and the thing it is dragging reads as lag, not as polish.
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

private val PANEL_HEIGHT = 132.dp

/**
 * Breathing room inside the tonal bed.
 *
 * The series never touches the container's rounded corners, which is what stops a rounded clip
 * from shaving the first and last samples off a chart.
 */
private val PLOT_INSET = Spacing.md

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
        val insetPx = with(density) { PLOT_INSET.toPx() }
        val plotWidthPx = (widthPx - insetPx * 2).coerceAtLeast(1f)
        val columns = (plotWidthPx / 2f).roundToInt().coerceIn(2, ChartSeries.MAX_COLUMNS)

        // The sample under a horizontal position, shared by every panel so the stack reads as
        // one chart. `x` is monotonic in both axis modes, which is what makes this exact. It has
        // to agree with `xOf` in the draw pass constant for constant — the cursor line and the
        // sample it reports are the same thing seen twice.
        val indexAt: (Float) -> Int = remember(x, insetPx, plotWidthPx) {
            { position ->
                val fraction = ((position - insetPx) / plotWidthPx).coerceIn(0f, 1f)
                TelemetryAnalysis.nearestIndex(x, x[0] + (x[x.size - 1] - x[0]) * fraction)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scrubbable(x.size, indexAt, cursor, selection, haptics),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            panels.forEach { panel ->
                ChartPanelView(
                    panel = panel,
                    x = x,
                    columns = columns,
                    insetPx = insetPx,
                    plotWidthPx = plotWidthPx,
                    cursor = cursor,
                    selection = selection,
                    measurer = measurer,
                    labelStyle = labelStyle,
                )
            }

            XAxisLabels(x = x, xFormat = xFormat, inset = PLOT_INSET)
        }
    }
}

/**
 * One panel: a direct-labelled header and the plot in its own tonal container.
 *
 * The readout lives in the header beside its own colour swatch rather than in a legend, so the
 * colour sits next to the thing it describes instead of in a key elsewhere on the screen.
 */
@Composable
private fun ChartPanelView(
    panel: ChartPanel,
    x: DoubleArray,
    columns: Int,
    insetPx: Float,
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
    val density = LocalDensity.current
    val lineWidthPx = with(density) { GeneratedTokens.lineWidth.toPx() } * STROKE_EMPHASIS
    val dotRadiusPx = with(density) { GeneratedTokens.dataEndRadius.toPx() }
    val cursorWidthPx = with(density) { CURSOR_WIDTH.toPx() }
    val labelPadPx = with(density) { Spacing.sm.toPx() }

    // The bed the series is drawn on. Two tonal steps above the card, not one: `SectionCard` is
    // itself `surfaceContainerLow`, so a bed at the same role is a container nobody can see.
    val bed = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.sm),
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
                Text(panel.label, style = MaterialTheme.typography.labelLarge)
            }
            PanelReadout(panel = panel, cursor = cursor, colour = colour)
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(PANEL_HEIGHT)
                .clip(ExpressiveShapes.largeIncreased)
                .background(bed)
                .semantics { contentDescription = "${panel.label} chart" },
        ) {
            drawPanel(
                series = series,
                x = x,
                colour = colour,
                chromeGridline = chrome.gridline,
                chromeInkMuted = chrome.inkMuted,
                bed = bed,
                selectionColour = selectionColour,
                insetPx = insetPx,
                plotWidthPx = plotWidthPx,
                cursorIndex = cursor.value,
                selectionRange = selection.value,
                format = panel.format,
                measurer = measurer,
                labelStyle = labelStyle,
                lineWidthPx = lineWidthPx,
                dotRadiusPx = dotRadiusPx,
                cursorWidthPx = cursorWidthPx,
                labelPadPx = labelPadPx,
            )
        }
    }
}

/**
 * Isolated so that moving the cursor recomposes one line of text per panel rather than the whole
 * screen. Everything else the cursor touches is read in the draw phase and only repaints.
 */
@Composable
private fun PanelReadout(panel: ChartPanel, cursor: State<Int?>, colour: Color) {
    val index = cursor.value
    val value = if (index == null) Double.NaN else panel.values.getOrElse(index) { Double.NaN }
    Text(
        text = if (value.isNaN()) panel.summary ?: Format.EM_DASH else panel.format(value),
        style = HealthHubType.titleMediumEmphasized,
        modifier = Modifier
            .clip(CircleShape)
            // Tinted with the channel's own colour rather than a neutral: the pill is part of
            // the direct labelling, so it carries the same identity the swatch and the line do.
            .background(colour.copy(alpha = READOUT_TINT_ALPHA))
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    )
}

@Composable
private fun XAxisLabels(x: DoubleArray, xFormat: (Double) -> String, inset: Dp) {
    val chrome = LocalChartChrome.current
    val first = x[0]
    val last = x[x.size - 1]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = inset),
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

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
private fun DrawScope.drawPanel(
    series: ChartSeries,
    x: DoubleArray,
    colour: Color,
    chromeGridline: Color,
    chromeInkMuted: Color,
    bed: Color,
    selectionColour: Color,
    insetPx: Float,
    plotWidthPx: Float,
    cursorIndex: Int?,
    selectionRange: IntRange?,
    format: (Double) -> String,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    lineWidthPx: Float,
    dotRadiusPx: Float,
    cursorWidthPx: Float,
    labelPadPx: Float,
) {
    if (!series.hasData) return

    // The label band at the top of the bed is reserved before the series is scaled, so a peak
    // never draws through its own axis label.
    val labelHeight = measurer.measure(format(series.displayMax), labelStyle).size.height.toFloat()
    val top = labelPadPx + labelHeight
    val bottom = size.height - labelPadPx - labelHeight
    val plotHeight = (bottom - top).coerceAtLeast(1f)

    // A flat channel — a turbo trainer holding 200 W — would otherwise divide by zero and be
    // drawn on the top edge; giving it a span puts the line through the middle instead.
    val rawSpan = (series.displayMax - series.displayMin).takeIf { it > 0 } ?: 1.0
    val low = series.displayMin - rawSpan * RANGE_PAD
    val high = series.displayMax + rawSpan * RANGE_PAD
    val range = high - low

    // Clamped, not dropped. A sample outside the trimmed range is drawn on the edge, so it still
    // reads as "off the top of this axis" rather than disappearing.
    fun yOf(value: Float): Float =
        (bottom - ((value - low) / range) * plotHeight).toFloat().coerceIn(top, bottom)

    fun xOf(fraction: Float): Float = insetPx + fraction * plotWidthPx

    for (step in listOf(0f, 0.5f, 1f)) {
        val y = top + plotHeight * step
        drawLine(
            chromeGridline,
            Offset(insetPx, y),
            Offset(insetPx + plotWidthPx, y),
            strokeWidth = 1f,
            cap = StrokeCap.Round,
        )
    }

    if (selectionRange != null) {
        val left = xOf(fractionOf(x, selectionRange.first))
        val right = xOf(fractionOf(x, selectionRange.last))
        drawRoundRect(
            color = selectionColour.copy(alpha = SELECTION_ALPHA),
            topLeft = Offset(left, top),
            size = Size((right - left).coerceAtLeast(1f), plotHeight),
            cornerRadius = CornerRadius(cursorWidthPx),
        )
    }

    // The area first, then the envelope on top of it. Both break at a gap: a NaN sample is a
    // gap, never a zero, and a fill carried straight across a tunnel would invent terrain.
    var runStart = -1
    for (column in 0..series.columns) {
        val present = column < series.columns && series.present[column]
        if (present && runStart < 0) runStart = column
        if (present || runStart < 0) continue

        val fill = Path()
        val firstX = xOf(series.at[runStart])
        fill.moveTo(firstX, bottom)
        for (i in runStart until column) fill.lineTo(xOf(series.at[i]), yOf(series.high[i]))
        fill.lineTo(xOf(series.at[column - 1]), bottom)
        fill.close()
        drawPath(
            fill,
            // Anchored to the top of the *series*, not the top of the bed. A walk whose speed
            // never leaves the bottom third of its own axis would otherwise be washed with the
            // transparent tail of the gradient and show no fill at all.
            Brush.verticalGradient(
                colors = listOf(colour.copy(alpha = FILL_TOP_ALPHA), Color.Transparent),
                startY = yOf(series.displayMax.toFloat()),
                endY = bottom,
            ),
        )
        runStart = -1
    }

    val path = Path()
    var open = false
    for (column in 0 until series.columns) {
        if (!series.present[column]) {
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
    drawPath(
        path,
        colour,
        style = Stroke(width = lineWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    /*
     * A sample with no neighbour gets a marker of its own.
     *
     * Left to the stroke it becomes a round cap the width of the line — three pixels — and a
     * channel that is mostly gaps, which is what an imported GPS track's altitude looks like
     * beside a denser speed channel, renders as scattered specks that read as dirt on the
     * screen rather than as measurements. Drawn at the mark token instead, the same data reads
     * as what it is: points, because points are all the source recorded.
     */
    for (column in 0 until series.columns) {
        if (!series.present[column]) continue
        val before = column > 0 && series.present[column - 1]
        val after = column < series.columns - 1 && series.present[column + 1]
        if (before || after) continue
        val centre = Offset(
            xOf(series.at[column]),
            yOf((series.high[column] + series.low[column]) / 2f),
        )
        drawCircle(colour, radius = dotRadiusPx * ISOLATED_DOT_SCALE, center = centre)
    }

    // Where the series ends, marked. Cheap, and it stops a short track from reading as a stray
    // scratch in the middle of an otherwise empty bed.
    val lastPresent = (series.columns - 1 downTo 0).firstOrNull { series.present[it] }
    if (lastPresent != null) {
        val centre = Offset(
            xOf(series.at[lastPresent]),
            yOf((series.high[lastPresent] + series.low[lastPresent]) / 2f),
        )
        drawCircle(bed, radius = dotRadiusPx + lineWidthPx, center = centre)
        drawCircle(colour, radius = dotRadiusPx * END_DOT_SCALE, center = centre)
    }

    if (cursorIndex != null) {
        val fraction = fractionOf(x, cursorIndex)
        val px = xOf(fraction)
        // A rounded bar rather than a hairline: this is the thing under the athlete's finger,
        // so it is drawn at a size a finger can be believed to be pointing at.
        drawRoundRect(
            color = selectionColour.copy(alpha = CURSOR_ALPHA),
            topLeft = Offset(px - cursorWidthPx / 2f, top),
            size = Size(cursorWidthPx, plotHeight),
            cornerRadius = CornerRadius(cursorWidthPx / 2f),
        )

        val column = (fraction * series.columns).toInt().coerceIn(0, series.columns - 1)
        if (series.present[column]) {
            val centre = Offset(px, yOf((series.high[column] + series.low[column]) / 2f))
            drawCircle(bed, radius = dotRadiusPx + lineWidthPx, center = centre)
            drawCircle(colour, radius = dotRadiusPx, center = centre)
        }
    }

    // Inside the bed, top-left and bottom-left. Outside it, they overhung the card — the gutter
    // they used to sit in was measured in pixels and did not follow the reader's font size.
    axisLabel(measurer, format(series.displayMax), labelStyle, chromeInkMuted)?.let {
        drawText(it, topLeft = Offset(insetPx, labelPadPx))
    }
    axisLabel(measurer, format(series.displayMin), labelStyle, chromeInkMuted)?.let {
        drawText(it, topLeft = Offset(insetPx, size.height - labelPadPx - it.size.height))
    }
}

/**
 * An axis label, or null when there is nothing worth writing.
 *
 * A formatter given a value it cannot express returns an em dash — pace does exactly that for a
 * speed of zero, which is a normal thing for a walk to contain. An em dash floating at the foot
 * of an axis reads as a rendering fault, so it is simply not drawn.
 */
private fun axisLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    colour: Color,
): TextLayoutResult? {
    if (text.isBlank() || text == Format.EM_DASH) return null
    return measurer.measure(text, style.copy(color = colour))
}

/** Headroom above and below the series, as a fraction of its own span. */
private const val RANGE_PAD = 0.08

/** The Expressive line is heavier than the token's base weight; the token is still the source. */
private const val STROKE_EMPHASIS = 1.5f

private const val SELECTION_ALPHA = 0.16f
private const val CURSOR_ALPHA = 0.28f
private const val FILL_TOP_ALPHA = 0.28f
private const val READOUT_TINT_ALPHA = 0.16f
private const val END_DOT_SCALE = 0.7f

/** A lone sample is drawn as a mark, not as the round cap of a line that goes nowhere. */
private const val ISOLATED_DOT_SCALE = 0.8f
private val CURSOR_WIDTH = 4.dp

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
