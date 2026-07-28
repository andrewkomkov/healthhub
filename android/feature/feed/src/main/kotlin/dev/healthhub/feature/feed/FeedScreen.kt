package dev.healthhub.feature.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.HealthHubType
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.LocalNavMenu
import dev.healthhub.core.network.FeedActivityDto
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenActivity: (String) -> Unit,
    onOpenSync: () -> Unit,
    onOpen: (Destination) -> Unit,
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val menu = LocalNavMenu.current
    var menuOpen by remember { mutableStateOf(false) }

    // Paging is driven by how close the list is to its end rather than by a scroll listener,
    // so the next page is already in flight before the athlete reaches the bottom.
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !state.loading && !state.exhausted) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activities") },
                actions = {
                    TextButton(onClick = onOpenSync) { Text("Sync") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        // The rest of the app, as declared by the modules that serve it — see
                        // `NavContribution.menuEntries`. Until this menu existed the only way to
                        // any of those screens was an ADB deep link, which is not a way in for
                        // the person the app is for.
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            menu.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.label) },
                                    leadingIcon = { Icon(entry.icon, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onOpen(entry.destination)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.activities.isEmpty() && state.loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.activities.isEmpty() -> EmptyFeed(
                    message = state.error ?: "No activities yet.",
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    items(state.activities, key = { it.id }) { activity ->
                        ActivityCard(activity = activity, onClick = { onOpenActivity(activity.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFeed(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text("Nothing here yet", style = MaterialTheme.typography.titleLarge)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            "Grant Health Connect access on the sync screen, then run a sync.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One workout in the feed.
 *
 * The card leads with the *figure*, not with the label. Four columns of "Distance / Time / Pace"
 * with the values underneath gave every number the same weight and made the card a small table:
 * an athlete scrolling a month of walks reads distance and date, and reads the rest only when
 * one of them looks unusual. So distance is a headline, the date sits beside it in a quiet role,
 * and everything else is an icon and a number in a wrapping row — no containers, no second table.
 *
 * The icons are the alphabet: a stopwatch, a heart, a mountain. They survive being glanced at,
 * which is what a feed is looked at with, and they cost less width than the words did — which is
 * how six figures now fit where four used to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityCard(activity: FeedActivityDto, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // Expressive leans on larger radii than classic Material; the shape comes from the
        // generated token scale, not a literal dp value.
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDistance(activity.distanceM),
                    style = HealthHubType.titleLargeEmphasized,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    formatDate(activity),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Two things worth knowing before opening the card, and both are one glyph: this
                // workout has a track to look at, and more than one app recorded it.
                if (activity.sourceCount > 1) {
                    Icon(
                        Icons.Rounded.Layers,
                        contentDescription = "Recorded by ${activity.sourceCount} apps",
                        modifier = Modifier.size(ICON_SIZE),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                if (activity.hasGps) {
                    Icon(
                        Icons.Rounded.Map,
                        contentDescription = "Has a route",
                        modifier = Modifier.size(ICON_SIZE),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Health Connect writes no title of its own, so the sport is stored as one and the
            // line read "Walking · Walking". The title is only worth its own words when somebody
            // — the athlete, or the app that recorded it — actually wrote one.
            val sport = activity.sport.replaceFirstChar { it.uppercase() }
            Text(
                if (activity.title.equals(activity.sport, ignoreCase = true)) {
                    sport
                } else {
                    "$sport · ${activity.title}"
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                // Guard against a stored zero as well as a null: older rows were synced before
                // the engine learned to treat an unmeasurable moving time as unknown.
                Metric(
                    Icons.Rounded.Timer,
                    formatDuration(
                        activity.movingSeconds?.takeIf { it > 0 } ?: activity.elapsedSeconds,
                    ),
                )
                Metric(Icons.Rounded.Speed, formatPace(activity.avgSpeedMps, activity.sport))
                activity.avgHrBpm?.let { Metric(Icons.Rounded.Favorite, "$it bpm") }
                activity.elevationGainM?.takeIf { it >= 1 }?.let {
                    Metric(Icons.Rounded.Terrain, "+${it.roundToInt()} m")
                }
            }
        }
    }
}

/** A figure with its icon. No chip container: a feed of them would be a wall of outlines. */
@Composable
private fun Metric(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Sized to the label beside it rather than to the touch grid: nothing here is tappable. */
private val ICON_SIZE = 16.dp

/* ------------------------------------------------------------------ formatting */

private val dateFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

/** Rendered in the timezone the workout was recorded in, not the viewer's. */
private fun formatDate(activity: FeedActivityDto): String =
    Instant.ofEpochMilli(activity.startTime)
        .atOffset(ZoneOffset.ofTotalSeconds(activity.tzOffsetMinutes * 60))
        .format(dateFormatter)

private fun formatDistance(metres: Double?): String =
    if (metres == null) "—" else String.format(Locale.US, "%.2f km", metres / 1000)

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** Cycling reads as speed; running and walking read as pace. */
private fun formatPace(metresPerSecond: Double?, sport: String): String {
    if (metresPerSecond == null || metresPerSecond <= 0) return "—"
    if (sport in setOf("cycling", "ebiking", "rowing", "skiing", "skating")) {
        return String.format(Locale.US, "%.1f km/h", metresPerSecond * 3.6)
    }
    val secondsPerKm = 1000 / metresPerSecond
    val minutes = (secondsPerKm / 60).toInt()
    val seconds = (secondsPerKm % 60).roundToInt()
    return String.format(Locale.US, "%d:%02d /km", minutes, seconds)
}
