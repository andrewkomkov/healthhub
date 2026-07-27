/**
 * Wears the athlete's personal colour scheme.
 *
 * Android 12+ derives a Material You palette from the wallpaper. The phone pushes the
 * resolved roles to the Worker; this applies them here, so the browser shows the same
 * personalised scheme as the phone in the athlete's pocket.
 *
 * Only UI roles are overridden. Chart series colours are left alone deliberately: they are
 * validated for colour-vision deficiency and contrast against both surfaces, and a palette
 * derived from a photo of someone's dog carries no such guarantee. Personalisation stops
 * where legibility of data begins.
 */

export interface DynamicTheme {
  light: Record<string, string>
  dark: Record<string, string>
  source: 'dynamic' | 'default'
  updatedAt: number
}

const STYLE_ELEMENT_ID = 'hh-dynamic-theme'

/**
 * The complete set of roles a personal scheme may carry — the UI palette and nothing else.
 *
 * The Worker already rejects anything outside this list, and this repeats it because the
 * consequence of being wrong lands here: these values are written into a stylesheet. A chart
 * series slot is not on the list and cannot be added to it by anything a phone uploads, which
 * is the rule `contrast.test.ts` measures the palette against.
 */
const UI_ROLES = new Set([
  'primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer',
  'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
  'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
  'error', 'onError', 'errorContainer', 'onErrorContainer',
  'background', 'onBackground', 'surface', 'onSurface',
  'surfaceVariant', 'onSurfaceVariant',
  'surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer',
  'surfaceContainerHigh', 'surfaceContainerHighest',
  'surfaceDim', 'surfaceBright',
  'outline', 'outlineVariant',
  'inverseSurface', 'inverseOnSurface', 'inversePrimary',
  'scrim', 'shadow',
])

/** Exported for the test that pins it against the token file and the Worker's own list. */
export const dynamicRoles: ReadonlySet<string> = UI_ROLES

const HEX = /^#[0-9a-fA-F]{6}$/

const kebab = (role: string) => role.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()

function declarations(scheme: Record<string, string>): string {
  return Object.entries(scheme)
    .filter(([role, hex]) => UI_ROLES.has(role) && HEX.test(hex))
    .map(([role, hex]) => `  --md-sys-color-${kebab(role)}: ${hex};`)
    .join('\n')
}

/**
 * Injects the scheme as a stylesheet rather than setting inline properties on `:root`.
 *
 * A stylesheet can carry the same light/dark cascade the generated tokens use — the OS
 * setting plus the explicit theme toggle, with the toggle winning both ways — which setting
 * properties directly on the element cannot express.
 */
export function applyDynamicTheme(theme: DynamicTheme | null): void {
  const existing = document.getElementById(STYLE_ELEMENT_ID)
  if (existing) existing.remove()
  if (!theme) return

  const style = document.createElement('style')
  style.id = STYLE_ELEMENT_ID
  style.textContent = [
    ':root {',
    declarations(theme.light),
    '}',
    '',
    '@media (prefers-color-scheme: dark) {',
    '  :root:where(:not([data-theme="light"])) {',
    declarations(theme.dark),
    '  }',
    '}',
    '',
    ':root[data-theme="dark"] {',
    declarations(theme.dark),
    '}',
  ].join('\n')

  // Appended last so it wins over generated-tokens.css at equal specificity.
  document.head.append(style)

  const surface = theme.light['surface']
  if (surface) {
    document
      .querySelector('meta[name="theme-color"][media*="light"]')
      ?.setAttribute('content', surface)
  }
  const darkSurface = theme.dark['surface']
  if (darkSurface) {
    document
      .querySelector('meta[name="theme-color"][media*="dark"]')
      ?.setAttribute('content', darkSurface)
  }
}
