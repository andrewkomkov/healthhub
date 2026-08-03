package dev.healthhub.feature.updates

import android.text.format.DateUtils
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.Spacing

/**
 * The version this phone is running, and the one GitHub has.
 *
 * There is no store in this architecture, so this screen is the whole update story: it says
 * what is installed, what exists, and installs the difference without the athlete leaving the
 * app or finding a file in Downloads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    onBack: () -> Unit,
    viewModel: UpdatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.updates_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
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
                    stringResource(R.string.updates_current, state.currentVersion),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    lastCheckedLabel(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val update = state.available
            if (update != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            stringResource(R.string.updates_available, update.version),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        update.notes?.let { notes ->
                            Text(
                                // Release notes are generated from commit history and run
                                // long; the head of them is what fits on a card.
                                notes.lineSequence().take(NOTES_LINES).joinToString("\n").trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!state.installsInPlace) {
                            Text(
                                stringResource(R.string.updates_debug_build),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (update.apkUrl == null) {
                            Text(
                                stringResource(R.string.updates_no_apk),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (!state.checking) {
                Text(
                    stringResource(R.string.updates_up_to_date),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.progress?.let { progress -> ProgressRow(progress) }

            state.failure?.let { failure ->
                Text(
                    failure.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failure.severe) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (update != null && state.progress == null) {
                Button(
                    onClick = {
                        viewModel.clearFailure()
                        viewModel.install(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.installsInPlace && update.apkUrl != null) {
                            stringResource(R.string.updates_install, update.version)
                        } else {
                            stringResource(R.string.updates_release_page)
                        },
                    )
                }
            }

            OutlinedButton(
                onClick = viewModel::checkNow,
                enabled = !state.checking && state.progress == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.checking) stringResource(R.string.updates_checking) else stringResource(R.string.updates_check))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.updates_auto), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.updates_auto_body) +
                            "its own server, and it carries nothing about you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.autoCheck, onCheckedChange = viewModel::setAutoCheck)
            }

            TextButton(onClick = { viewModel.openReleasePage(context) }) {
                Text(stringResource(R.string.updates_all_releases))
            }
        }
    }
}

@Composable
private fun ProgressRow(progress: UpdateProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            when (progress.stage) {
                UpdateProgress.Stage.DOWNLOAD -> stringResource(R.string.updates_downloading)
                UpdateProgress.Stage.VERIFY -> stringResource(R.string.updates_verifying)
                UpdateProgress.Stage.INSTALL -> stringResource(R.string.updates_handing_over)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${megabytes(progress.bytes)} of ${megabytes(progress.total)} MB",
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun megabytes(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0)

/**
 * `@Composable` because it reads resources, and the relative time it builds comes from the
 * platform — `DateUtils` already speaks the phone's language, so the only part that needed
 * translating is the word around it.
 */
@Composable
private fun lastCheckedLabel(state: UpdatesUiState): String = when {
    state.checking -> stringResource(R.string.updates_checking_github)
    state.lastCheck == 0L -> stringResource(R.string.updates_never_checked)
    else -> stringResource(
        R.string.updates_checked,
        DateUtils.getRelativeTimeSpanString(
            state.lastCheck,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString(),
    )
}

private const val NOTES_LINES = 12
