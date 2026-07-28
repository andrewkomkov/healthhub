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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.ExpressiveShapes
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
        // The one answer both branches are decided from. It used to be two — the screen asked
        // "are there any positions" and the map asked "is there a line" — and a recording whose
        // track is a single fix answered yes to the first and no to the second, so it rendered
        // no map *and* no explanation, just a gap between two cards.
        val geometry = remember(lat, lon, telemetry?.time) {
            Route.geometry(lat, lon, telemetry?.time)
        }
        val fixes = remember(lat, lon) {
            if (lat == null || lon == null) {
                0
            } else {
                (0 until minOf(lat.size, lon.size)).count { !lat[it].isNaN() && !lon[it].isNaN() }
            }
        }

        if (geometry.bounds != null) {
            RouteMap(geometry = geometry, lat = lat, lon = lon, cursor = cursor)
            // The import's verdict outlives the card it was shown in. It is the moment the map
            // appears that the athlete needs to be told whether the distance above it changed,
            // and letting the map replace the explanation would drop that sentence on the floor.
            if (route is RouteImportState.Imported) {
                RouteOutcome(message = route.message)
            }
        } else {
            RouteCard(state = route, fixes = fixes, onImport = onImportRoute)
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
    // Tiles rather than a card holding ten label-and-value pairs. Ten pairs inside one container
    // is a table, and a table is read by scanning a column — but there is no column here, only a
    // wrap, so the eye has to walk it item by item. Each figure in its own tonal container has
    // an edge to stop at, which is what lets "Avg HR" be found without reading the nine beside it.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        maxItemsInEachRow = 2,
    ) {
        val tile = Modifier.weight(1f)
        Stat(Icons.Rounded.Route, "Distance", Format.distance(activity.distanceM, units), tile)
        Stat(
            Icons.Rounded.Timer,
            "Moving",
            Format.duration(activity.movingSeconds ?: activity.elapsedSeconds),
            tile,
        )
        Stat(Icons.Rounded.Schedule, "Elapsed", Format.duration(activity.elapsedSeconds), tile)
        Stat(
            Icons.Rounded.Speed,
            if (pacey) "Avg pace" else "Avg speed",
            Format.paceOrSpeed(activity.avgSpeedMps, sport, units),
            tile,
        )
        activity.maxSpeedMps?.let {
            Stat(
                Icons.Rounded.Bolt,
                if (pacey) "Best pace" else "Max speed",
                Format.paceOrSpeed(it, sport, units),
                tile,
            )
        }
        activity.elevationGainM?.let {
            Stat(Icons.Rounded.Terrain, "Elev gain", Format.elevation(it, units), tile)
        }
        activity.avgHrBpm?.let { Stat(Icons.Rounded.Favorite, "Avg HR", "$it bpm", tile) }
        activity.maxHrBpm?.let { Stat(Icons.Rounded.MonitorHeart, "Max HR", "$it bpm", tile) }
        activity.avgPowerW?.let { Stat(Icons.Rounded.Bolt, "Avg power", Format.power(it), tile) }
        activity.caloriesKcal?.let {
            Stat(
                Icons.Rounded.LocalFireDepartment,
                "Calories",
                "${it.roundToInt()} kcal",
                tile,
            )
        }
    }
}

/**
 * One figure, in its own container, under its icon and name.
 *
 * The value carries the Expressive *emphasised* weight and the label does not: the thing that has
 * to be readable at a glance is the number, and the Expressive type scale exists precisely so
 * that prominence is a role rather than a hand-picked size. The icon is what makes the tile
 * findable without reading it at all.
 */
@Composable
private fun Stat(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = ExpressiveShapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
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
                )
            }
            Text(value, style = HealthHubType.headlineLargeEmphasized, maxLines = 1)
        }
    }
}

/** Sized to the label beside it rather than to the touch grid: nothing here is tappable. */
private val STAT_ICON_SIZE = 15.dp

/**
 * A figure and its name, with no container of its own.
 *
 * The selection panel is *inside* a card and is read as a block — it answers one question, "what
 * happened in the stretch I marked" — so tiles there would be containers inside a container.
 */
@Composable
private fun Figure(label: String, value: String) {
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
            Figure("Distance", Format.distance(stats.distanceM, units))
            Figure("Elapsed", Format.duration(stats.elapsedSeconds))
            Figure("Moving", Format.duration(stats.movingSeconds))
            Figure(
                if (pacey) "Avg pace" else "Avg speed",
                Format.paceOrSpeed(stats.avgSpeedMps, sport, units),
            )
            Figure("Elev gain", Format.elevation(stats.elevationGainM, units))
            Figure("Avg HR", Format.heartRate(stats.avgHrBpm))
            stats.avgPowerW?.let { Figure("Avg power", Format.power(it)) }
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
            // Standing still is not a pace. Without this the axis of a ride with traffic lights
            // in it runs down to two hours per kilometre and the ride itself is a sliver.
            axisFloor = if (key == "speed") {
                TelemetryAnalysis.MOVING_SPEED_THRESHOLD_MPS
            } else {
                Double.NEGATIVE_INFINITY
            },
        )
    }
}
