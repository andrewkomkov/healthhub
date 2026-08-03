package dev.healthhub.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.HealthHubType
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.model.RoutePoint
import dev.healthhub.core.model.UnitSystem
import dev.healthhub.core.navigation.ActivityAction
import dev.healthhub.core.ui.ErrorState
import dev.healthhub.core.ui.Format
import dev.healthhub.core.ui.UnitLabels
import dev.healthhub.core.ui.unitLabels
import dev.healthhub.core.ui.HealthHubIcons
import dev.healthhub.core.ui.SectionCard
import dev.healthhub.core.ui.SkeletonBox
import dev.healthhub.core.ui.SkeletonLine
import dev.healthhub.core.ui.StatTile
import dev.healthhub.core.ui.sportName
import dev.healthhub.core.ui.R as CoreR

/**
 * The activity detail screen: summary, route, aligned charts, splits and zones.
 *
 * The same surface as the web client's, built from the same numbers. Everything with a figure on
 * it was computed on a phone at ingest and is displayed verbatim; the only things derived here
 * are the distance axis and the statistics for a range the athlete selects, and both mirror the
 * browser's rules constant for constant (see [TelemetryAnalysis]).
 */
private val PANEL_ORDER = listOf("elevation", "speed", "hr", "cadence", "power")

/** A channel's name, looked up where there is a `Context` to look it up with. */
private val PANEL_LABELS = mapOf(
    "elevation" to R.string.channel_elevation,
    "speed" to R.string.channel_speed,
    "hr" to R.string.channel_hr,
    "cadence" to R.string.channel_cadence,
    "power" to R.string.channel_power,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityScreen(onBack: () -> Unit, viewModel: ActivityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirming by remember { mutableStateOf<ActivityAction?>(null) }

    val message = state.message
    // Resolved in the composition, where a `Context` exists, and only then handed to the
    // snackbar — the view model has no business knowing what language the phone is in.
    val messageText = when (message) {
        is ActivityMessage.Resource -> stringResource(message.id)
        is ActivityMessage.Text -> message.value
        null -> null
    }
    LaunchedEffect(messageText) {
        if (messageText == null) return@LaunchedEffect
        snackbar.showSnackbar(messageText)
        viewModel.messageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.activity?.let { sportName(it.sport) } ?: stringResource(R.string.activity_fallback_title),
                        style = HealthHubType.titleLargeEmphasized,
                    )
                },
                // An icon rather than the word "Back": the Expressive app bar expects a 48 dp
                // touch target in the navigation slot, and a text button there both under-fills
                // it and competes with the title for the eye.
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HealthHubIcons.Back, contentDescription = stringResource(CoreR.string.action_back))
                    }
                },
                actions = {
                    // Whatever other modules contribute for this workout. This screen names
                    // none of them and does not know what any of them do — see
                    // `ActivityActionProvider`. An empty set draws no button at all, which is
                    // what a build without `feature:sources` in it should look like.
                    ActionMenu(
                        actions = state.actions,
                        running = state.runningAction,
                        onSelect = { action ->
                            if (action.confirm != null) confirming = action
                            else viewModel.perform(action)
                        },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val activity = state.activity
        when {
            // A skeleton in the shape of the screen, not a spinner in the middle of it. This
            // one opens on a tap from a card the athlete is already looking at, so the layout
            // arriving before the numbers is what makes the transition read as continuous.
            state.loading && activity == null ->
                ActivityDetailSkeleton(modifier = Modifier.padding(padding))

            activity == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                ErrorState(
                    title = stringResource(R.string.activity_error_title),
                    message = state.error ?: stringResource(R.string.activity_error_body),
                    onRetry = viewModel::load,
                )
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

    confirming?.let { action ->
        val confirmation = action.confirm ?: return@let
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(confirmation.title)) },
            text = { Text(stringResource(confirmation.body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    viewModel.perform(action)
                }) { Text(stringResource(confirmation.confirmLabel)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(CoreR.string.action_cancel))
                }
            },
        )
    }
}

/**
 * The contributed actions, behind one overflow button.
 *
 * An overflow rather than icons in the bar: this screen's own title has to survive, the actions
 * are words rather than universally understood marks ("Set aside" is not a glyph anyone knows),
 * and there is no upper bound on how many modules contribute — a bar that grows a button per
 * installed feature stops being a title bar.
 */
@Composable
private fun ActionMenu(
    actions: List<ActivityAction>,
    running: String?,
    onSelect: (ActivityAction) -> Unit,
) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }, enabled = running == null) {
            Icon(HealthHubIcons.Overflow, contentDescription = stringResource(R.string.activity_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                    onClick = {
                        open = false
                        onSelect(action)
                    },
                )
            }
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
    val labels = unitLabels()
    val x = remember(telemetry, activeAxis) { xAxisOf(telemetry, activeAxis) }
    // Resolved here rather than inside `panelsOf`, which is a plain function with no
    // composition around it: the labels are resources and the strings are what it is handed.
    val channelNames = PANEL_ORDER.associateWith { stringResource(PANEL_LABELS.getValue(it)) }
    val paceName = stringResource(R.string.channel_pace)
    val panels = remember(telemetry, sport, units, channelNames, labels) {
        panelsOf(telemetry, activity, sport, units, channelNames, paceName, labels)
    }

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
                        stringResource(R.string.activity_preview),
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
                        stringResource(
                            R.string.activity_thinned,
                            thinned.storedCount / thinned.count.coerceAtLeast(1),
                            thinned.storedCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TelemetryCharts(
                    x = x,
                    xFormat = xFormatFor(activeAxis, units, labels),
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
            InlineNote(
                title = stringResource(R.string.activity_no_samples_title),
                body = stringResource(R.string.activity_no_samples_body),
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
    val labels = unitLabels()
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
        StatTile(
            HealthHubIcons.Distance,
            stringResource(CoreR.string.metric_distance),
            Format.distance(activity.distanceM, units, labels),
            tile,
        )
        StatTile(
            HealthHubIcons.Duration,
            stringResource(CoreR.string.metric_moving),
            Format.duration(activity.movingSeconds ?: activity.elapsedSeconds),
            tile,
        )
        StatTile(
            Icons.Rounded.Schedule,
            stringResource(CoreR.string.metric_elapsed),
            Format.duration(activity.elapsedSeconds),
            tile,
        )
        StatTile(
            HealthHubIcons.Pace,
            stringResource(
                if (pacey) CoreR.string.metric_avg_pace else CoreR.string.metric_avg_speed,
            ),
            Format.paceOrSpeed(activity.avgSpeedMps, sport, units, labels),
            tile,
        )
        activity.maxSpeedMps?.let {
            StatTile(
                HealthHubIcons.Power,
                stringResource(
                    if (pacey) CoreR.string.metric_best_pace else CoreR.string.metric_max_speed,
                ),
                Format.paceOrSpeed(it, sport, units, labels),
                tile,
            )
        }
        activity.elevationGainM?.let {
            StatTile(
                HealthHubIcons.Elevation,
                stringResource(CoreR.string.metric_elevation_gain),
                Format.elevation(it, units, labels),
                tile,
            )
        }
        activity.avgHrBpm?.let {
            StatTile(
                HealthHubIcons.HeartRate,
                stringResource(CoreR.string.metric_avg_hr),
                Format.heartRate(it.toDouble(), labels),
                tile,
            )
        }
        activity.maxHrBpm?.let {
            StatTile(
                Icons.Rounded.MonitorHeart,
                stringResource(CoreR.string.metric_max_hr),
                Format.heartRate(it.toDouble(), labels),
                tile,
            )
        }
        activity.avgPowerW?.let {
            StatTile(
                HealthHubIcons.Power,
                stringResource(CoreR.string.metric_avg_power),
                Format.power(it, labels),
                tile,
            )
        }
        activity.caloriesKcal?.let {
            StatTile(
                HealthHubIcons.Calories,
                stringResource(CoreR.string.metric_calories),
                Format.calories(it, labels),
                tile,
            )
        }
    }
}

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
            stringResource(R.string.activity_selection_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val stats = remember(channels, range) {
        TelemetryAnalysis.rangeStats(channels, range.first, range.last)
    }
    val labels = unitLabels()
    val pacey = !Format.usesSpeed(sport)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.activity_selection),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { selection.value = null }) {
                Text(stringResource(R.string.activity_selection_clear))
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Figure(
                stringResource(CoreR.string.metric_distance),
                Format.distance(stats.distanceM, units, labels),
            )
            Figure(
                stringResource(CoreR.string.metric_elapsed),
                Format.duration(stats.elapsedSeconds),
            )
            Figure(
                stringResource(CoreR.string.metric_moving),
                Format.duration(stats.movingSeconds),
            )
            Figure(
                stringResource(
                    if (pacey) CoreR.string.metric_avg_pace else CoreR.string.metric_avg_speed,
                ),
                Format.paceOrSpeed(stats.avgSpeedMps, sport, units, labels),
            )
            Figure(
                stringResource(CoreR.string.metric_elevation_gain),
                Format.elevation(stats.elevationGainM, units, labels),
            )
            Figure(stringResource(CoreR.string.metric_avg_hr), Format.heartRate(stats.avgHrBpm, labels))
            stats.avgPowerW?.let {
                Figure(stringResource(CoreR.string.metric_avg_power), Format.power(it, labels))
            }
        }
    }
}

/**
 * A card that explains why the section it stands in place of is not there.
 *
 * Deliberately not `core:ui`'s `EmptyState`, which owns a whole screen and offers a way out of
 * it. This one sits between two sections that *did* render, so it is the size of the thing it
 * replaces and carries no action — there is nothing the athlete can do about a source that
 * recorded no samples, and a button suggesting otherwise would be a lie.
 */
@Composable
private fun InlineNote(title: String, body: String) {
    SectionCard(title = null) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The detail screen before its numbers arrive.
 *
 * Laid out as the screen it becomes — a headline, a grid of tiles, a map-sized block, a chart —
 * so that nothing moves when the request answers. The alternative, a spinner in the middle of an
 * empty screen, gives the athlete no idea whether this workout has a route on it until it either
 * appears or does not.
 */
@Composable
private fun ActivityDetailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SkeletonLine(widthFraction = 0.4f, height = 12.dp)
            SkeletonLine(widthFraction = 0.7f, height = 28.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SkeletonBox(
                modifier = Modifier.weight(1f).height(SKELETON_TILE_HEIGHT),
                shape = MaterialTheme.shapes.extraLarge,
            )
            SkeletonBox(
                modifier = Modifier.weight(1f).height(SKELETON_TILE_HEIGHT),
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SkeletonBox(
                modifier = Modifier.weight(1f).height(SKELETON_TILE_HEIGHT),
                shape = MaterialTheme.shapes.extraLarge,
            )
            SkeletonBox(
                modifier = Modifier.weight(1f).height(SKELETON_TILE_HEIGHT),
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        SkeletonBox(
            modifier = Modifier.fillMaxWidth().height(SKELETON_MAP_HEIGHT),
            shape = MaterialTheme.shapes.extraLarge,
        )
        SkeletonBox(
            modifier = Modifier.fillMaxWidth().height(SKELETON_CHART_HEIGHT),
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
}

private val SKELETON_TILE_HEIGHT = 84.dp
private val SKELETON_MAP_HEIGHT = 220.dp
private val SKELETON_CHART_HEIGHT = 180.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AxisChips(axis: Axis, enabled: Boolean, onSelect: (Axis) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FilterChip(
            selected = axis == Axis.TIME,
            onClick = { onSelect(Axis.TIME) },
            label = { Text(stringResource(R.string.activity_axis_time)) },
        )
        FilterChip(
            selected = axis == Axis.DISTANCE,
            onClick = { onSelect(Axis.DISTANCE) },
            label = { Text(stringResource(R.string.activity_axis_distance)) },
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

private fun xFormatFor(axis: Axis, units: UnitSystem, labels: UnitLabels): (Double) -> String {
    if (axis == Axis.DISTANCE) return { value -> Format.distance(value, units, labels) }
    return { value -> Format.duration(value) }
}

/** The units a channel reads in. Separate from the panel so the `when` returns a value, not a
 *  lambda — a `when` branch whose body is braces is a block, and the two are easy to confuse. */
private fun formatterFor(
    key: String,
    sport: String,
    units: UnitSystem,
    labels: UnitLabels,
): (Double) -> String {
    if (key == "elevation") return { value -> Format.elevation(value, units, labels) }
    if (key == "speed") return { value -> Format.paceOrSpeed(value, sport, units, labels) }
    if (key == "hr") return { value -> Format.heartRate(value, labels) }
    if (key == "cadence") return { value -> Format.cadence(value, labels) }
    return { value -> Format.power(value, labels) }
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
    channelNames: Map<String, String>,
    paceName: String,
    labels: UnitLabels,
): List<ChartPanel> {
    if (telemetry == null) return emptyList()
    val pacey = !Format.usesSpeed(sport)

    return PANEL_ORDER.mapNotNull { key ->
        val values = telemetry.channel(key)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ChartPanel(
            key = key,
            label = if (key == "speed" && pacey) paceName else channelNames.getValue(key),
            values = values,
            format = formatterFor(key, sport, units, labels),
            summary = when (key) {
                "elevation" -> activity.elevationGainM?.let { "+${Format.elevation(it, units, labels)}" }
                "speed" -> Format.paceOrSpeed(activity.avgSpeedMps, sport, units, labels)
                "hr" -> activity.avgHrBpm?.let { "$it bpm" }
                "cadence" -> activity.avgCadenceRpm?.let { Format.cadence(it, labels) }
                else -> activity.avgPowerW?.let { Format.power(it, labels) }
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
