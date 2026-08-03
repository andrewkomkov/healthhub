package dev.healthhub.feature.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.ExpressiveShapes
import dev.healthhub.core.designsystem.HealthHubProgressBar
import dev.healthhub.core.designsystem.HealthHubType
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.ui.R as CoreR
import dev.healthhub.core.healthconnect.HealthDomain

/**
 * Health and recovery: last night, the sleep trend, heart-rate variability, resting heart rate
 * and a readiness score derived from all three.
 *
 * Everything on this screen is computed on the device from rows the edge stored verbatim
 * (Principle I). The card that matters most is the last one — the domain switches. This screen
 * is where sleep and recovery are *turned on*, which is why they are not requested at install
 * time (Principle IV), and where the record types HealthHub does not read are named, which is
 * why "my nutrition data is missing" has an answer instead of silence (Principle VI).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthScreen(
    viewModel: HealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The domain whose permission request is in flight. Held here rather than in the view model
    // because it is entirely a property of this screen's dialogue.
    var pending by remember { mutableStateOf<HealthDomain?>(null) }
    val contract = remember(viewModel) { viewModel.permissionContract() }

    val launcher = rememberLauncherForActivityResult(contract) { granted ->
        val domain = pending
        pending = null
        if (domain != null && viewModel.permissionsFor(domain).all { it in granted }) {
            // Only switched on once the grant actually arrived. Turning a switch on that the
            // athlete then declined would leave the screen claiming data it can never read.
            viewModel.setEnabled(domain, true)
            viewModel.syncAndReload()
        } else {
            viewModel.refreshDomains()
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshDomains() }

    // Turning a domain on is three steps that must not be reordered: ask for exactly that
    // domain's permission, switch it on only once the grant arrives, then read the history
    // behind it. Switching on first would leave a screen that promises data it cannot read.
    val onToggle: (HealthDomain, Boolean) -> Unit = { domain, on ->
        when {
            !on -> viewModel.setEnabled(domain, false)
            state.domains.firstOrNull { it.domain == domain }?.granted == true -> {
                viewModel.setEnabled(domain, true)
                viewModel.syncAndReload()
            }
            else -> {
                pending = domain
                launcher.launch(viewModel.permissionsFor(domain))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // No navigation icon: Health is one of the navigation bar's own destinations,
                // and a back arrow on a tab points at whichever screen happened to precede it.
                // The bar is the way between top-level screens; back leaves the app.
                title = {
                    Text(
                        stringResource(R.string.health_title),
                        style = HealthHubType.titleLargeEmphasized,
                    )
                },
                actions = {
                    TextButton(
                        onClick = viewModel::syncAndReload,
                        enabled = !state.syncing,
                    ) {
                        Text(
                            stringResource(
                                if (state.syncing) R.string.health_syncing else R.string.health_sync,
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (state.loading || state.syncing) {
                // The wavy indicator, which keeps moving while the fraction does not: a sleep
                // sync over a year of nights is slow enough that a static bar reads as a hang.
                item { HealthHubProgressBar(modifier = Modifier.fillMaxWidth()) }
            }

            state.error?.let { message ->
                item {
                    Notice(
                        title = stringResource(R.string.health_error_title),
                        body = message,
                        action = stringResource(CoreR.string.action_try_again) to viewModel::load,
                    )
                }
            }

            if (state.availability != HealthConnectSource.Availability.AVAILABLE) {
                item {
                    Notice(
                        title = stringResource(R.string.hc_unavailable_title),
                        body = stringResource(R.string.hc_unavailable_body),
                    )
                }
            }

            item { ReadinessCard(state.readiness) }

            state.lastNight?.let { night -> item { LastNightCard(night) } }

            if (state.nights.size >= 2) {
                item { SleepTrendCard(state.nights) }
            }

            if (state.hrv.size >= 2) {
                item {
                    TrendCard(
                        title = "Heart-rate variability",
                        unit = "ms",
                        days = state.hrv,
                        baseline = state.hrvBaseline,
                        color = hrvColor(),
                        decimals = 0,
                    )
                }
            }

            if (state.restingHr.size >= 2) {
                item {
                    TrendCard(
                        title = "Resting heart rate",
                        unit = "bpm",
                        days = state.restingHr,
                        baseline = state.restingHrBaseline,
                        color = restingHrColor(),
                        decimals = 0,
                    )
                }
            }

            if (state.latest.isNotEmpty()) {
                item { LatestCard(state.latest) }
            }

            item { DomainsCard(state.domains, onToggle) }

            item { NotIngestedCard(state) }
        }
    }
}

/**
 * The readiness card.
 *
 * The number is never shown alone: every component that produced it is listed with the figures
 * it came from, and when there is no baseline the card says what is still missing instead of
 * showing a plausible-looking 50.
 */
@Composable
private fun ReadinessCard(score: Readiness.Score?) {
    Card(modifier = Modifier.fillMaxWidth(), shape = ExpressiveShapes.largeIncreased) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Readiness", style = MaterialTheme.typography.titleMedium)

            val value = score?.value
            if (score == null || value == null) {
                Text(
                    score?.note ?: "Turn on sleep and recovery below to see readiness.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text("$value", style = HealthHubType.headlineLargeEmphasized)
                Text(
                    " / 100",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            score.components.forEach { component ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(component.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            component.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${component.score.toInt()}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Text(
                score.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LastNightCard(night: SleepDto) {
    Card(modifier = Modifier.fillMaxWidth(), shape = ExpressiveShapes.largeIncreased) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Last night", style = MaterialTheme.typography.titleMedium)
                Text(
                    Format.date(night.localDate),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(Format.duration(night.totalSeconds), style = HealthHubType.headlineLargeEmphasized)

            Text(
                buildString {
                    append(Format.timeOfDay(night.startTime, night.tzOffsetMinutes))
                    append(" – ")
                    append(Format.timeOfDay(night.endTime, night.tzOffsetMinutes))
                    night.timeInBedSeconds?.let {
                        append(" · ${Format.duration(it)} in bed")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (night.stageCount > 0) {
                SleepStageBreakdown(night)
            } else {
                // A duration and nothing else is a normal shape for a phone app to write, and
                // saying so is better than an empty band the athlete has to interpret.
                Text(
                    "This source recorded a duration but no sleep stages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SleepTrendCard(nights: List<SleepDto>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = ExpressiveShapes.largeIncreased) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Sleep", style = MaterialTheme.typography.titleMedium)

            val average = nights.map { it.totalSeconds }.average().toLong()
            Text(
                "${Format.duration(average)} a night across ${nights.size} nights",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SleepTrendChart(nights)
            StageLegend(stagesPresent(nights))
        }
    }
}

@Composable
private fun TrendCard(
    title: String,
    unit: String,
    days: List<DayValue>,
    baseline: Double?,
    color: Color,
    decimals: Int,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = ExpressiveShapes.largeIncreased) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)

            val latest = Trends.latest(days)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    latest?.let { "%.${decimals}f".format(it.value) } ?: "—",
                    style = HealthHubType.headlineLargeEmphasized,
                )
                Text(
                    " $unit",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                Format.deviation(Trends.deviationPercent(latest?.value, baseline))
                    ?: "Still building a baseline — ${Trends.MIN_BASELINE_DAYS} days are needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TrendChart(days = days, baseline = baseline, color = color, label = title)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    Format.date(days.first().date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Format.date(days.last().date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LatestCard(readings: List<LatestReading>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = ExpressiveShapes.largeIncreased) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Latest readings", style = MaterialTheme.typography.titleMedium)

            readings.forEach { reading ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(reading.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Format.dateOf(reading.measuredAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${Format.measurement(reading)} ${reading.unit}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

/**
 * The switches that decide what is requested from Health Connect.
 *
 * This is Principle IV as a piece of UI: nothing beyond workouts is asked for until the athlete
 * turns it on here, with the reason for it on the same line as the switch.
 */
@Composable
private fun DomainsCard(
    domains: List<DomainState>,
    onToggle: (HealthDomain, Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = ExpressiveShapes.largeIncreased) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("What HealthHub reads", style = MaterialTheme.typography.titleMedium)
            Text(
                "Nothing beyond workouts is requested until you turn it on. Turning something " +
                    "off stops the next sync reading it; nothing already synced is deleted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            domains.forEach { domain ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(domain.domain.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            domain.domain.purpose,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (domain.enabled && !domain.granted) {
                            // On but not granted is the state that otherwise looks like a bug:
                            // the switch says yes and no data ever appears.
                            Text(
                                "Health Connect has not granted this yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Switch(
                        checked = domain.enabled,
                        // Workouts are the product; a switch that empties the app is not a
                        // preference worth offering.
                        enabled = domain.domain != HealthDomain.ALWAYS_ON,
                        onCheckedChange = { on -> onToggle(domain.domain, on) },
                    )
                }
            }
        }
    }
}

/**
 * Everything Health Connect exposes that this app does not read, by name.
 *
 * Collapsed by default because it is long, and present at all because Principle VI's failure
 * mode is silence: an athlete whose phone is full of nutrition readings should be able to find
 * out that HealthHub does not model them, rather than concluding the sync is broken.
 */
@Composable
private fun NotIngestedCard(state: HealthUiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), shape = ExpressiveShapes.largeIncreased) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text("What HealthHub does not read", style = MaterialTheme.typography.titleMedium)
            Text(
                "${state.notIngested.size} Health Connect record types are not ingested. Every " +
                    "sync report names them too, so nothing is missing without being said.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (expanded) {
                HorizontalDivider()
                state.notIngested.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(entry.typeName, style = MaterialTheme.typography.bodySmall)
                        Text(
                            entry.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f).padding(start = Spacing.md),
                        )
                    }
                }
            }

            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide the list" else "Show the list")
            }
        }
    }
}

@Composable
private fun Notice(
    title: String,
    body: String,
    action: Pair<String, () -> Unit>? = null,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = ExpressiveShapes.largeIncreased) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.let { (label, onClick) ->
                Button(onClick = onClick, modifier = Modifier.padding(top = Spacing.sm)) {
                    Text(label)
                }
            }
        }
    }
}
