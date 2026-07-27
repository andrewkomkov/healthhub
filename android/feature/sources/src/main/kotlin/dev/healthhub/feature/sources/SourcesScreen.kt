package dev.healthhub.feature.sources

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.network.SourceDto
import kotlin.math.roundToInt

/**
 * Which apps the athlete trusts, most trusted first.
 *
 * Health Connect is a hub, and the same ride arrives from the bike computer's own app, from
 * Strava and from Google Fit. The order stored here is what the phone applies on the next sync
 * when it decides which of those recordings represents the workout.
 *
 * Reordering works two ways deliberately. A long press on the handle picks a row up and drags
 * it; the same row also carries move buttons, because a drag is not available to someone using
 * TalkBack or switch access. Both paths call the same [movedItem], so the accessible path is
 * covered by the same tests as the gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourcesScreen(
    onOpenArchive: () -> Unit,
    onBack: () -> Unit,
    viewModel: SourcesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = onOpenArchive) { Text("Archive") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    "Whose numbers do you believe?",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "When several apps record the same workout, the one highest in this list " +
                        "represents it in your feed. The others wait in the archive with " +
                        "everything they measured, and you can restore any of them for a single " +
                        "workout at any time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Press and hold a handle to drag a source, or use its move buttons.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.loadError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = viewModel::load) { Text("Try again") }
            }

            if (state.save == SaveState.FAILED) {
                Text(
                    "That change did not reach the server, so the list below is what is " +
                        "actually stored. Try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when {
                state.loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)

                state.sources.isNotEmpty() -> SourceList(
                    sources = state.sources,
                    onMove = viewModel::move,
                    onCommit = viewModel::commit,
                    onToggle = viewModel::toggle,
                )

                // Sources are discovered by a sync, never created here. Saying so is the
                // difference between an empty screen and a broken one.
                state.loadError == null -> Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text("No sources yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Apps appear here once your phone has seen them writing to Health " +
                            "Connect. Run a sync and come back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Unit
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    when (state.save) {
                        SaveState.SAVING -> "Saving…"
                        SaveState.SAVED -> "Saved. The next sync will apply it."
                        SaveState.FAILED -> ""
                        SaveState.IDLE -> "Your phone applies this order the next time it syncs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "A source you turn off is still ingested — its recordings go to the archive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Every reorder is spoken here: a list visually rearranging is not an event a
                // screen reader can observe on its own.
                Text(
                    state.announcement,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/**
 * A plain [Column] rather than a `LazyColumn`: this list is the apps on one phone that write
 * to Health Connect, which is a handful, and a drag that has to work across recycled rows is a
 * great deal of machinery to carry for six items.
 */
@Composable
private fun SourceList(
    sources: List<SourceDto>,
    onMove: (Int, Int) -> Unit,
    onCommit: () -> Unit,
    onToggle: (Int) -> Unit,
) {
    // The gesture handler is created once per row and never restarted, so it must not read the
    // list it was composed with — by the third swap during one drag that list is two orders old.
    val latest by rememberUpdatedState(sources)
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var rowHeight by remember { mutableStateOf(0) }
    val gapPx = with(LocalDensity.current) { Spacing.sm.roundToPx() }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        sources.forEachIndexed { index, source ->
            key(source.packageName) {
                val dragging = draggingKey == source.packageName

                SourceRow(
                    source = source,
                    index = index,
                    total = sources.size,
                    dragging = dragging,
                    offsetY = if (dragging) dragOffset else 0f,
                    onMeasured = { height -> rowHeight = height },
                    onToggle = { onToggle(index) },
                    onMoveUp = { onMove(index, stepTo(index, -1, sources.size)) },
                    onMoveDown = { onMove(index, stepTo(index, 1, sources.size)) },
                    handleModifier = Modifier.pointerInput(source.packageName) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingKey = source.packageName
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                draggingKey = null
                                dragOffset = 0f
                                onCommit()
                            },
                            onDragCancel = {
                                draggingKey = null
                                dragOffset = 0f
                                onCommit()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                // One measured row plus the gap between two of them. Every row
                                // reports its height, and they are the same shape, so whichever
                                // measured last is the step for all of them.
                                val step = rowHeight + gapPx
                                val list = latest
                                val from =
                                    list.indexOfFirst { it.packageName == source.packageName }
                                if (step > 0 && from >= 0) {
                                    val target =
                                        stepTo(from, (dragOffset / step).roundToInt(), list.size)
                                    if (target != from) {
                                        onMove(from, target)
                                        // The row has physically moved that many slots, so the
                                        // finger's offset from its new home shrinks by as much.
                                        dragOffset -= (target - from) * step
                                    }
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: SourceDto,
    index: Int,
    total: Int,
    dragging: Boolean,
    offsetY: Float,
    onMeasured: (Int) -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    handleModifier: Modifier,
) {
    val name = Labels.sourceLabel(source.packageName, source.label)
    val position = "position ${index + 1} of $total"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = offsetY }
            .onSizeChanged { onMeasured(it.height) },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (dragging) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = handleModifier
                        .sizeIn(minWidth = Spacing.xxxl, minHeight = Spacing.xxxl)
                        .semantics {
                            contentDescription =
                                "Reorder $name, $position. Press and hold to drag, or use the " +
                                    "move up and move down buttons below."
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "⋮⋮",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${source.packageName} · ${Labels.activityCount(source.activityCount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = source.enabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.semantics {
                        contentDescription =
                            "Use $name when choosing which recording represents a workout"
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (source.enabled) "Considered when choosing" else "Ignored when choosing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onMoveUp, enabled = index > 0) { Text("Move up") }
                TextButton(onClick = onMoveDown, enabled = index < total - 1) { Text("Move down") }
            }
        }
    }
}
