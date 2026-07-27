package dev.healthhub.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.HealthHubType
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.model.RoutePoint
import dev.healthhub.core.model.UnitSystem
import kotlin.math.roundToInt

/**
 * The activity detail screen: summary, route, aligned charts, splits and zones.
 *
 * The same surface as the web client's, built from the same numbers. Everything with a figure on
 * it was computed on a phone at ingest and is displayed verbatim; the only things derived here
 * are the distance axis and the statistics for a range the athlete selects, and both mirror the
 * browser's rules constant for constant (see [TelemetryAnalysis]).
 */
private val PANEL_ORDER = listOf("elevation", "speed", "hr", "cadence", "power")

private val PANEL_LABELS = mapOf(
    "elevation" to "Elevation",
    "speed" to "Speed",
    "hr" to "Heart rate",
    "cadence" to "Cadence",
    "power" to "Power",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityScreen(onBack: () -> Unit, viewModel: ActivityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.activity?.let { Format.sportLabel(it.sport) } ?: "Activity",
                        style = HealthHubType.titleLargeEmphasized,
                    )
                },
                // An icon rather than the word "Back": the Expressive app bar expects a 48 dp
                // touch target in the navigation slot, and a text button there both under-fills
                // it and competes with the title for the eye.
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to activities",
                        )
                    }
                },
            )
        },
    ) { padding ->
        val activity = state.activity
        when {
            state.loading && activity == null -> Box(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            activity == null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text("Could not open this activity", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.error ?: "It may not have finished syncing yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = viewModel::load) { Text("Try again") }
            }

            else -> ActivityDetail(
                activity = activity,
                telemetry = state.telemetry,
                resolution = state.resolution,
                units = state.units,
                route = state.route,
                onImportRoute = viewModel::onRouteGranted,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ActivityDetail(
    activity: ActivityDetailDto,
    telemetry: TelemetryChannels?,
    resolution: Resolution,
    units: UnitSystem,
    route: RouteImportState,
    onImportRoute: (String, List<RoutePoint>?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sport = activity.sport
    val cursor = remember { mutableStateOf<Int?>(null) }
    val selection = remember { mutableStateOf<IntRange?>(null) }
    var axis by rememberSaveable { mutableStateOf(Axis.TIME) }

    // A new object at a different resolution means every index the athlete was pointing at
    // refers to a different sample. Keeping them would silently move the cursor.
    LaunchedEffect(telemetry) {
        cursor.value = null
        selection.value = null
    }

    val hasDistanceAxis = telemetry?.distance != null
    val activeAxis = if (hasDistanceAxis) axis else Axis.TIME
    val x = remember(telemetry, activeAxis) { xAxisOf(telemetry, activeAxis) }
    val panels = remember(telemetry, sport, units) { panelsOf(telemetry, activity, sport, units) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                Format.localDate(activity.startTime, activity.tzOffsetMinutes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(activity.title, style = MaterialTheme.typography.headlineSmall)
            activity.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        SummaryCard(activity = activity, sport = sport, units = units)

        val lat = telemetry?.lat
        val lon = telemetry?.lon
        // A track of nothing but dropouts is not a route. Checking here rather than inside the
        // map keeps the explanatory state and the map as one either-or instead of a blank space.
        val drawable = remember(lat, lon) {
            lat != null && lon != null &&
                (0 until minOf(lat.size, lon.size)).any { !lat[it].isNaN() && !lon[it].isNaN() }
        }

        if (drawable && lat != null && lon != null) {
            RouteMap(lat = lat, lon = lon, time = telemetry?.time, cursor = cursor)
            // The import's verdict outlives the card it was shown in. It is the moment the map
            // appears that the athlete needs to be told whether the distance above it changed,
            // and letting the map replace the explanation would drop that sentence on the floor.
            if (route is RouteImportState.Imported) {
                RouteOutcome(message = route.message)
            }
        } else {
            RouteCard(state = route, onImport = onImportRoute)
        }

        if (panels.isNotEmpty() && x != null) {
            SectionCard(title = null) {
                AxisChips(
                    axis = activeAxis,
                    enabled = hasDistanceAxis,
                    onSelect = { axis = it },
                )
                if (resolution == Resolution.PREVIEW) {
                    Text(
                        "Preview resolution — loading full detail…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Said out loud rather than left to be noticed: above the sample budget the
                // charts and the selection statistics are computed over a thinned series, and
                // the browser holding the whole object would then report slightly differently.
                val thinned = telemetry?.takeIf { it.decimated }
                if (thinned != null) {
                    Text(
                        "Showing 1 sample in " +
                            "${thinned.storedCount / thinned.count.coerceAtLeast(1)} — " +
                            "this recording has ${thinned.storedCount} samples.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TelemetryCharts(
                    x = x,
                    xFormat = xFormatFor(activeAxis, units),
                    panels = panels,
                    cursor = cursor,
                    selection = selection,
                )

                SelectionPanel(
                    channels = telemetry,
                    selection = selection,
                    sport = sport,
                    units = units,
                )
            }
        } else if (resolution == Resolution.NONE) {
            EmptyState(
                title = "No per-second data for this workout",
                body = "The source recorded a summary but no samples, so there is nothing to " +
                    "plot. The figures above are what it did report.",
            )
        }

        SplitsTable(splits = activity.splits, sport = sport, units = units)
        ZoneDistribution(zones = activity.zones)
    }
}

/* ------------------------------------------------------------------------- sections */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(activity: ActivityDetailDto, sport: String, units: UnitSystem) {
    val pacey = !Format.usesSpeed(sport)
    SectionCard(title = null) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Stat("Distance", Format.distance(activity.distanceM, units))
            Stat("Moving", Format.duration(activity.movingSeconds ?: activity.elapsedSeconds))
            Stat("Elapsed", Format.duration(activity.elapsedSeconds))
            Stat(
                if (pacey) "Avg pace" else "Avg speed",
                Format.paceOrSpeed(activity.avgSpeedMps, sport, units),
            )
            activity.maxSpeedMps?.let {
                Stat(
                    if (pacey) "Best pace" else "Max speed",
                    Format.paceOrSpeed(it, sport, units),
                )
            }
            activity.elevationGainM?.let { Stat("Elev gain", Format.elevation(it, units)) }
            activity.avgHrBpm?.let { Stat("Avg HR", "$it bpm") }
            activity.maxHrBpm?.let { Stat("Max HR", "$it bpm") }
            activity.avgPowerW?.let { Stat("Avg power", Format.power(it)) }
            activity.caloriesKcal?.let { Stat("Calories", "${it.roundToInt()} kcal") }
        }
    }
}

/**
 * One figure and its name.
 *
 * The value carries the Expressive *emphasised* weight and the label does not: on a card holding
 * ten of these, the thing that has to be readable at a glance is the number, and the Expressive
 * type scale exists precisely so that prominence is a role rather than a hand-picked size.
 */
@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = HealthHubType.titleMediumEmphasized)
    }
}

/**
 * The statistics for the stretch the athlete marked.
 *
 * Read in its own composable so a cursor moving under the finger repaints the charts without
 * recomposing this, and so selecting a range recomposes only this.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionPanel(
    channels: TelemetryChannels?,
    selection: MutableState<IntRange?>,
    sport: String,
    units: UnitSystem,
) {
    val range = selection.value
    if (channels == null || range == null) {
        Text(
            "Press and hold a chart, then drag, to see the numbers for just that stretch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val stats = remember(channels, range) {
        TelemetryAnalysis.rangeStats(channels, range.first, range.last)
    }
    val pacey = !Format.usesSpeed(sport)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Selection", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { selection.value = null }) { Text("Clear") }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Stat("Distance", Format.distance(stats.distanceM, units))
            Stat("Elapsed", Format.duration(stats.elapsedSeconds))
            Stat("Moving", Format.duration(stats.movingSeconds))
            Stat(
                if (pacey) "Avg pace" else "Avg speed",
                Format.paceOrSpeed(stats.avgSpeedMps, sport, units),
            )
            Stat("Elev gain", Format.elevation(stats.elevationGainM, units))
            Stat("Avg HR", Format.heartRate(stats.avgHrBpm))
            stats.avgPowerW?.let { Stat("Avg power", Format.power(it)) }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    SectionCard(title = null) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AxisChips(axis: Axis, enabled: Boolean, onSelect: (Axis) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FilterChip(
            selected = axis == Axis.TIME,
            onClick = { onSelect(Axis.TIME) },
            label = { Text("Time") },
        )
        FilterChip(
            selected = axis == Axis.DISTANCE,
            onClick = { onSelect(Axis.DISTANCE) },
            label = { Text("Distance") },
            enabled = enabled,
        )
    }
}

/* --------------------------------------------------------------------------- derivation */

internal enum class Axis { TIME, DISTANCE }

/**
 * The x axis the stack is drawn against.
 *
 * Seconds rather than milliseconds, so the tick labels read as durations, and the distance axis
 * straight from [TelemetryAnalysis.cumulativeDistance] — the same two choices the web client
 * makes, because a cursor at "1:23:45" has to mean the same sample on both screens.
 */
private fun xAxisOf(telemetry: TelemetryChannels?, axis: Axis): DoubleArray? {
    if (telemetry == null) return null
    if (axis == Axis.DISTANCE) return telemetry.distance
    val time = telemetry.time ?: return null
    val seconds = DoubleArray(time.size)
    for (i in time.indices) seconds[i] = time[i] / 1000.0
    return seconds
}

private fun xFormatFor(axis: Axis, units: UnitSystem): (Double) -> String {
    if (axis == Axis.DISTANCE) return { value -> Format.distance(value, units) }
    return { value -> Format.duration(value) }
}

/** The units a channel reads in. Separate from the panel so the `when` returns a value, not a
 *  lambda — a `when` branch whose body is braces is a block, and the two are easy to confuse. */
private fun formatterFor(key: String, sport: String, units: UnitSystem): (Double) -> String {
    if (key == "elevation") return { value -> Format.elevation(value, units) }
    if (key == "speed") return { value -> Format.paceOrSpeed(value, sport, units) }
    if (key == "hr") return { value -> Format.heartRate(value) }
    if (key == "cadence") return { value -> Format.cadence(value) }
    return { value -> Format.power(value) }
}

/**
 * One panel per channel the source actually recorded, in a fixed stacking order.
 *
 * An absent channel gets no panel at all — an empty axis reading zero would claim the sensor was
 * there and measured nothing, which is the opposite of what happened.
 */
private fun panelsOf(
    telemetry: TelemetryChannels?,
    activity: ActivityDetailDto,
    sport: String,
    units: UnitSystem,
): List<ChartPanel> {
    if (telemetry == null) return emptyList()
    val pacey = !Format.usesSpeed(sport)

    return PANEL_ORDER.mapNotNull { key ->
        val values = telemetry.channel(key)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ChartPanel(
            key = key,
            label = if (key == "speed" && pacey) "Pace" else PANEL_LABELS.getValue(key),
            values = values,
            format = formatterFor(key, sport, units),
            summary = when (key) {
                "elevation" -> activity.elevationGainM?.let { "+${Format.elevation(it, units)}" }
                "speed" -> Format.paceOrSpeed(activity.avgSpeedMps, sport, units)
                "hr" -> activity.avgHrBpm?.let { "$it bpm" }
                "cadence" -> activity.avgCadenceRpm?.let { Format.cadence(it) }
                else -> activity.avgPowerW?.let { Format.power(it) }
            },
        )
    }
}
