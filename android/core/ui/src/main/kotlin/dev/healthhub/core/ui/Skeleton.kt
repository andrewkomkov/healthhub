package dev.healthhub.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A placeholder in the shape of the thing it is waiting for.
 *
 * A centred spinner tells the athlete that something is happening and nothing about what.
 * A skeleton in the layout of the cards it becomes says "a list of workouts is coming", holds
 * the scroll position the real content will occupy, and means the screen does not jump when it
 * arrives. That is the whole argument for it, and it is a layout argument rather than a
 * decorative one.
 *
 * Every skeleton is hidden from accessibility. A screen reader announcing eight identical
 * "loading" boxes is worse than silence — the screen that owns them says "Loading activities"
 * once, in words, and that is the thing worth hearing.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(shimmerBrush())
            .semantics { hideFromAccessibility() },
    )
}

/** A line of text that has not arrived. Width is a fraction, so it reflows like the real thing. */
@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 16.dp,
) {
    SkeletonBox(
        modifier = modifier.fillMaxWidth(widthFraction).height(height),
        shape = MaterialTheme.shapes.extraSmall,
    )
}

/** A figure that has not arrived, sized to the label it sits beside rather than to the text. */
@Composable
fun SkeletonPill(width: Dp, height: Dp = 16.dp, modifier: Modifier = Modifier) {
    SkeletonBox(
        modifier = modifier.width(width).height(height),
        shape = MaterialTheme.shapes.extraSmall,
    )
}

/**
 * The sweep every skeleton on screen shares.
 *
 * One infinite transition per composable is fine here — they are all driven by the same clock,
 * so the sweep lines up across a list without anything being threaded between the items.
 */
@Composable
private fun shimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainer

    val transition = rememberInfiniteTransition(label = "skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Slow enough to read as breathing rather than as a fault. A fast shimmer on a
            // full screen of placeholders is genuinely unpleasant to look at, and for someone
            // sensitive to motion it is worse than unpleasant.
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-sweep",
    )

    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset.Zero,
        end = Offset(x = 400f * progress + 200f, y = 200f),
    )
}
