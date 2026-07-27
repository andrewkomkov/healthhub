package dev.healthhub.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.model.UnitSystem

/**
 * The recordings a higher-priority source displaced.
 *
 * Every word on this screen is chosen so that an athlete leaves it certain nothing was thrown
 * away. There is no destructive action here and no route to one, because the model has none —
 * a recording is either representing its workout or waiting in this list, and the athlete can
 * move it between the two as often as they like.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveScreen(
    onOpenActivity: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ArchiveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Paged the way the feed is: the next page is in flight before the list runs out.
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 3
        }
    }

    // Keyed on the list length as well, because a page that lands without pushing the last
    // visible row far enough leaves `shouldLoadMore` true and the effect would never run again.
    // A failed page stops the automatic reach entirely — the footer asks instead of retrying in
    // a loop the athlete cannot see.
    LaunchedEffect(shouldLoadMore, state.activities.size) {
        if (shouldLoadMore && !state.loading && !state.exhausted && state.error == null) {
            viewModel.loadMore()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archive") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.activities.isEmpty() && state.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.activities.isEmpty() -> EmptyArchive(
                    message = state.error,
                    onRetry = viewModel::loadMore,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    item(key = "intro") { ArchiveIntro() }

                    items(state.activities, key = { it.id }) { activity ->
                        ArchivedCard(
                            activity = activity,
                            units = state.units,
                            cardState = state.cards[activity.id] ?: CardState.IDLE,
                            onOpen = { onOpenActivity(activity.id) },
                            onRestore = { viewModel.restore(activity.id) },
                            onSetAside = { viewModel.setAside(activity.id) },
                        )
                    }

                    item(key = "footer") {
                        ArchiveFooter(
                            error = state.error,
                            exhausted = state.exhausted,
                            onRetry = viewModel::loadMore,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveIntro() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Nothing here was thrown away", style = MaterialTheme.typography.titleMedium)
        Text(
            "When several apps record the same workout, the one you trust most represents it " +
                "in your feed and the rest wait here with everything they measured. Restore " +
                "any of them and that choice outranks every later sync.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyArchive(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            if (message == null) "Your archive is empty" else "Could not load your archive",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            message ?: "Every workout you have is representing itself in the feed. Recordings " +
                "appear here when two apps report the same session, or when you set one aside " +
                "by hand.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // An empty archive and an archive that would not load look identical otherwise, and one
        // of those two says nothing was set aside when something was.
        if (message != null) TextButton(onClick = onRetry) { Text("Try again") }
    }
}

/**
 * The end of the list, or the reason there is no end yet.
 *
 * A page that failed has to say so here: the list above is still complete as far as it goes, and
 * silently stopping would read as "that is everything" when it is not.
 */
@Composable
private fun ArchiveFooter(error: String?, exhausted: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            when {
                error != null -> "$error Everything already listed is still set aside, intact."
                exhausted -> "That is everything currently set aside."
                else -> "Loading more…"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (error != null) TextButton(onClick = onRetry) { Text("Try again") }
    }
}

/**
 * One recording that is not currently representing its workout.
 *
 * Restoring is a `PATCH` that flips visibility and locks it. The card then stays exactly where
 * it is, showing what happened and offering the way back, because a row that disappeared would
 * look like the thing the athlete was promised never happens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArchivedCard(
    activity: ArchivedActivityDto,
    units: UnitSystem,
    cardState: CardState,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onSetAside: () -> Unit,
) {
    val restored = cardState == CardState.RESTORED
    val busy = cardState == CardState.WORKING
    val alsoBy = Labels.alsoRecordedBy(activity.sourceCount)
    val lock = Labels.lockNote(activity.visibilityLocked || restored)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (restored) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                "${Format.sportLabel(activity.sport)} · " +
                    Format.localDate(activity.startTime, activity.tzOffsetMinutes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The title is the way into the workout itself: an athlete deciding whether to
            // restore a recording will want to look at what it actually measured first.
            TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) {
                Text(activity.title, style = MaterialTheme.typography.titleLarge)
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Badge(Labels.sourceLabel(activity.sourcePackage))
                if (alsoBy != null) Badge(alsoBy, accent = true)
            }

            Text(
                Labels.archivedBecause(activity.archivedReason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Stat("Distance", Format.distance(activity.distanceM, units))
                Stat(
                    "Time",
                    Format.duration(
                        activity.movingSeconds?.takeIf { it > 0 } ?: activity.elapsedSeconds,
                    ),
                )
                Stat(
                    if (Format.usesSpeed(activity.sport)) "Avg speed" else "Avg pace",
                    Format.paceOrSpeed(activity.avgSpeedMps, activity.sport, units),
                )
                activity.elevationGainM?.let { Stat("Elevation", Format.elevation(it, units)) }
                activity.avgHrBpm?.let { Stat("Avg HR", "$it bpm") }
            }

            if (cardState == CardState.FAILED) {
                Text(
                    "That did not reach the server. Your archive is unchanged — try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (restored) {
                        "Back in your feed. Later syncs will leave it there."
                    } else {
                        lock ?: "Waiting here with everything it measured."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (restored) {
                    TextButton(onClick = onSetAside, enabled = !busy) { Text("Undo") }
                } else {
                    Button(onClick = onRestore, enabled = !busy) {
                        Text(if (busy) "Restoring…" else "Restore to feed")
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, accent: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (accent) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (accent) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(
        // Read as one phrase — "Distance, 42.19 km" — rather than as two unrelated fragments.
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$label, $value" },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
