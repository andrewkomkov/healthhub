import type { MiddlewareHandler } from 'hono'
import type { AppEnv, Principal } from '../types'
import { bearerToken, hashToken, readCookie, SESSION_COOKIE } from '../auth/tokens'
import { fail } from './errors'

/**
 * Resolves the request to exactly one athlete.
 *
 * A device token identifies a registered Android installation; a session cookie identifies a
 * browser. Both resolve to a user id, and every downstream query is scoped by it — which is
 * how per-user isolation (FR-027, SC-009) is enforced in one place rather than route by route.
 */
export const authenticate: MiddlewareHandler<AppEnv> = async (c, next) => {
  const pepper = c.env.SESSION_PEPPER ?? ''
  const principal = await resolvePrincipal(c.req.raw, c.env.DB, pepper)
  if (!principal) fail('unauthenticated', 'Sign in to continue.')

  c.set('principal', principal)
  await next()
}

/** Same as `authenticate`, but additionally rejects browser sessions. */
export const requireDevice: MiddlewareHandler<AppEnv> = async (c, next) => {
  const principal = c.get('principal')
  if (principal.kind !== 'device') {
    fail('forbidden', 'This endpoint is for registered devices.')
  }
  await next()
}

async function resolvePrincipal(
  request: Request,
  db: D1Database,
  pepper: string,
): Promise<Principal | null> {
  const device = bearerToken(request.headers.get('authorization'))
  if (device) {
    const hash = await hashToken(device, pepper)
    const row = await db
      .prepare('SELECT id, user_id FROM devices WHERE token_hash = ? AND revoked_at IS NULL')
      .bind(hash)
      .first<{ id: string; user_id: string }>()
    if (!row) return null

    // Best-effort liveness stamp; never worth failing a sync over.
    await db
      .prepare('UPDATE devices SET last_seen_at = ? WHERE id = ?')
      .bind(Date.now(), row.id)
      .run()
      .catch(() => undefined)

    return { userId: row.user_id, deviceId: row.id, kind: 'device' }
  }

  const cookie = readCookie(request.headers.get('cookie'), SESSION_COOKIE)
  if (cookie) {
    const hash = await hashToken(cookie, pepper)
    const row = await db
      .prepare('SELECT user_id, expires_at FROM sessions WHERE token_hash = ?')
      .bind(hash)
      .first<{ user_id: string; expires_at: number }>()
    if (!row) return null
    if (row.expires_at <= Date.now()) {
      await db.prepare('DELETE FROM sessions WHERE token_hash = ?').bind(hash).run()
      return null
    }
    return { userId: row.user_id, kind: 'session' }
  }

  return null
}

/**
 * Loads a resource the caller owns, or 404s.
 *
 * The 404-on-mismatch is deliberate: a 403 would confirm the identifier exists and let
 * activities be enumerated across accounts.
 */
export async function ownedActivity<T = Record<string, unknown>>(
  db: D1Database,
  userId: string,
  activityId: string,
  columns = '*',
): Promise<T> {
  const row = await db
    .prepare(
      `SELECT ${columns} FROM activities WHERE id = ? AND user_id = ? AND deleted_at IS NULL`,
    )
    .bind(activityId, userId)
    .first<T>()
  if (!row) fail('not_found', 'No such activity.')
  return row
}
