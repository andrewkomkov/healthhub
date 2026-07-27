#!/usr/bin/env node
/**
 * Generates the two client-side representations of the design tokens from tokens.json:
 *
 *   web/src/core/m3e/generated-tokens.css
 *   android/core/designsystem/src/main/kotlin/dev/healthhub/core/designsystem/GeneratedTokens.kt
 *
 * Both are generated from the same source so the Android and web clients cannot drift
 * (Constitution Principle III). Neither output is committed — both builds run this first.
 */

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(here, '..', '..')
const tokens = JSON.parse(readFileSync(join(here, 'tokens.json'), 'utf8'))

const BANNER = 'GENERATED FROM packages/design-tokens/tokens.json — DO NOT EDIT'

/* ------------------------------------------------------------------ helpers */

const kebab = (s) => s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()

/** Strip the $comment keys used for documentation inside tokens.json. */
const isDoc = (key) => key.startsWith('$')

/**
 * Type sizes reach the browser in `rem`, never `px`.
 *
 * A px font-size ignores the reader's own default font size — the browser setting someone
 * with low vision has already found and turned up — and only responds to page zoom. The
 * Android side has the same property for free, because `sp` follows the system font scale.
 * Sixteen is the CSS initial font size, so nothing moves for a reader who changed nothing.
 */
const REM_BASE = 16
// Every token divides exactly by sixteen, so no rounding is applied — a rounded rem cannot be
// turned back into the px it came from, and the parity test does exactly that.
const rem = (px) => `${px / REM_BASE}rem`

/* ---------------------------------------------------------------------- CSS */

function buildCss() {
  const lines = [`/* ${BANNER} */`, '']

  const colorVars = (mode) =>
    Object.entries(tokens.color[mode])
      .map(([role, hex]) => `  --md-sys-color-${kebab(role)}: ${hex};`)
      .join('\n')

  const chartVars = (mode) => {
    const out = []
    tokens.chart.series[mode].forEach((hex, i) => {
      out.push(`  --chart-series-${i + 1}: ${hex};`)
    })
    for (const [role, hex] of Object.entries(tokens.chart.chrome[mode])) {
      out.push(`  --chart-${kebab(role)}: ${hex};`)
    }
    for (const [role, hex] of Object.entries(tokens.chart.status[mode])) {
      out.push(`  --chart-status-${kebab(role)}: ${hex};`)
    }
    out.push(`  --chart-diverging-midpoint: ${tokens.chart.diverging.midpoint[mode]};`)
    // The usable span of the sequential ramp on this surface. The palest steps disappear on
    // a light background and the darkest ones disappear on a dark one, so an ordinal scale
    // has to be laid out inside these bounds rather than across all thirteen steps.
    const steps = tokens.chart.sequential.steps.length
    out.push(
      `  --chart-sequential-floor: ${mode === 'light' ? tokens.chart.sequential.ordinalFloorLight : 1};`,
    )
    out.push(
      `  --chart-sequential-ceiling: ${mode === 'dark' ? tokens.chart.sequential.ordinalCeilingDark : steps};`,
    )
    return out.join('\n')
  }

  // Mode-invariant tokens: shape, type, motion, spacing, elevation, status, marks.
  const staticVars = []
  for (const [name, px] of Object.entries(tokens.shape)) {
    staticVars.push(`  --md-sys-shape-${kebab(name)}: ${name === 'full' ? '9999px' : `${px}px`};`)
  }
  for (const [name, spec] of Object.entries(tokens.typography.scale)) {
    const k = kebab(name)
    staticVars.push(`  --md-sys-type-${k}-size: ${rem(spec.size)};`)
    staticVars.push(`  --md-sys-type-${k}-line-height: ${rem(spec.lineHeight)};`)
    staticVars.push(`  --md-sys-type-${k}-weight: ${spec.weight};`)
    staticVars.push(`  --md-sys-type-${k}-tracking: ${rem(spec.tracking)};`)
  }
  staticVars.push(`  --md-sys-type-font-family: ${tokens.typography.fontFamily};`)
  for (const [name, value] of Object.entries(tokens.motion.cssFallback)) {
    if (isDoc(name)) continue
    staticVars.push(`  --md-sys-motion-${kebab(name)}: ${value};`)
  }
  for (const [name, px] of Object.entries(tokens.spacing)) {
    staticVars.push(`  --hh-space-${kebab(name)}: ${px}px;`)
  }
  for (const [name, dp] of Object.entries(tokens.elevation)) {
    staticVars.push(`  --md-sys-elevation-${kebab(name)}: ${dp}px;`)
  }
  for (const [name, px] of Object.entries(tokens.chart.marks)) {
    if (isDoc(name)) continue
    staticVars.push(`  --chart-mark-${kebab(name)}: ${px}px;`)
  }
  tokens.chart.sequential.steps.forEach((hex, i) => {
    staticVars.push(`  --chart-sequential-${i + 1}: ${hex};`)
  })
  staticVars.push(`  --chart-diverging-negative: ${tokens.chart.diverging.negative};`)
  staticVars.push(`  --chart-diverging-positive: ${tokens.chart.diverging.positive};`)
  // A channel's colour is looked up by name, not by the order it happens to be plotted in,
  // so hiding heart rate never repaints power. These indirect through the series variables
  // rather than restating a hex, which keeps light/dark following the scheme above.
  for (const [channel, slot] of Object.entries(tokens.chart.channelSlots)) {
    staticVars.push(`  --chart-channel-${kebab(channel)}: var(--chart-series-${slot + 1});`)
  }

  lines.push(':root {')
  lines.push('  color-scheme: light dark;')
  lines.push(staticVars.join('\n'))
  lines.push('')
  lines.push('  /* light is the default; both toggle scopes below override it */')
  lines.push(colorVars('light'))
  lines.push(chartVars('light'))
  lines.push('}')
  lines.push('')

  // The OS setting, guarded so an explicit light stamp still wins.
  lines.push('@media (prefers-color-scheme: dark) {')
  lines.push('  :root:where(:not([data-theme="light"])) {')
  lines.push(
    colorVars('dark')
      .split('\n')
      .map((l) => `  ${l}`)
      .join('\n'),
  )
  lines.push(
    chartVars('dark')
      .split('\n')
      .map((l) => `  ${l}`)
      .join('\n'),
  )
  lines.push('  }')
  lines.push('}')
  lines.push('')

  // The in-app toggle, which must win in both directions.
  lines.push(':root[data-theme="dark"] {')
  lines.push(colorVars('dark'))
  lines.push(chartVars('dark'))
  lines.push('}')
  lines.push('')

  return lines.join('\n')
}

/* -------------------------------------------------------------------- Kotlin */

const argb = (hex) => `0xFF${hex.replace('#', '').toUpperCase()}`

/**
 * Roles that exist in the token file but not in Compose's ColorScheme. `shadow` is a real
 * Material role, but Compose derives shadows from elevation instead of exposing a colour
 * slot, so passing it is a compile error. It still reaches the web, which does use it.
 */
const NOT_IN_COMPOSE_COLOR_SCHEME = new Set(['shadow'])

function buildKotlin() {
  const colorScheme = (mode) =>
    Object.entries(tokens.color[mode])
      .filter(([role]) => !NOT_IN_COMPOSE_COLOR_SCHEME.has(role))
      .map(([role, hex]) => `        ${role} = Color(${argb(hex)}),`)
      .join('\n')

  const seriesList = (mode) =>
    tokens.chart.series[mode].map((hex) => `Color(${argb(hex)})`).join(', ')

  const chromeFields = (mode) =>
    Object.entries(tokens.chart.chrome[mode])
      .map(([role, hex]) => `        ${role} = Color(${argb(hex)}),`)
      .join('\n')

  const statusFields = (mode) =>
    Object.entries(tokens.chart.status[mode])
      .map(([role, hex]) => `        ${role} = Color(${argb(hex)}),`)
      .join('\n')

  const sequentialList = tokens.chart.sequential.steps.map((hex) => `Color(${argb(hex)})`).join(', ')

  const elevationEntries = Object.entries(tokens.elevation)
    .map(([name, dp]) => `        "${name}" to ${dp}.dp,`)
    .join('\n')

  const typeEntries = Object.entries(tokens.typography.scale)
    .map(
      ([name, s]) =>
        `        "${name}" to TypeToken(${s.size}.sp, ${s.lineHeight}.sp, ${s.weight}, ${s.tracking}.sp),`,
    )
    .join('\n')

  const shapeEntries = Object.entries(tokens.shape)
    .map(([name, px]) => `        "${name}" to ${px}.dp,`)
    .join('\n')

  const spacingEntries = Object.entries(tokens.spacing)
    .map(([name, px]) => `    val ${name}: Dp = ${px}.dp`)
    .join('\n')

  const motionEntries = Object.entries(tokens.motion)
    .filter(([k]) => k === 'spatial' || k === 'effects')
    .map(([group, specs]) =>
      Object.entries(specs)
        .map(
          ([speed, s]) =>
            `        "${group}.${speed}" to SpringToken(${s.damping}f, ${s.stiffness}f),`,
        )
        .join('\n'),
    )
    .join('\n')

  const channelSlots = Object.entries(tokens.chart.channelSlots)
    .map(([channel, slot]) => `        "${channel}" to ${slot},`)
    .join('\n')

  return `// ${BANNER}
@file:Suppress("MagicNumber", "LongMethod")

package dev.healthhub.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One entry of the Material 3 Expressive type scale. */
data class TypeToken(
    val size: TextUnit,
    val lineHeight: TextUnit,
    val weight: Int,
    val tracking: TextUnit,
)

/** A Material 3 Expressive motion spring. */
data class SpringToken(val damping: Float, val stiffness: Float)

/**
 * Chart chrome for one appearance. Charts take their ink and grid from here rather
 * than from a charting default, per Constitution Principle III.
 */
data class ChartChrome(
    val surface: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val gridline: Color,
    val axis: Color,
)

/**
 * Status colours for one appearance. Amber and coral clear 3:1 on a near-black surface and
 * fail it on a near-white one, so there are two sets rather than one.
 */
data class ChartStatus(
    val good: Color,
    val warning: Color,
    val serious: Color,
    val critical: Color,
)

object GeneratedTokens {

    val lightColorScheme: ColorScheme = lightColorScheme(
${colorScheme('light')}
    )

    val darkColorScheme: ColorScheme = darkColorScheme(
${colorScheme('dark')}
    )

    /**
     * Categorical series colours, in fixed slot order. A channel keeps its slot no
     * matter how many series are visible — colour follows the entity, never its rank.
     */
    val chartSeriesLight: List<Color> = listOf(${seriesList('light')})
    val chartSeriesDark: List<Color> = listOf(${seriesList('dark')})

    /** Which slot each telemetry channel owns. */
    val channelSlots: Map<String, Int> = mapOf(
${channelSlots}
    )

    val chartChromeLight = ChartChrome(
${chromeFields('light')}
    )

    val chartChromeDark = ChartChrome(
${chromeFields('dark')}
    )

    val statusLight = ChartStatus(
${statusFields('light')}
    )

    val statusDark = ChartStatus(
${statusFields('dark')}
    )

    /**
     * The single-hue magnitude ramp, light to dark. Both clients lay ordinal categories out
     * across the same thirteen steps — the web through CSS custom properties, Android through
     * [sequentialStep] — so a heart-rate zone is the same blue in the browser and on the phone.
     */
    val chartSequential: List<Color> = listOf(${sequentialList})

    /**
     * The span of the ramp that stays legible on each surface. Below this step on light, and
     * above the dark ceiling below, a bar falls under 3:1 against the track it is drawn on.
     */
    val sequentialFloorLight: Int = ${tokens.chart.sequential.ordinalFloorLight}
    val sequentialCeilingDark: Int = ${tokens.chart.sequential.ordinalCeilingDark}

    val divergingNegative = Color(${argb(tokens.chart.diverging.negative)})
    val divergingPositive = Color(${argb(tokens.chart.diverging.positive)})
    val divergingMidpointLight = Color(${argb(tokens.chart.diverging.midpoint.light)})
    val divergingMidpointDark = Color(${argb(tokens.chart.diverging.midpoint.dark)})

    val lineWidth: Dp = ${tokens.chart.marks.lineWidth}.dp
    val markerMinSize: Dp = ${tokens.chart.marks.markerMinSize}.dp
    val dataEndRadius: Dp = ${tokens.chart.marks.dataEndRadius}.dp
    val fillGap: Dp = ${tokens.chart.marks.fillGap}.dp
    val overlapRing: Dp = ${tokens.chart.marks.overlapRing}.dp

    val typeScale: Map<String, TypeToken> = mapOf(
${typeEntries}
    )

    val shapeScale: Map<String, Dp> = mapOf(
${shapeEntries}
    )

    val elevationScale: Map<String, Dp> = mapOf(
${elevationEntries}
    )

    val springs: Map<String, SpringToken> = mapOf(
${motionEntries}
    )

    object Spacing {
${spacingEntries}
    }

    /** Colour for a telemetry channel in the given appearance. */
    fun seriesColor(channel: String, dark: Boolean): Color {
        val palette = if (dark) chartSeriesDark else chartSeriesLight
        val slot = channelSlots[channel] ?: return palette.last()
        return palette[slot % palette.size]
    }

    /**
     * The ramp step for the [index]-th of [count] ordered categories — heart-rate zones, say.
     *
     * The same arithmetic as the web client's ChartTheme.ordinal, against the same bounds,
     * because a zone that is the fifth of five must be the same blue on both screens.
     */
    fun sequentialStep(index: Int, count: Int, dark: Boolean): Color {
        val floor = if (dark) 1 else sequentialFloorLight
        val ceiling = if (dark) sequentialCeilingDark else chartSequential.size
        val step = if (count <= 1) {
            ceiling
        } else {
            floor + ((ceiling - floor) * index + (count - 1) / 2) / (count - 1)
        }
        return chartSequential[step.coerceIn(1, chartSequential.size) - 1]
    }
}
`
}

/* --------------------------------------------------------------------- write */

function write(path, contents) {
  const full = join(repoRoot, path)
  mkdirSync(dirname(full), { recursive: true })
  writeFileSync(full, contents, 'utf8')
  const kb = (Buffer.byteLength(contents) / 1024).toFixed(1)
  console.log(`  ${path}  (${kb} kB)`)
}

console.log('design-tokens →')
write('web/src/core/m3e/generated-tokens.css', buildCss())
write(
  'android/core/designsystem/src/main/kotlin/dev/healthhub/core/designsystem/GeneratedTokens.kt',
  buildKotlin(),
)
console.log('done')
