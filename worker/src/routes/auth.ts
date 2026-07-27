import { Hono } from 'hono'
import type { AppEnv } from '../types'
import {
  auth0Config,
  authorizeUrl,
  exchangeCode,
  redirectUri,
  verifyIdToken,
} from '../auth/auth0'
import {
  clearedStateCookie,
  openState,
  sealState,
  STATE_COOKIE,
  stateCookie,
} from '../auth/oauthstate'
import { hashPassword, verifyPassword } from '../auth/password'
import {
  clearedSessionCookie,
  generateToken,
  hashToken,
  readCookie,
  sessionCookie,
  SESSION_COOKIE,
  SESSION_TTL_MS,
} from '../auth/tokens'
import { fail } from '../lib/errors'
import { authenticate } from '../lib/guard'
import { AUTH_LIMIT, clientIp, enforce } from '../lib/ratelimit'
import { email, jsonBody, normalizeEmail, oneOf, optionalStr, str } from '../lib/validate'

const MIN_PASSWORD = 10

interface UserRow {
  id: string
  email: string
  display_name: string
  unit_system: string
}

const publicUser = (row: UserRow) => ({
  id: row.id,
  email: row.email,
  displayName: row.display_name,
  unitSystem: row.unit_system,
})

async function startSession(
  db: D1Database,
  userId: string,
  pepper: string,
  userAgent: string | null,
): Promise<string> {
  const token = generateToken()
  const now = Date.now()
  await db
    .prepare(
      'INSERT INTO sessions (token_hash, user_id, created_at, expires_at, user_agent) VALUES (?, ?, ?, ?, ?)',
    )
    .bind(await hashToken(token, pepper), userId, now, now + SESSION_TTL_MS, userAgent)
    .run()
  return token
}

export const authRoutes = new Hono<AppEnv>()

  .post('/register', async (c) => {
    await enforce(c.env.DB, AUTH_LIMIT, clientIp(c.req.raw))

    const body = await jsonBody(c.req.raw)
    const address = email(body)
    const password = str(body, 'password', { min: MIN_PASSWORD, max: 512 })
    const displayName = str(body, 'displayName', { max: 80 })
    const norm = normalizeEmail(address)

    const existing = await c.env.DB.prepare('SELECT id FROM users WHERE email_norm = ?')
      .bind(norm)
      .first<{ id: string }>()
    if (existing) fail('conflict', 'An account with that email already exists.')

    const id = crypto.randomUUID()
    await c.env.DB.prepare(
      `INSERT INTO users (id, email, email_norm, password_hash, display_name, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
    )
      .bind(id, address, norm, await hashPassword(password), displayName, Date.now())
      .run()

    const token = await startSession(
      c.env.DB,
      id,
      c.env.SESSION_PEPPER ?? '',
      c.req.header('user-agent') ?? null,
    )
    c.header('set-cookie', sessionCookie(token))
    return c.json(
      { user: { id, email: address, displayName, unitSystem: 'metric' } },
      201,
    )
  })

  .post('/login', async (c) => {
    await enforce(c.env.DB, AUTH_LIMIT, clientIp(c.req.raw))

    const body = await jsonBody(c.req.raw)
    const address = email(body)
    const password = str(body, 'password', { min: 1, max: 512 })

    const row = await c.env.DB.prepare(
      `SELECT id, email, display_name, unit_system, password_hash
       FROM users WHERE email_norm = ? AND deleted_at IS NULL`,
    )
      .bind(normalizeEmail(address))
      .first<UserRow & { password_hash: string }>()

    // Hash even when the account is unknown, so a missing account and a wrong password
    // cost the same time and return the same response.
    const stored = row?.password_hash ?? (await hashPassword(crypto.randomUUID()))
    const ok = await verifyPassword(password, stored)
    if (!row || !ok) fail('unauthenticated', 'Incorrect email or password.')

    const token = await startSession(
      c.env.DB,
      row.id,
      c.env.SESSION_PEPPER ?? '',
      c.req.header('user-agent') ?? null,
    )
    c.header('set-cookie', sessionCookie(token))
    return c.json({ user: publicUser(row) })
  })

  .post('/logout', async (c) => {
    const cookie = readCookie(c.req.header('cookie'), SESSION_COOKIE)
    if (cookie) {
      await c.env.DB.prepare('DELETE FROM sessions WHERE token_hash = ?')
        .bind(await hashToken(cookie, c.env.SESSION_PEPPER ?? ''))
        .run()
    }
    c.header('set-cookie', clearedSessionCookie())
    return c.body(null, 204)
  })

  .get('/me', authenticate, async (c) => {
    const row = await c.env.DB.prepare(
      'SELECT id, email, display_name, unit_system FROM users WHERE id = ? AND deleted_at IS NULL',
    )
      .bind(c.get('principal').userId)
      .first<UserRow>()
    if (!row) fail('unauthenticated', 'Sign in to continue.')
    return c.json({ user: publicUser(row) })
  })

  .patch('/me', authenticate, async (c) => {
    const body = await jsonBody(c.req.raw)
    const userId = c.get('principal').userId

    const displayName = optionalStr(body, 'displayName', { max: 80 })
    const unitSystem =
      body['unitSystem'] === undefined
        ? null
        : oneOf(body, 'unitSystem', ['metric', 'imperial'] as const)

    if (displayName !== null) {
      await c.env.DB.prepare('UPDATE users SET display_name = ? WHERE id = ?')
        .bind(displayName, userId)
        .run()
    }
    if (unitSystem !== null) {
      await c.env.DB.prepare('UPDATE users SET unit_system = ? WHERE id = ?')
        .bind(unitSystem, userId)
        .run()
    }

    const row = await c.env.DB.prepare(
      'SELECT id, email, display_name, unit_system FROM users WHERE id = ?',
    )
      .bind(userId)
      .first<UserRow>()
    if (!row) fail('not_found', 'No such account.')
    return c.json({ user: publicUser(row) })
  })

  /**
   * Account deletion (FR-029). R2 objects are removed explicitly rather than relying on a
   * cascade, because no cascade reaches object storage.
   */
  .delete('/me', authenticate, async (c) => {
    const userId = c.get('principal').userId
    const prefix = `u/${userId}/`

    let cursor: string | undefined
    do {
      const page = await c.env.BLOBS.list({ prefix, cursor, limit: 1000 })
      if (page.objects.length > 0) {
        await c.env.BLOBS.delete(page.objects.map((o) => o.key))
      }
      cursor = page.truncated ? page.cursor : undefined
    } while (cursor)

    // Explicit, ordered deletes rather than trusting ON DELETE CASCADE: D1 only enforces
    // foreign keys when the connection has PRAGMA foreign_keys = ON, and "the athlete's data
    // is actually gone" is too important to make conditional on a pragma.
    const db = c.env.DB
    await db.batch([
      db
        .prepare(
          'DELETE FROM activity_splits WHERE activity_id IN (SELECT id FROM activities WHERE user_id = ?)',
        )
        .bind(userId),
      db
        .prepare(
          'DELETE FROM activity_zones WHERE activity_id IN (SELECT id FROM activities WHERE user_id = ?)',
        )
        .bind(userId),
      db
        .prepare(
          'DELETE FROM sync_cursors WHERE device_id IN (SELECT id FROM devices WHERE user_id = ?)',
        )
        .bind(userId),
      db
        .prepare(
          'DELETE FROM sync_reports WHERE device_id IN (SELECT id FROM devices WHERE user_id = ?)',
        )
        .bind(userId),
      db.prepare('DELETE FROM activities WHERE user_id = ?').bind(userId),
      db.prepare('DELETE FROM privacy_zones WHERE user_id = ?').bind(userId),
      db.prepare('DELETE FROM devices WHERE user_id = ?').bind(userId),
      db.prepare('DELETE FROM sessions WHERE user_id = ?').bind(userId),
      db.prepare('DELETE FROM identities WHERE user_id = ?').bind(userId),
      db.prepare('DELETE FROM user_theme WHERE user_id = ?').bind(userId),
      db.prepare('DELETE FROM users WHERE id = ?').bind(userId),
    ])

    c.header('set-cookie', clearedSessionCookie())
    return c.body(null, 204)
  })

  /** Lets the clients render only the sign-in methods this deployment actually has. */
  .get('/providers', (c) =>
    c.json({ password: true, auth0: auth0Config(c.env) !== null }),
  )

  /**
   * Starts the Auth0 authorization code flow.
   *
   * `mode=device` is how the Android app signs in: it opens this in a Custom Tab, and the
   * callback finishes by redirecting to a `healthhub://` deep link carrying a freshly issued
   * device token, so the app never handles the athlete's password or the client secret.
   */
  .get('/auth0/login', async (c) => {
    const config = auth0Config(c.env)
    if (!config) fail('not_found', 'Auth0 is not configured for this deployment.')

    const mode = c.req.query('mode') === 'device' ? 'device' : 'web'
    const sealed = await sealState(
      {
        state: crypto.randomUUID(),
        nonce: crypto.randomUUID(),
        mode,
        deviceName: c.req.query('deviceName')?.slice(0, 120),
        issuedAt: Date.now(),
      },
      c.env.SESSION_PEPPER ?? '',
    )

    const opened = await openState(sealed, c.env.SESSION_PEPPER ?? '')
    if (!opened) fail('internal', 'Could not start sign-in.')

    c.header('set-cookie', stateCookie(sealed))
    return c.redirect(
      authorizeUrl(config, {
        state: opened.state,
        nonce: opened.nonce,
        redirectUri: redirectUri(c.req.raw),
      }),
      302,
    )
  })

  .get('/auth0/callback', async (c) => {
    const config = auth0Config(c.env)
    if (!config) fail('not_found', 'Auth0 is not configured for this deployment.')

    const pepper = c.env.SESSION_PEPPER ?? ''
    const sealed = readCookie(c.req.header('cookie'), STATE_COOKIE)
    const pending = sealed ? await openState(sealed, pepper) : null
    if (!pending) fail('unauthenticated', 'Sign-in expired. Please try again.')

    // The state check is what stops a third party from feeding us their own login.
    if (c.req.query('state') !== pending.state) {
      fail('unauthenticated', 'Sign-in state mismatch.')
    }

    const error = c.req.query('error')
    if (error) {
      fail('unauthenticated', c.req.query('error_description') ?? 'Sign-in was cancelled.')
    }

    const code = c.req.query('code')
    if (!code) fail('unauthenticated', 'Sign-in did not return a code.')

    const idToken = await exchangeCode(config, code, redirectUri(c.req.raw))
    const claims = await verifyIdToken(config, idToken, pending.nonce)

    const userId = await linkOrCreateUser(c.env.DB, claims)

    c.header('set-cookie', clearedStateCookie(), { append: true })

    if (pending.mode === 'device') {
      const token = generateToken()
      const deviceId = crypto.randomUUID()
      await c.env.DB.prepare(
        `INSERT INTO devices (id, user_id, token_hash, name, platform, created_at)
         VALUES (?, ?, ?, ?, 'android', ?)`,
      )
        .bind(
          deviceId,
          userId,
          await hashToken(token, pepper),
          pending.deviceName ?? 'Android device',
          Date.now(),
        )
        .run()

      // Handing the token back through the deep link keeps it out of the Custom Tab's
      // history and out of any page the athlete can see.
      return c.redirect(
        `healthhub://auth/callback?token=${encodeURIComponent(token)}&device=${deviceId}`,
        302,
      )
    }

    const session = await startSession(
      c.env.DB,
      userId,
      pepper,
      c.req.header('user-agent') ?? null,
    )
    c.header('set-cookie', sessionCookie(session), { append: true })
    return c.redirect(pending.returnTo ?? '/', 302)
  })

/**
 * Resolves an Auth0 identity to a HealthHub account.
 *
 * Matching an existing local account by verified email is deliberate: an athlete who
 * registered with a password and later signs in with Auth0 should land in their own account,
 * not a duplicate. It is gated on `email_verified` — without that check, anyone able to
 * assert an unverified email at the provider could claim an existing account.
 */
async function linkOrCreateUser(
  db: D1Database,
  claims: { sub: string; email?: string; email_verified?: boolean; name?: string; nickname?: string },
): Promise<string> {
  const now = Date.now()

  const existing = await db
    .prepare('SELECT user_id FROM identities WHERE provider = ? AND subject = ?')
    .bind('auth0', claims.sub)
    .first<{ user_id: string }>()

  if (existing) {
    await db
      .prepare('UPDATE identities SET last_seen_at = ? WHERE provider = ? AND subject = ?')
      .bind(now, 'auth0', claims.sub)
      .run()
    return existing.user_id
  }

  const address = claims.email
  const norm = address ? normalizeEmail(address) : null

  if (norm && claims.email_verified) {
    const byEmail = await db
      .prepare('SELECT id FROM users WHERE email_norm = ? AND deleted_at IS NULL')
      .bind(norm)
      .first<{ id: string }>()
    if (byEmail) {
      await db
        .prepare(
          `INSERT INTO identities (provider, subject, user_id, email, created_at, last_seen_at)
           VALUES (?, ?, ?, ?, ?, ?)`,
        )
        .bind('auth0', claims.sub, byEmail.id, address ?? null, now, now)
        .run()
      return byEmail.id
    }
  }

  const id = crypto.randomUUID()
  const displayName = claims.name ?? claims.nickname ?? address ?? 'Athlete'
  // Auth0 accounts carry no local password; the sentinel never parses as a PBKDF2 record,
  // so password verification against it can only fail.
  await db.batch([
    db
      .prepare(
        `INSERT INTO users (id, email, email_norm, password_hash, display_name, created_at)
         VALUES (?, ?, ?, 'external', ?, ?)`,
      )
      .bind(id, address ?? `${claims.sub}@auth0.local`, norm ?? claims.sub.toLowerCase(), displayName, now),
    db
      .prepare(
        `INSERT INTO identities (provider, subject, user_id, email, created_at, last_seen_at)
         VALUES (?, ?, ?, ?, ?, ?)`,
      )
      .bind('auth0', claims.sub, id, address ?? null, now, now),
  ])

  return id
}
