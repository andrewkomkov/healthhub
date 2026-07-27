import { Hono } from 'hono'
import type { AppEnv } from '../types'
import { fail } from '../lib/errors'
import { authenticate, requireDevice } from '../lib/guard'
import { jsonBody, oneOf } from '../lib/validate'

/**
 * The athlete's personal colour scheme.
 *
 * The phone extracts a Material You palette from the wallpaper and pushes it here; the web
 * client fetches it and overrides its own token values. The result is that both clients wear
 * the same personalised scheme rather than merely the same design system.
 *
 * Only UI colour roles travel. The chart series palette is **not** part of this: it is
 * validated for colour-vision deficiency and contrast against both surfaces, and a palette
 * derived from someone's wallpaper carries no such guarantee. Personalisation stops where
 * legibility of data begins.
 */

/** Exactly the roles the token pipeline defines. Anything else is rejected, not stored. */
const ROLES = new Set([
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

const HEX = /^#[0-9a-fA-F]{6}$/

/**
 * Validates a scheme into a clean object.
 *
 * Unknown roles are dropped rather than stored: this JSON is injected into the page as CSS
 * custom properties, so accepting arbitrary keys and values would hand an attacker a way to
 * write whatever they liked into another session's stylesheet.
 */
function cleanScheme(value: unknown, label: string): Record<string, string> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    fail('validation_failed', `"${label}" must be an object of colour roles.`)
  }

  const cleaned: Record<string, string> = {}
  for (const [role, hex] of Object.entries(value as Record<string, unknown>)) {
    if (!ROLES.has(role)) continue
    if (typeof hex !== 'string' || !HEX.test(hex)) {
      fail('validation_failed', `"${label}.${role}" must be a #RRGGBB colour.`)
    }
    cleaned[role] = hex.toLowerCase()
  }

  if (Object.keys(cleaned).length === 0) {
    fail('validation_failed', `"${label}" carried no recognised colour roles.`)
  }
  return cleaned
}

export const themeRoutes = new Hono<AppEnv>()

  .use('*', authenticate)

  /** The web client calls this on load; a 404 simply means "use the built-in palette". */
  .get('/', async (c) => {
    const row = await c.env.DB.prepare(
      'SELECT light_json, dark_json, source, updated_at FROM user_theme WHERE user_id = ?',
    )
      .bind(c.get('principal').userId)
      .first<{ light_json: string; dark_json: string; source: string; updated_at: number }>()

    if (!row) return c.json({ theme: null })

    return c.json({
      theme: {
        light: JSON.parse(row.light_json) as Record<string, string>,
        dark: JSON.parse(row.dark_json) as Record<string, string>,
        source: row.source,
        updatedAt: row.updated_at,
      },
    })
  })

  /** The phone pushes its Material You palette here after extracting it from the wallpaper. */
  .put('/', requireDevice, async (c) => {
    const principal = c.get('principal')
    const body = await jsonBody(c.req.raw)

    const source = oneOf(body, 'source', ['dynamic', 'default'] as const, 'dynamic')
    const light = cleanScheme(body['light'], 'light')
    const dark = cleanScheme(body['dark'], 'dark')

    await c.env.DB.prepare(
      `INSERT INTO user_theme (user_id, light_json, dark_json, source, device_id, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT (user_id) DO UPDATE SET
         light_json = excluded.light_json,
         dark_json = excluded.dark_json,
         source = excluded.source,
         device_id = excluded.device_id,
         updated_at = excluded.updated_at`,
    )
      .bind(
        principal.userId,
        JSON.stringify(light),
        JSON.stringify(dark),
        source,
        principal.deviceId ?? null,
        Date.now(),
      )
      .run()

    return c.body(null, 204)
  })

  /** Reverts to the built-in palette. */
  .delete('/', async (c) => {
    await c.env.DB.prepare('DELETE FROM user_theme WHERE user_id = ?')
      .bind(c.get('principal').userId)
      .run()
    return c.body(null, 204)
  })
