package dev.healthhub.core.designsystem

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * "Something is happening", drawn the Expressive way.
 *
 * Both APIs are experimental, so both are wrapped here — the opt-in stays inside this module,
 * and an alpha bump that renames one of them is a one-file change rather than a change on every
 * screen that shows progress. Same reason as [HealthHubNavigationBar].
 */

/**
 * A determinate or indeterminate bar for work with a known shape — a sync, an upload.
 *
 * The wavy indicator rather than the flat one, and not for decoration: the wave is *moving*
 * even when the fraction is not, which is the difference between "this is slow" and "this has
 * stopped". A sync that spends ninety seconds on one large activity looks identical to a hung
 * app under a static bar, and that is the moment an athlete force-quits.
 *
 * Pass `progress` when the total is known and omit it when it is not — never fake a fraction.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HealthHubProgressBar(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
) {
    if (progress != null) {
        LinearWavyProgressIndicator(progress = progress, modifier = modifier)
    } else {
        LinearWavyProgressIndicator(modifier = modifier)
    }
}

/**
 * The small indeterminate mark, for a wait with no shape to report.
 *
 * Expressive's loading indicator is a sequence of morphing shapes rather than a spinning arc.
 * Prefer a skeleton in the layout of the thing being waited for wherever one is possible — this
 * is for the cases where the layout genuinely is not known yet.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HealthHubLoadingIndicator(modifier: Modifier = Modifier) {
    LoadingIndicator(modifier = modifier)
}
