package dev.healthhub.feature.sync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.healthconnect.HealthConnectSource
import dev.healthhub.core.model.SyncStatus
import dev.healthhub.core.sync.SyncEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.health.connect.client.PermissionController
            .createRequestPermissionResultContract(),
    ) { viewModel.refreshPermissions() }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissions()
        viewModel.scheduleBackground()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                navigationIcon = {
                    androidx.compose.material3.TextButton(onClick = onBack) { Text("Back") }
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
            when (state.availability) {
                HealthConnectSource.Availability.UNAVAILABLE -> Notice(
                    title = "Health Connect is not available",
                    body = "HealthHub reads your workouts from Health Connect. Install or " +
                        "enable it, then come back.",
                )

                HealthConnectSource.Availability.UPDATE_REQUIRED -> Notice(
                    title = "Health Connect needs updating",
                    body = "Update Health Connect from the Play Store to continue.",
                )

                HealthConnectSource.Availability.AVAILABLE -> {
                    if (state.missingPermissions.isNotEmpty()) {
                        Notice(
                            title = "${state.missingPermissions.size} permissions still needed",
                            body = state.missingPermissions
                                .joinToString(", ") { it.substringAfterLast('.') },
                        )
                    }

                    // Always offered, not only when something is missing: routes in
                    // particular cannot be pre-granted by tooling and are easy to skip on
                    // the first pass, so asking again must always be one tap away.
                    Button(
                        onClick = { permissionLauncher.launch(viewModel.permissions) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.missingPermissions.isEmpty()) {
                                "Review Health Connect access"
                            } else {
                                "Grant Health Connect access"
                            },
                        )
                    }

                    Text(
                        "HealthHub asks only for workout data: sessions, GPS routes, heart " +
                            "rate, speed, power, cadence, distance, elevation and calories.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Route access is sometimes only grantable from Health Connect's own
                    // screens rather than the in-app dialog, so this is the way out of that
                    // corner rather than a dead end.
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS",
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Health Connect settings")
                    }

                    Button(
                        onClick = viewModel::syncNow,
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.running) "Syncing…" else "Sync now")
                    }
                }
            }

            (state.progress as? SyncEngine.Progress.Running)?.let { running ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(running.stage, style = MaterialTheme.typography.bodyMedium)
                    if (running.total > 0) {
                        LinearProgressIndicator(
                            progress = { running.done.toFloat() / running.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${running.done} of ${running.total}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sync on Wi-Fi only", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Telemetry for a long ride runs to megabytes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.unmeteredOnly,
                    onCheckedChange = viewModel::setUnmeteredOnly,
                )
            }

            state.lastReport?.let { report ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text("Last sync", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (report.status) {
                                SyncStatus.OK -> "Completed"
                                SyncStatus.PARTIAL -> "Completed with problems"
                                SyncStatus.FAILED -> "Failed"
                                SyncStatus.RUNNING -> "Running"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${report.sessionsSynced} workouts · ${report.samplesSynced} samples",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        report.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }

                        // Principle VI: anything the app could not handle is named here
                        // rather than silently dropped.
                        if (report.unhandledTypes.isNotEmpty()) {
                            Text(
                                "Not handled: ${report.unhandledTypes.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (report.failures.isNotEmpty()) {
                            Text(
                                "${report.failures.size} workouts failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
        }
    }
}
