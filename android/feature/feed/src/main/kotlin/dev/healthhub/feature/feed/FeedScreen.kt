package dev.healthhub.feature.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.HealthHubType
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.model.UnitSystem
import dev.healthhub.core.network.FeedActivityDto
import dev.healthhub.core.ui.EmptyState
import dev.healthhub.core.ui.ErrorState
import dev.healthhub.core.ui.Format
import dev.healthhub.core.ui.UnitLabels
import dev.healthhub.core.ui.unitLabels
import dev.healthhub.core.ui.HealthHubIcons
import dev.healthhub.core.ui.MetricLabel
import dev.healthhub.core.ui.SkeletonBox
import dev.healthhub.core.ui.SkeletonLine
import dev.healthhub.core.ui.SkeletonPill
import dev.healthhub.core.ui.sportAndTitle
import dev.healthhub.core.ui.R as CoreR

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

    LaunchedEffect(shouldLoadMore, state.exhausted, state.pageError) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            /*
             * No overflow menu.
             *
             * It used to hold Sources, Archive, Updates and About — built from
             * `NavContribution.menuEntries`, which was the point: only a module that registered
             * a screen can offer a way into one. Then the settings screen was built and it
             * consumed the same registry, so every one of those destinations was offered
             * twice: once behind three dots here, once in a card there. A destination offered
             * twice is one time too many, and of the two places the three-dot menu is the one
             * an app puts things it hopes you will not need. The registry is unchanged and
             * still has exactly one consumer.
             */
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.feed_title),
                        style = HealthHubType.titleLargeEmphasized,
                    )
                },
            )
        },
        floatingActionButton = {
            // Sync is the one thing an athlete comes to this screen to *do*, and it used to be
            // a text button in the app bar competing with an overflow. A FAB is where Material
            // puts the screen's action, and an extended one says which action it is.
            ExtendedFloatingActionButton(
                onClick = onOpenSync,
                icon = { Icon(HealthHubIcons.SyncNow, contentDescription = null) },
                text = { Text(stringResource(R.string.feed_sync)) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh(fromPull = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                state.activities.isEmpty() && state.loading -> FeedSkeleton()

                state.activities.isEmpty() && state.error != null -> ErrorState(
                    icon = HealthHubIcons.Offline,
                    title = stringResource(R.string.feed_error_title),
                    message = state.error.orEmpty(),
                    onRetry = { viewModel.refresh() },
                    modifier = Modifier.align(Alignment.Center),
                )

                state.activities.isEmpty() -> EmptyState(
                    icon = HealthHubIcons.Activities,
                    title = stringResource(R.string.feed_empty_title),
                    body = stringResource(R.string.feed_empty_body),
                    actionLabel = stringResource(R.string.feed_empty_action),
                    onAction = onOpenSync,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> ActivityList(
                    state = state,
                    listState = listState,
                    onOpenActivity = onOpenActivity,
                    onRetryPage = viewModel::retryPage,
                )
            }
        }
    }
}

@Composable
private fun ActivityList(
    state: FeedUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenActivity: (String) -> Unit,
    onRetryPage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Above the list rather than inside it: it is a statement about the whole feed, and a
        // banner that scrolls away takes the explanation for stale figures with it.
        AnimatedVisibility(
            visible = state.offline,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            OfflineBanner()
        }

        LazyColumn(
            state = listState,
            // A phone is 400 dp wide and a tablet is not. Cards that stretch to a foldable's
            // full width put the distance and the date at opposite ends of the screen, which is
            // two saccades to read one card.
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth()
                .widthIn(max = CONTENT_MAX_WIDTH),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.lg,
                // Room under the last card for the FAB, which otherwise covers it.
                bottom = Spacing.xxxl + Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(state.activities, key = { it.id }) { activity ->
                ActivityCard(
                    activity = activity,
                    units = state.units,
                    onClick = { onOpenActivity(activity.id) },
                )
            }

            if (state.loadingMore) {
                item(key = "loading-more") { ActivityCardSkeleton() }
            }

            state.pageError?.let { message ->
                item(key = "page-error") {
                    // Under the last card, not over the list: everything above it is real and
                    // still worth reading. The button is what un-latches paging — without one,
                    // a single dropped connection ends the feed for the rest of the session.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.feed_page_error, message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRetryPage) {
                            Text(stringResource(CoreR.string.action_try_again))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HealthHubIcons.Offline,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            stringResource(R.string.feed_offline),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * how six figures now fit where four used to. They come from `core:ui` so that the same quantity
 * is the same mark here, on the detail screen, and in the browser.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityCard(
    activity: FeedActivityDto,
    units: UnitSystem,
    onClick: () -> Unit,
) {
    // Resolved once per card rather than per figure: twelve resource lookups is twelve, and
    // this runs for every row in a list an athlete scrolls through a month of.
    val labels = unitLabels()
    val distance = Format.distance(activity.distanceM, units, labels)
    val date = Format.localDate(activity.startTime, activity.tzOffsetMinutes)
    val heading = sportAndTitle(activity.sport, activity.title)
    val description = stringResource(R.string.feed_card_description, heading, date, distance)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            // One node, one announcement. A card whose six figures are each their own stop is
            // six swipes to skip a workout the athlete is not looking for; the summary below is
            // what a sighted reader takes from the same card at a glance.
            .clearAndSetSemantics { contentDescription = description },
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
                Icon(
                    HealthHubIcons.forSport(activity.sport),
                    contentDescription = null,
                    modifier = Modifier.size(SPORT_ICON_SIZE),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(distance, style = HealthHubType.titleLargeEmphasized)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Two things worth knowing before opening the card, and both are one glyph: this
                // workout has a track to look at, and more than one app recorded it.
                if (activity.sourceCount > 1) {
                    Icon(
                        HealthHubIcons.MultipleSources,
                        contentDescription = null,
                        modifier = Modifier.size(BADGE_ICON_SIZE),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                if (activity.hasGps) {
                    Icon(
                        HealthHubIcons.Route,
                        contentDescription = null,
                        modifier = Modifier.size(BADGE_ICON_SIZE),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                heading,
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
                MetricLabel(
                    HealthHubIcons.Duration,
                    Format.duration(
                        activity.movingSeconds?.takeIf { it > 0 } ?: activity.elapsedSeconds,
                    ),
                    name = stringResource(CoreR.string.metric_moving_time),
                )
                MetricLabel(
                    HealthHubIcons.Pace,
                    // The sport set that decides pace-or-speed lives in `core:ui` now. This
                    // screen's own copy of it was missing swimming, so a swim read as a pace
                    // here and as a speed on the screen this card opens.
                    Format.paceOrSpeed(activity.avgSpeedMps, activity.sport, units, labels),
                    name = stringResource(
                        if (Format.usesSpeed(activity.sport)) {
                            CoreR.string.metric_average_speed
                        } else {
                            CoreR.string.metric_average_pace
                        },
                    ),
                )
                activity.avgHrBpm?.let {
                    MetricLabel(
                        HealthHubIcons.HeartRate,
                        Format.heartRate(it.toDouble(), labels),
                        name = stringResource(CoreR.string.metric_average_hr),
                    )
                }
                activity.elevationGainM?.takeIf { it >= 1 }?.let {
                    MetricLabel(
                        HealthHubIcons.Elevation,
                        "+${Format.elevation(it, units, labels)}",
                        name = stringResource(CoreR.string.metric_elevation),
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------------------ loading */

/**
 * The feed before it has any workouts in it.
 *
 * Six cards' worth of placeholder rather than a spinner. The feed is the cold-start screen, so
 * this is the first thing the app ever draws — and a centred spinner on an empty screen is
 * indistinguishable from an app that has hung.
 */
@Composable
private fun FeedSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = CONTENT_MAX_WIDTH)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        repeat(SKELETON_CARDS) { ActivityCardSkeleton() }
    }
}

/** One placeholder card, in the layout of the card it becomes. */
@Composable
private fun ActivityCardSkeleton() {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                SkeletonBox(modifier = Modifier.size(SPORT_ICON_SIZE))
                Spacer(Modifier.width(Spacing.sm))
                SkeletonPill(width = 96.dp, height = 24.dp)
                Spacer(Modifier.width(Spacing.sm))
                SkeletonPill(width = 108.dp, height = 12.dp)
            }
            SkeletonLine(widthFraction = 0.5f, height = 14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                SkeletonPill(width = 56.dp, height = 14.dp)
                SkeletonPill(width = 72.dp, height = 14.dp)
                SkeletonPill(width = 60.dp, height = 14.dp)
            }
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}

private const val SKELETON_CARDS = 6

/** Sized to the label beside it rather than to the touch grid: nothing here is tappable. */
private val BADGE_ICON_SIZE = 16.dp
private val SPORT_ICON_SIZE = 20.dp

/** Wide enough for a card to read as one object, narrow enough to read in one glance. */
private val CONTENT_MAX_WIDTH = 720.dp
