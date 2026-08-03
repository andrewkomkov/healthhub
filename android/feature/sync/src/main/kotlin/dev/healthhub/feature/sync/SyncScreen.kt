package dev.healthhub.feature.sync

import android.content.Intent
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.HealthHubProgressBar
import dev.healthhub.core.designsystem.HealthHubType
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.model.SyncStatus
import dev.healthhub.core.sync.SyncEngine
import dev.healthhub.core.ui.HealthHubIcons
import dev.healthhub.core.ui.SectionCard
import dev.healthhub.core.ui.R as CoreR
import dev.healthhub.core.ui.StatTile

/**
 * Where an athlete grants access, watches a sync happen, and reads what it did.
 *
 * The screen is a stack of *cards*, one per question — can we read anything, is anything
 * running, how should it behave, what happened last time. It used to be a flat column of
 * full-width buttons with two paragraphs between them, which gave a permission grant, a
 * settings shortcut and "Sync now" exactly the same weight; the first two are things you do
 * once and the third is what you came for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.refreshPermissions() }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissions()
        viewModel.scheduleBackground()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.sync_title),
                        style = HealthHubType.titleLargeEmphasized,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HealthHubIcons.Back, contentDescription = stringResource(CoreR.string.action_back))
                    }
                },
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
            Column(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth()
                    .widthIn(max = CONTENT_MAX_WIDTH),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                when (state.availability) {
                    HealthConnectSource.Availability.UNAVAILABLE -> SectionCard(
                        title = stringResource(R.string.hc_unavailable_title),
                    ) {
                        Body(stringResource(R.string.hc_unavailable_body))
                    }

                    HealthConnectSource.Availability.UPDATE_REQUIRED -> SectionCard(
                        title = stringResource(R.string.hc_update_title),
                    ) {
                        Body(stringResource(R.string.hc_update_body))
                    }

                    HealthConnectSource.Availability.AVAILABLE -> {
                        AccessCard(
                            missing = state.missingPermissions,
                            onRequest = { permissionLauncher.launch(viewModel.permissions) },
                            onOpenSettings = {
                                // Route access is sometimes only grantable from Health
                                // Connect's own screens rather than the in-app dialog, so this
                                // is the way out of that corner rather than a dead end.
                                runCatching {
                                    context.startActivity(
                                        Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
                                    )
                                }
                            },
                        )

                        Button(
                            onClick = viewModel::syncNow,
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (state.running) R.string.syncing else R.string.sync_now,
                                ),
                            )
                        }
                    }
                }

                (state.progress as? SyncEngine.Progress.Running)?.let { running ->
                    ProgressCard(running)
                }

                SectionCard(title = stringResource(R.string.how_it_syncs)) {
                    SwitchRow(
                        title = stringResource(R.string.wifi_only),
                        body = stringResource(R.string.wifi_only_body),
                        checked = state.unmeteredOnly,
                        onCheckedChange = viewModel::setUnmeteredOnly,
                    )
                }

                state.lastReport?.let { report ->
                    ReportCard(report, accessMissing = state.missingPermissions.isNotEmpty())
                }
            }
        }
    }
}

@Composable
private fun AccessCard(
    missing: Set<String>,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    SectionCard(
        title = stringResource(
            if (missing.isEmpty()) R.string.access_granted else R.string.access_needed,
        ),
        // What is missing, in words. A count — "3 permissions still needed" — tells an athlete
        // nothing they can act on, but the constants are not the answer either: this card read
        // "READ_HEALTH_DATA_IN_BACKGROUND, READ_TOTAL_CALORIES_BURNED, …" on a Pixel, which is
        // a developer's output pointed at somebody who just wanted their rides.
        subtitle = if (missing.isEmpty()) {
            null
        } else {
            missing.map { stringResource(readablePermission(it)) }
                .distinct()
                .sorted()
                .joinToString(", ")
        },
    ) {
        Body(stringResource(R.string.access_body))

        // Always offered, not only when something is missing: routes in particular cannot be
        // pre-granted by tooling and are easy to skip on the first pass, so asking again must
        // always be one tap away.
        OutlinedButton(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (missing.isEmpty()) R.string.access_review else R.string.access_grant,
                ),
            )
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open_hc_settings))
        }
    }
}

/**
 * `android.permission.health.READ_TOTAL_CALORIES_BURNED` → "calories".
 *
 * A map rather than a prettified constant, because the two do not always correspond: several
 * permissions are one idea to an athlete (`READ_HEALTH_DATA_IN_BACKGROUND` and
 * `READ_HEALTH_DATA_HISTORY` are both "your older workouts, while the app is closed"), and a
 * mechanical de-underscoring would say "read total calories burned", which is not English.
 * Anything unlisted falls back to the de-underscored constant rather than being dropped —
 * Principle VI: a permission the app cannot name is still a permission it is asking for.
 */
@StringRes
private fun readablePermission(permission: String): Int =
    when (permission.substringAfterLast('.')) {
        "READ_EXERCISE" -> R.string.perm_workouts
        "READ_EXERCISE_ROUTES" -> R.string.perm_routes
        "READ_HEART_RATE" -> R.string.perm_hr
        "READ_SPEED" -> R.string.perm_speed
        "READ_POWER" -> R.string.perm_power
        "READ_STEPS" -> R.string.perm_steps
        "READ_DISTANCE" -> R.string.perm_distance
        "READ_ELEVATION_GAINED" -> R.string.perm_elevation
        "READ_TOTAL_CALORIES_BURNED", "READ_ACTIVE_CALORIES_BURNED" -> R.string.perm_calories
        "READ_SLEEP" -> R.string.perm_sleep
        "READ_HEART_RATE_VARIABILITY" -> R.string.perm_hrv
        "READ_RESTING_HEART_RATE" -> R.string.perm_resting_hr
        "READ_OXYGEN_SATURATION" -> R.string.perm_spo2
        "READ_WEIGHT" -> R.string.perm_weight
        "READ_BODY_FAT" -> R.string.perm_body_fat
        "READ_BLOOD_PRESSURE" -> R.string.perm_blood_pressure
        // No comma inside the phrase: these are joined into a comma-separated list, and
        // "workouts, your older workouts, in the background" reads as three items rather
        // than two. Seen on a Pixel, which is the only way that kind of thing is ever seen.
        "READ_HEALTH_DATA_IN_BACKGROUND", "READ_HEALTH_DATA_HISTORY" -> R.string.perm_history
        // A permission the app cannot name is still a permission it is asking for, so an
        // unlisted one falls through to the constant rather than disappearing (Principle VI).
        else -> R.string.perm_unnamed
    }

@Composable
private fun ProgressCard(running: SyncEngine.Progress.Running) {
    SectionCard(title = stringResource(R.string.sync_progress_title)) {
        Text(running.stage, style = MaterialTheme.typography.bodyMedium)
        if (running.total > 0) {
            HealthHubProgressBar(
                progress = { running.done.toFloat() / running.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.sync_progress_count, running.done, running.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // No total to report, so no fraction is drawn. A bar that invents one is worse
            // than an indeterminate bar: it promises an end that nothing is counting towards.
            HealthHubProgressBar(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ReportCard(report: dev.healthhub.core.model.SyncReport, accessMissing: Boolean) {
    // A sync that failed with permissions outstanding failed *for that reason*, and the card
    // knows it without having to read the exception: the screen above it is already reporting
    // what is missing. Saying so is the difference between an answer and a stack trace.
    val failedForAccess = report.status == SyncStatus.FAILED && accessMissing

    SectionCard(
        title = stringResource(R.string.last_sync),
        subtitle = stringResource(
            when {
                failedForAccess -> R.string.last_sync_failed_access
                report.status == SyncStatus.OK -> R.string.last_sync_completed
                report.status == SyncStatus.PARTIAL -> R.string.last_sync_partial
                report.status == SyncStatus.FAILED -> R.string.last_sync_failed
                else -> R.string.last_sync_running
            },
        ),
        spacing = Spacing.md,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile(
                HealthHubIcons.Activities,
                stringResource(R.string.report_workouts),
                report.sessionsSynced.toString(),
                Modifier.weight(1f),
            )
            StatTile(
                HealthHubIcons.HeartRate,
                stringResource(R.string.report_samples),
                report.samplesSynced.toString(),
                Modifier.weight(1f),
            )
        }

        // The raw message is a Java exception when the platform refused, and printing
        // `android.health.connect.HealthConnectException: java.lang.SecurityException: …` at an
        // athlete is not reporting, it is leaking. It is still shown when it is the only thing
        // that explains the failure.
        report.message?.takeIf { !failedForAccess }?.let { Body(it) }

        // Principle VI: anything the app could not handle is named here rather than silently
        // dropped. Silence is the failure mode this whole card exists to prevent.
        if (report.unhandledTypes.isNotEmpty()) {
            Text(
                stringResource(R.string.report_unhandled, report.unhandledTypes.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (report.failures.isNotEmpty()) {
            Text(
                stringResource(R.string.report_failures, report.failures.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The whole row toggles: a switch at the right-hand edge is the hardest target on the screen. */
@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row owns the gesture and the semantics; the switch is the picture of the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

private val CONTENT_MAX_WIDTH = 720.dp
