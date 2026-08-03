package dev.healthhub.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.healthhub.core.designsystem.DynamicColors
import dev.healthhub.core.designsystem.HealthHubType
import dev.healthhub.core.designsystem.Spacing
import dev.healthhub.core.model.UnitSystem
import dev.healthhub.core.navigation.Destination
import dev.healthhub.core.navigation.LocalNavMenu
import dev.healthhub.core.preferences.ThemeMode
import dev.healthhub.core.ui.HealthHubIcons
import dev.healthhub.core.ui.SectionCard
import dev.healthhub.core.ui.R as CoreR

/**
 * Settings: how the app looks, what it counts in, and who is signed in.
 *
 * The module this lives in existed as a build file and an empty directory for months, while
 * `Destination.Settings` existed in `core:navigation` — so the route was a value any screen
 * could offer and no screen could serve, and offering it threw. That is the failure the menu
 * registry made unrepresentable; this is the one that makes it moot.
 *
 * The screen is also the only way off a phone: there was no sign-out anywhere in the app, only
 * a debug ADB command, so an athlete who signed in on a borrowed device had no way back out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    onOpen: (Destination) -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.messageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        style = HealthHubType.titleLargeEmphasized,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                AppearanceCard(
                    themeMode = state.themeMode,
                    dynamicColor = state.dynamicColor,
                    onThemeMode = viewModel::setThemeMode,
                    onDynamicColor = viewModel::setDynamicColor,
                )

                UnitsCard(units = state.units, onUnits = viewModel::setUnits)

                AccountCard(
                    email = state.email,
                    displayName = state.displayName,
                    signedIn = state.signedIn,
                    signingOut = state.signingOut,
                    onSignOut = { confirmSignOut = true },
                    onSignIn = onSignedOut,
                )

                ElsewhereCard(onOpen = onOpen)
            }
        }
    }

    if (confirmSignOut) {
        SignOutDialog(
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                viewModel.signOut()
            },
        )
    }
}

@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.appearance), spacing = Spacing.md) {
        Choice(
            options = ThemeMode.entries,
            selected = themeMode,
            // Shorter than the sentence form below, which is what a screen reader hears.
            label = { mode -> stringResource(themeShortLabel(mode)) },
            describe = { mode ->
                stringResource(R.string.theme_description, stringResource(themeLongLabel(mode)))
            },
            onSelect = onThemeMode,
        )

        HorizontalDivider()

        SwitchRow(
            title = stringResource(R.string.dynamic_title),
            // Said plainly rather than left as a greyed-out switch with no explanation: on a
            // phone below Android 12 there is no wallpaper palette to read, and a control that
            // simply refuses to move reads as a bug in the app.
            body = stringResource(
                if (DynamicColors.isSupported) R.string.dynamic_body else R.string.dynamic_unsupported,
            ),
            checked = dynamicColor && DynamicColors.isSupported,
            enabled = DynamicColors.isSupported,
            onCheckedChange = onDynamicColor,
        )
    }
}

@Composable
private fun UnitsCard(units: UnitSystem, onUnits: (UnitSystem) -> Unit) {
    SectionCard(
        title = stringResource(R.string.units_title),
        subtitle = stringResource(R.string.units_subtitle),
        spacing = Spacing.md,
    ) {
        Choice(
            options = UnitSystem.entries,
            selected = units,
            label = { system ->
                stringResource(
                    if (system == UnitSystem.IMPERIAL) R.string.units_imperial
                    else R.string.units_metric,
                )
            },
            describe = { system ->
                stringResource(
                    if (system == UnitSystem.IMPERIAL) R.string.units_imperial_description
                    else R.string.units_metric_description,
                )
            },
            onSelect = onUnits,
        )
    }
}

@Composable
private fun AccountCard(
    email: String?,
    displayName: String?,
    signedIn: Boolean,
    signingOut: Boolean,
    onSignOut: () -> Unit,
    onSignIn: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.account), spacing = Spacing.md) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                if (signedIn) {
                    displayName ?: stringResource(R.string.account_signed_in)
                } else {
                    stringResource(R.string.account_not_signed_in)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                // An em dash rather than a spinner while the address is on its way: this card
                // is not what the athlete came for, and everything above it works without it.
                // "Not signed in" is a different sentence and gets a different one.
                if (signedIn) {
                    email ?: stringResource(CoreR.string.em_dash)
                } else {
                    stringResource(R.string.account_not_signed_in_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Pulled back by the button's own text inset so the label lines up with the sentences
        // above it rather than sitting twelve pixels to their right. The touch target keeps
        // its full width; only the ink moves.
        if (signedIn) {
            TextButton(
                onClick = onSignOut,
                enabled = !signingOut,
                modifier = Modifier.offset(x = -TEXT_BUTTON_INSET),
            ) {
                Text(stringResource(if (signingOut) R.string.signing_out else R.string.sign_out))
            }
        } else {
            Button(onClick = onSignIn) { Text(stringResource(R.string.sign_in)) }
        }
    }
}

/**
 * Everything else the app can show, as declared by the modules that serve it.
 *
 * Built from the menu registry rather than from a list of destinations written out here: a
 * screen that names another feature's route can offer one the graph does not contain, which is
 * a crash rather than a dead link. This card cannot — only a module that registered a screen
 * puts an entry in the set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ElsewhereCard(onOpen: (Destination) -> Unit) {
    val entries = LocalNavMenu.current.filter { it.destination != Destination.Settings }
    if (entries.isEmpty()) return

    SectionCard(title = stringResource(R.string.more), spacing = Spacing.xs) {
        entries.forEach { entry ->
            ListItem(
                headlineContent = { Text(stringResource(entry.label)) },
                leadingContent = { Icon(entry.icon, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onOpen(entry.destination) },
            )
        }
    }
}

@Composable
private fun SignOutDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_out_title)) },
        text = { Text(stringResource(R.string.sign_out_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.sign_out)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.action_cancel)) }
        },
    )
}

/* --------------------------------------------------------------------------- primitives */

/**
 * A closed set of choices, as one control.
 *
 * A segmented button rather than three radio rows or a dialog: the options are short, mutually
 * exclusive and few, which is exactly the shape Material gives this control, and the athlete can
 * see what they did not pick without opening anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    describe: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            // Both resolved here rather than inside the semantics block: that block is an
            // ordinary lambda, not a composition, and a resource lookup cannot happen in one.
            val text = label(option)
            val description = describe(option)
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // The visible label is a word out of context — "System", "Miles" — and a screen
                // reader announcing it alone leaves the athlete guessing what it applies to.
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = description
                    role = Role.RadioButton
                    toggleableState =
                        if (option == selected) ToggleableState.On else ToggleableState.Off
                },
            ) {
                Text(text)
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row toggles, not just the switch: a 48 dp target at the right-hand edge
            // is the hardest thing on the screen to hit, and the label beside it is inert.
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            // The row owns the gesture and the semantics; the switch is the picture of the
            // state. Two independently focusable controls for one setting is one too many.
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

/** Wide enough for a setting to read as one row, narrow enough to read in one glance. */
private val CONTENT_MAX_WIDTH = 720.dp

/** `TextButton`'s own horizontal content padding, which is what pushes its label out of line. */
private val TEXT_BUTTON_INSET = 12.dp

/** The word on the segmented button: one word, because there are three of them in a row. */
@StringRes
private fun themeShortLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

/** The sentence form, which is what a screen reader is given instead of the bare word. */
@StringRes
private fun themeLongLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system_long
    ThemeMode.LIGHT -> R.string.theme_light_long
    ThemeMode.DARK -> R.string.theme_dark_long
}
