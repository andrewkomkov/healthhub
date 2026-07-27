package dev.healthhub.core.designsystem

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Material You, and the bridge that carries it to the web client.
 *
 * Android 12+ derives a colour scheme from the athlete's wallpaper. HealthHub uses it on the
 * phone and — this is the part that is unusual — uploads the resolved roles so the browser
 * can wear the same personalised scheme. The two clients then look like one product on that
 * specific athlete's devices, rather than merely like the same design system.
 *
 * Only UI roles travel. The chart series palette stays as generated: it is validated for
 * colour-vision deficiency and contrast against both surfaces, and a palette extracted from
 * a wallpaper carries no such guarantee. Personalisation stops where legibility of data
 * begins.
 */
object DynamicColors {

    /** Material You needs Android 12; the SM-G780F on Android 13 has it. */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun lightScheme(context: Context): ColorScheme? =
        if (isSupported) dynamicLightColorScheme(context) else null

    fun darkScheme(context: Context): ColorScheme? =
        if (isSupported) dynamicDarkColorScheme(context) else null

    /**
     * Flattens a scheme into the role → #RRGGBB map the Worker stores and the web applies.
     *
     * The role names match the token pipeline exactly; the Worker rejects anything it does
     * not recognise, so a rename here fails loudly rather than silently dropping a colour.
     */
    fun toRoleMap(scheme: ColorScheme): Map<String, String> = mapOf(
        "primary" to scheme.primary.hex(),
        "onPrimary" to scheme.onPrimary.hex(),
        "primaryContainer" to scheme.primaryContainer.hex(),
        "onPrimaryContainer" to scheme.onPrimaryContainer.hex(),
        "secondary" to scheme.secondary.hex(),
        "onSecondary" to scheme.onSecondary.hex(),
        "secondaryContainer" to scheme.secondaryContainer.hex(),
        "onSecondaryContainer" to scheme.onSecondaryContainer.hex(),
        "tertiary" to scheme.tertiary.hex(),
        "onTertiary" to scheme.onTertiary.hex(),
        "tertiaryContainer" to scheme.tertiaryContainer.hex(),
        "onTertiaryContainer" to scheme.onTertiaryContainer.hex(),
        "error" to scheme.error.hex(),
        "onError" to scheme.onError.hex(),
        "errorContainer" to scheme.errorContainer.hex(),
        "onErrorContainer" to scheme.onErrorContainer.hex(),
        "background" to scheme.background.hex(),
        "onBackground" to scheme.onBackground.hex(),
        "surface" to scheme.surface.hex(),
        "onSurface" to scheme.onSurface.hex(),
        "surfaceVariant" to scheme.surfaceVariant.hex(),
        "onSurfaceVariant" to scheme.onSurfaceVariant.hex(),
        "surfaceContainerLowest" to scheme.surfaceContainerLowest.hex(),
        "surfaceContainerLow" to scheme.surfaceContainerLow.hex(),
        "surfaceContainer" to scheme.surfaceContainer.hex(),
        "surfaceContainerHigh" to scheme.surfaceContainerHigh.hex(),
        "surfaceContainerHighest" to scheme.surfaceContainerHighest.hex(),
        "surfaceDim" to scheme.surfaceDim.hex(),
        "surfaceBright" to scheme.surfaceBright.hex(),
        "outline" to scheme.outline.hex(),
        "outlineVariant" to scheme.outlineVariant.hex(),
        "inverseSurface" to scheme.inverseSurface.hex(),
        "inverseOnSurface" to scheme.inverseOnSurface.hex(),
        "inversePrimary" to scheme.inversePrimary.hex(),
        "scrim" to scheme.scrim.hex(),
    )

    /** Both schemes as maps, or null when the device cannot produce them. */
    fun extract(context: Context): Pair<Map<String, String>, Map<String, String>>? {
        val light = lightScheme(context) ?: return null
        val dark = darkScheme(context) ?: return null
        return toRoleMap(light) to toRoleMap(dark)
    }

    private fun Color.hex(): String = String.format("#%06X", 0xFFFFFF and toArgb())
}
