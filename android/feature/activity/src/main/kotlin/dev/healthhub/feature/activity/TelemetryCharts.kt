package dev.healthhub.feature.activity

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
 * The cursor-scrubbed chart on the activity detail screen.
 *
 * **One channel at a time, chosen with a chip.** This used to stack every channel the recording
 * had — five panels, one above the other, sharing a cursor. Each was then a sixth of the screen
 * tall, which is not enough height to read a shape out of, and reaching the power chart meant
 * scrolling past four others. A phone shows one chart properly or five badly. The chips are the
 * switch, they carry the channel's own colour, and the channel under the finger is named in full
 * above the plot rather than in a legend beside it.
 *
 * Everything is drawn on a Compose canvas from `core:designsystem` primitives. There is no
 * charting library and therefore no library default anywhere in it: ink, gridlines, stroke width
 * and the per-channel colour all come from the generated tokens, which is the file the web
 * client's chart theme is built from too (Constitution Principle III). A channel keeps its slot
 * in the palette, so heart rate is the same colour on the phone and in the browser.
 *
 * ## What makes it Expressive rather than merely Material
 *
 * A line on a bare background is a chart a spreadsheet would draw. The treatment here is five
 * deliberate choices, each of which is a token rather than a taste:
 *
 *  - **The value is the headline.** The number under the finger is set in the Expressive
 *    emphasised display role, in the channel's own colour, above the plot — not floating on the
 *    curve, where a tall series simply covers it. Under it, in one quiet line, *where* on the
 *    ride that reading is; with no finger down, the activity average instead.
 *  - **The plot has a container.** The canvas is a shaped tonal bed at the Expressive
 *    `largeIncreased` radius, so the series sits *in* something rather than floating on the card.
 *  - **The series has weight.** A round-capped stroke over a vertical gradient wash of the
 *    channel's own colour, the mean of each bucket rather than its min-max envelope — see
 *    [ChartSeries], where the reversal is argued.
 *  - **The cursor is a component, not a hairline.** A full-height rounded scrubber with a ringed
 *    dot on the series, sized from `markerMinSize` so it stays a touch target rather than a pixel.
 *  - **Switching channels is animated.** The new curve grows from the baseline over one spatial
 *    motion step; swapped instantly, the eye has nothing to follow and reads it as a glitch.
 *
 * Two gestures, because a phone has no hover. A drag scrubs the cursor — which is also what moves
 * the marker on the map — with a haptic tick every fortieth of the width, so the ride can be felt
 * as well as seen. A press held still for the long-press timeout arms a range selection, and
 * dragging from there reports the statistics for that stretch.
 */
internal class ChartPanel(
    /** Channel name; also picks the colour, so a channel keeps its hue across both clients. */
    val key: String,
    val label: String,
    val values: DoubleArray,
    val format: (Double) -> String,
    /** Shown when the cursor is away — usually the activity average. */
    val summary: String?,
    /**
     * A value the axis may not drop below, whatever the channel did. Speed passes the moving
     * threshold: see [ChartSeries.axisFloor], which is where the reasoning lives.
     */
    val axisFloor: Double = Double.NEGATIVE_INFINITY,
)

private val PLOT_HEIGHT = 200.dp

/**
 * Breathing room inside the tonal bed.
 *
 * The series never touches the container's rounded corners, which is what stops a rounded clip
 * from shaving the first and last samples off a chart.
 */
private val PLOT_INSET = Spacing.md

@OptIn(ExperimentalLayoutApi::class)
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

    // Saved as a key rather than an index: a preview object and the full one can offer different
    // channels, and an index would silently point at a different chart when the swap lands.
    var selectedKey by rememberSaveable(panels.map { it.key }) { mutableStateOf(panels[0].key) }
    val panel = panels.firstOrNull { it.key == selectedKey } ?: panels[0]

    Column(modifier = modifier.fillMaxWidth()) {
        if (panels.size > 1) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                panels.forEach { entry ->
                    ChannelChip(
                        panel = entry,
                        selected = entry.key == panel.key,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedKey = entry.key
                        },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }

        ChartPlot(
            panel = panel,
            x = x,
            xFormat = xFormat,
            cursor = cursor,
            selection = selection,
            measurer = measurer,
            labelStyle = labelStyle,
            density = density,
            haptics = haptics,
        )
    }
}

@Composable
private fun ChannelChip(panel: ChartPanel, selected: Boolean, onClick: () -> Unit) {
    val colour = channelColor(panel.key)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(panel.label, maxLines = 1) },
        // The channel's own colour, not a generic icon: the swatch is the same identity the line,
        // the readout and the web client's legend all carry, so the chip needs no other mark.
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(GeneratedTokens.markerMinSize)
                    .clip(CircleShape)
                    .background(colour),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = colour.copy(alpha = SELECTED_CHIP_ALPHA),
        ),
    )
}

/**
 * The readout, the plot and the x axis: everything that changes when the chip changes.
 *
 * Split out so that switching channels rebuilds this and not the chip row, and so that the
 * reduction is remembered per channel rather than recomputed on every recomposition of the screen.
 */
@Composable
private fun ChartPlot(
    panel: ChartPanel,
    x: DoubleArray,
    xFormat: (Double) -> String,
    cursor: MutableState<Int?>,
    selection: MutableState<IntRange?>,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    density: Density,
    haptics: HapticFeedback,
) {
    val chrome = LocalChartChrome.current
    val colour = channelColor(panel.key)
    val selectionColour = MaterialTheme.colorScheme.primary
    val series = remember(panel.values, x) {
        ChartSeries.build(x, panel.values, ChartSeries.BUCKETS, panel.axisFloor)
    }
    val lineWidthPx = with(density) { GeneratedTokens.lineWidth.toPx() } * STROKE_EMPHASIS
    val dotRadiusPx = with(density) { GeneratedTokens.dataEndRadius.toPx() }
    val cursorWidthPx = with(density) { CURSOR_WIDTH.toPx() }
    val labelPadPx = with(density) { Spacing.sm.toPx() }
    val insetPx = with(density) { PLOT_INSET.toPx() }

    // The bed the series is drawn on. Two tonal steps above the card, not one: `SectionCard` is
    // itself `surfaceContainerLow`, so a bed at the same role is a container nobody can see.
    val bed = MaterialTheme.colorScheme.surfaceContainerHighest

    // Grown from the baseline on every change of channel. Snapped to zero first, so the switch
    // is a movement the eye can follow rather than a substitution it can only notice afterwards.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(panel.key, series) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(REVEAL_MS))
    }

    val index = cursor.value
    val at = if (index == null) Double.NaN else panel.values.getOrElse(index) { Double.NaN }
    Text(
        text = if (at.isNaN()) panel.summary ?: Format.EM_DASH else panel.format(at),
        style = HealthHubType.headlineLargeEmphasized,
        color = colour,
    )
    Text(
        text = when {
            index == null -> if (panel.summary == null) panel.label else "${panel.label}, average"
            else -> "${panel.label} at ${xFormat(x[index.coerceIn(x.indices)])}"
        },
        style = MaterialTheme.typography.labelMedium,
        color = chrome.inkMuted,
    )

    Spacer(Modifier.height(Spacing.sm))

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(PLOT_HEIGHT)
            .clip(ExpressiveShapes.largeIncreased)
            .background(bed)
            .scrubbable(x, insetPx, cursor, selection, haptics)
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
            plotWidthPx = (size.width - insetPx * 2).coerceAtLeast(1f),
            cursorIndex = cursor.value,
            selectionRange = selection.value,
            format = panel.format,
            measurer = measurer,
            labelStyle = labelStyle,
            lineWidthPx = lineWidthPx,
            dotRadiusPx = dotRadiusPx,
            cursorWidthPx = cursorWidthPx,
            labelPadPx = labelPadPx,
            reveal = reveal.value,
        )
    }

    Spacer(Modifier.height(Spacing.sm))
    XAxisLabels(x = x, xFormat = xFormat, inset = PLOT_INSET)
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
    reveal: Float,
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
    // The headroom is not allowed to reopen what the floor closed: padding under a pace axis
    // floored at walking speed puts the bottom of the panel back at half an hour per kilometre.
    val low = (series.displayMin - rawSpan * RANGE_PAD).coerceAtLeast(series.axisFloor)
    val high = series.displayMax + rawSpan * RANGE_PAD
    val range = high - low

    // Clamped, not dropped. A point outside the trimmed range is drawn on the edge, so it still
    // reads as "off the top of this axis" rather than disappearing.
    fun yOf(value: Float): Float {
        val full = (bottom - ((value - low) / range) * plotHeight).toFloat().coerceIn(top, bottom)
        return bottom - (bottom - full) * reveal
    }

    fun xOf(fraction: Float): Float = insetPx + fraction * plotWidthPx

    // Dashed rather than solid, and three rather than the full grid: the reading is the shape of
    // the curve, and a solid grid competes with it for the same ink.
    val dash = PathEffect.dashPathEffect(floatArrayOf(GRID_DASH_ON, GRID_DASH_OFF))
    for (step in listOf(0f, 0.5f, 1f)) {
        val y = top + plotHeight * step
        drawLine(
            chromeGridline,
            Offset(insetPx, y),
            Offset(insetPx + plotWidthPx, y),
            strokeWidth = 1f,
            cap = StrokeCap.Round,
            pathEffect = if (step == 1f) null else dash,
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

    // The area first, then the line on top of it. Both break at a gap: a bucket with no sample is
    // a gap, never a zero, and a fill carried straight across a tunnel would invent terrain.
    var runStart = -1
    for (column in 0..series.columns) {
        val present = column < series.columns && series.present[column]
        if (present && runStart < 0) runStart = column
        if (present || runStart < 0) continue

        val fill = Path()
        fill.moveTo(xOf(series.at[runStart]), bottom)
        for (i in runStart until column) fill.lineTo(xOf(series.at[i]), yOf(series.value[i]))
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
        val py = yOf(series.value[column])
        if (open) path.lineTo(px, py) else path.moveTo(px, py)
        open = true
    }
    drawPath(
        path,
        colour,
        style = Stroke(width = lineWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    /*
     * A bucket with no neighbour gets a mark of its own.
     *
     * Left to the stroke it becomes a round cap the width of the line — three pixels — and a
     * channel that is mostly gaps, which is what an imported GPS track's altitude looks like
     * beside a denser speed channel, renders as scattered specks that read as dirt on the screen
     * rather than as measurements. Drawn at the mark token instead, the same data reads as what
     * it is: points, because points are all the source recorded.
     */
    for (column in 0 until series.columns) {
        if (!series.present[column]) continue
        val before = column > 0 && series.present[column - 1]
        val after = column < series.columns - 1 && series.present[column + 1]
        if (before || after) continue
        val centre = Offset(xOf(series.at[column]), yOf(series.value[column]))
        drawCircle(colour, radius = dotRadiusPx * ISOLATED_DOT_SCALE, center = centre)
    }

    // Where the series ends, marked. Cheap, and it stops a short track from reading as a stray
    // scratch in the middle of an otherwise empty bed.
    val lastPresent = (series.columns - 1 downTo 0).firstOrNull { series.present[it] }
    if (lastPresent != null) {
        val centre = Offset(xOf(series.at[lastPresent]), yOf(series.value[lastPresent]))
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
            val centre = Offset(px, yOf(series.value[column]))
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
private const val SELECTED_CHIP_ALPHA = 0.22f
private const val END_DOT_SCALE = 0.7f
private const val GRID_DASH_ON = 6f
private const val GRID_DASH_OFF = 10f
private const val REVEAL_MS = 320

/** A lone bucket is drawn as a mark, not as the round cap of a line that goes nowhere. */
private const val ISOLATED_DOT_SCALE = 0.8f
private val CURSOR_WIDTH = 4.dp

/**
 * Haptic ticks across the whole width of the chart.
 *
 * A finger crossing the plot produces hundreds of pointer events, and one tick per event is a
 * continuous buzz rather than a texture. Forty is about a finger's width per tick, which is what
 * makes the curve feel like a scale under the fingertip.
 */
private const val SCRUB_TICKS = 40

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
 * unconsumed: a chart that swallows vertical drags traps the athlete on it.
 */
private fun Modifier.scrubbable(
    x: DoubleArray,
    insetPx: Float,
    cursor: MutableState<Int?>,
    selection: MutableState<IntRange?>,
    haptics: HapticFeedback,
): Modifier = pointerInput(x, insetPx) {
    val plotWidthPx = (size.width - insetPx * 2).coerceAtLeast(1f)
    // The sample under a horizontal position. `x` is monotonic in both axis modes, which is what
    // makes this exact; it has to agree with `xOf` in the draw pass constant for constant — the
    // cursor line and the sample it reports are the same thing seen twice.
    val indexAt: (Float) -> Int = { position ->
        val fraction = ((position - insetPx) / plotWidthPx).coerceIn(0f, 1f)
        TelemetryAnalysis.nearestIndex(x, x[0] + (x[x.size - 1] - x[0]) * fraction)
    }
    val tickStep = (x.size / SCRUB_TICKS).coerceAtLeast(1)
    var lastTick = -1

    fun scrub(index: Int) {
        if (index / tickStep != lastTick) {
            lastTick = index / tickStep
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        cursor.value = index
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        lastTick = -1

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
                scrub(indexAt(change.position.x))
                change.consume()
            }

            // A tap parks the cursor where it landed, which is the phone's answer to hover.
            Gesture.TAP -> cursor.value = indexAt(down.position.x)

            Gesture.SCROLL -> Unit
        }
    }
}
