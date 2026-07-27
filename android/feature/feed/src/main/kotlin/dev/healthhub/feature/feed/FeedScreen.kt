package dev.healthhub.feature.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.Spacing
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
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

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
                    androidx.compose.material3.TextButton(onClick = onOpenSync) { Text("Sync") }
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

@Composable
private fun ActivityCard(activity: FeedActivityDto, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // Expressive leans on larger radii than classic Material; the shape comes from the
        // generated token scale, not a literal dp value.
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                "${activity.sport.replaceFirstChar { it.uppercase() }} · ${formatDate(activity)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(activity.title, style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                Stat("Distance", formatDistance(activity.distanceM))
                // Guard against a stored zero as well as a null: older rows were synced
                // before the engine learned to treat an unmeasurable moving time as unknown.
                Stat(
                    "Time",
                    formatDuration(
                        activity.movingSeconds?.takeIf { it > 0 } ?: activity.elapsedSeconds,
                    ),
                )
                Stat("Pace", formatPace(activity.avgSpeedMps, activity.sport))
                activity.avgHrBpm?.let { Stat("Avg HR", "$it bpm") }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

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
