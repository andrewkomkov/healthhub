package dev.healthhub.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * The single Material 3 Expressive theme for the app.
 *
 * Every value comes from GeneratedTokens, which is built from
 * packages/design-tokens/tokens.json — the same file that produces the web client's CSS.
 * That is what makes Constitution Principle III enforceable rather than aspirational: there
 * is no second place to define a colour, so the two clients cannot drift.
 *
 * Expressive APIs are used only in this file and its neighbours in this module, so an alpha
 * bump is a one-module change.
 */

/** Chart chrome for the current appearance, reached by charts without threading parameters. */
val LocalChartChrome = staticCompositionLocalOf<ChartChrome> {
    error("No ChartChrome — wrap the content in HealthHubTheme")
}

/** True when the dark appearance is active; series colours pick their own step from it. */
val LocalIsDark = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HealthHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You. On by default: the athlete's wallpaper palette is a better fit for their
     * device than ours, and the same scheme is uploaded so the web client can wear it too.
     * The generated palette remains the fallback wherever dynamic colour is unavailable.
     */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicScheme = if (dynamicColor) {
        if (darkTheme) DynamicColors.darkScheme(context) else DynamicColors.lightScheme(context)
    } else {
        null
    }

    val colorScheme = dynamicScheme
        ?: if (darkTheme) GeneratedTokens.darkColorScheme else GeneratedTokens.lightColorScheme

    CompositionLocalProvider(
        LocalChartChrome provides
            if (darkTheme) GeneratedTokens.chartChromeDark else GeneratedTokens.chartChromeLight,
        LocalIsDark provides darkTheme,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            shapes = healthHubShapes(),
            typography = healthHubTypography(),
            // The Expressive motion scheme is what makes transitions feel spring-driven
            // rather than timed; it is the point of adopting Expressive at all.
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

private fun shape(name: String): RoundedCornerShape =
    RoundedCornerShape(GeneratedTokens.shapeScale.getValue(name))

private fun healthHubShapes() = Shapes(
    extraSmall = shape("extraSmall"),
    small = shape("small"),
    medium = shape("medium"),
    large = shape("large"),
    extraLarge = shape("extraLarge"),
)

private fun style(name: String): TextStyle {
    val token = GeneratedTokens.typeScale.getValue(name)
    return TextStyle(
        fontSize = token.size,
        lineHeight = token.lineHeight,
        fontWeight = FontWeight(token.weight),
        letterSpacing = token.tracking,
    )
}

private fun healthHubTypography() = Typography(
    displayLarge = style("displayLarge"),
    displayMedium = style("displayMedium"),
    displaySmall = style("displaySmall"),
    headlineLarge = style("headlineLarge"),
    headlineMedium = style("headlineMedium"),
    headlineSmall = style("headlineSmall"),
    titleLarge = style("titleLarge"),
    titleMedium = style("titleMedium"),
    titleSmall = style("titleSmall"),
    bodyLarge = style("bodyLarge"),
    bodyMedium = style("bodyMedium"),
    bodySmall = style("bodySmall"),
    labelLarge = style("labelLarge"),
    labelMedium = style("labelMedium"),
    labelSmall = style("labelSmall"),
)

/** Emphasised type roles, used for prominence the way the Expressive spec intends. */
object HealthHubType {
    val headlineLargeEmphasized: TextStyle @Composable @ReadOnlyComposable get() = style("headlineLargeEmphasized")
    val titleLargeEmphasized: TextStyle @Composable @ReadOnlyComposable get() = style("titleLargeEmphasized")
    val titleMediumEmphasized: TextStyle @Composable @ReadOnlyComposable get() = style("titleMediumEmphasized")
    val labelLargeEmphasized: TextStyle @Composable @ReadOnlyComposable get() = style("labelLargeEmphasized")
}

/** Spacing scale. Layouts use these rather than literal dp values. */
object Spacing {
    val xs: Dp = GeneratedTokens.Spacing.xs
    val sm: Dp = GeneratedTokens.Spacing.sm
    val md: Dp = GeneratedTokens.Spacing.md
    val lg: Dp = GeneratedTokens.Spacing.lg
    val xl: Dp = GeneratedTokens.Spacing.xl
    val xxl: Dp = GeneratedTokens.Spacing.xxl
    val xxxl: Dp = GeneratedTokens.Spacing.xxxl
}

/**
 * The colour a telemetry channel owns.
 *
 * A channel keeps its slot no matter how many series are on screen — colour follows the
 * entity, never its rank — so filtering a series out never repaints the survivors.
 */
@Composable
fun channelColor(channel: String): Color =
    GeneratedTokens.seriesColor(channel, LocalIsDark.current)

/** Larger corner radii the Expressive scale adds beyond the classic Material shapes. */
object ExpressiveShapes {
    val largeIncreased: RoundedCornerShape get() = shape("largeIncreased")
    val extraLargeIncreased: RoundedCornerShape get() = shape("extraLargeIncreased")
    val extraExtraLarge: RoundedCornerShape get() = shape("extraExtraLarge")
}

internal val UnusedSpMarker = 0.sp
