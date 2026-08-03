package dev.healthhub.core.designsystem

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's navigation bar.
 *
 * Expressive's *short* navigation bar rather than the classic one: it is shorter, which matters
 * on a screen whose content is a scrolling feed, and its selected indicator is the shape the
 * rest of the app is drawn in. Both APIs are experimental, which is exactly why they are wrapped
 * here — the opt-in stays inside this module, so an alpha bump that renames one of them is a
 * one-file change rather than a change in every screen (see the note in [HealthHubTheme]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HealthHubNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ShortNavigationBar(modifier = modifier, content = content)
}

/**
 * The same bar, stood on its end.
 *
 * A bottom bar plus a floating action button eats a third of a landscape phone, and the screen
 * underneath is a scrolling list — which is the shape Material answers with a *rail*. The web
 * client has done this since it got a navigation surface at all (`AppShell`'s 48 rem breakpoint);
 * this is the phone's half of the same decision, and `HealthHubNavigationRailItem` takes the
 * same arguments as the bar's item so the caller writes the list once.
 */
@Composable
fun HealthHubNavigationRail(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    NavigationRail(modifier = modifier, content = content)
}

@Composable
fun HealthHubNavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = label,
        modifier = modifier,
    )
}

/** One destination in [HealthHubNavigationBar]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HealthHubNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    ShortNavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = label,
        modifier = modifier,
    )
}
