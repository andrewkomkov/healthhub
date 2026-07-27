import { Hono } from 'hono'
import type { AppEnv } from '../types'
import { generateToken, hashToken } from '../auth/tokens'
import { fail } from '../lib/errors'
import { authenticate } from '../lib/guard'
import { jsonBody, oneOf, optionalStr, str } from '../lib/validate'

export const deviceRoutes = new Hono<AppEnv>()

  .use('*', authenticate)

  /**
   * Registers an Android installation and issues its device token.
   *
   * Requires a browser-style session: the app signs in normally, then exchanges that for a
   * long-lived device token. The token is returned exactly once — only its hash is stored,
   * so it cannot be recovered, only replaced.
   */
  .post('/', async (c) => {
    const principal = c.get('principal')
    if (principal.kind !== 'session') {
      fail('forbidden', 'Sign in first, then register this device.')
    }

    const body = await jsonBody(c.req.raw)
    const name = str(body, 'name', { max: 120 })
    const platform = oneOf(body, 'platform', ['android'] as const, 'android')
    const appVersion = optionalStr(body, 'appVersion', { max: 40 })

    const id = crypto.randomUUID()
    const token = generateToken()
    const now = Date.now()

    await c.env.DB.prepare(
      `INSERT INTO devices (id, user_id, token_hash, name, platform, app_version, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind(
        id,
        principal.userId,
        await hashToken(token, c.env.SESSION_PEPPER ?? ''),
        name,
        platform,
        appVersion,
        now,
      )
      .run()

    return c.json({ device: { id, name, platform, createdAt: now }, token }, 201)
  })

  .get('/', async (c) => {
    const { results } = await c.env.DB.prepare(
      `SELECT id, name, platform, app_version, created_at, last_seen_at, revoked_at
       FROM devices WHERE user_id = ? ORDER BY created_at DESC`,
    )
      .bind(c.get('principal').userId)
      .all<{
        id: string
        name: string
        platform: string
        app_version: string | null
        created_at: number
        last_seen_at: number | null
        revoked_at: number | null
      }>()

    return c.json({
      devices: results.map((d) => ({
        id: d.id,
        name: d.name,
        platform: d.platform,
        appVersion: d.app_version,
        createdAt: d.created_at,
        lastSeenAt: d.last_seen_at,
        revokedAt: d.revoked_at,
      })),
    })
  })

  /** Revokes a device. Its token stops authenticating immediately (FR-028). */
  .delete('/:id', async (c) => {
    const result = await c.env.DB.prepare(
      'UPDATE devices SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL',
    )
      .bind(Date.now(), c.req.param('id'), c.get('principal').userId)
      .run()

    if (result.meta.changes === 0) fail('not_found', 'No such device.')
    return c.body(null, 204)
  })
