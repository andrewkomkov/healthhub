import { fail } from './errors'

/**
 * Fixed-window counters in D1.
 *
 * Deliberately coarse: this exists to blunt credential stuffing, not to shape traffic. Sync
 * routes get a generous allowance because a first-run backfill legitimately posts thousands
 * of activities in a burst.
 */
export interface RateLimit {
  bucket: string
  limit: number
  windowMs: number
}

export const AUTH_LIMIT: RateLimit = { bucket: 'auth', limit: 10, windowMs: 15 * 60 * 1000 }
export const SYNC_LIMIT: RateLimit = { bucket: 'sync', limit: 20_000, windowMs: 60 * 60 * 1000 }
/**
 * Minting archive credentials hands out a key that outlives the request, and Cloudflare
 * cannot revoke one before it expires. A handful an hour is more than a person needs and far
 * fewer than a stolen session could usefully collect.
 */
export const ARCHIVE_CREDENTIAL_LIMIT: RateLimit = {
  bucket: 'archive-credentials',
  limit: 10,
  windowMs: 60 * 60 * 1000,
}

export async function enforce(db: D1Database, rule: RateLimit, identity: string): Promise<void> {
  const now = Date.now()
  const windowStart = now - (now % rule.windowMs)

  // One statement: start a new window, or increment the current one.
  await db
    .prepare(
      `INSERT INTO rate_limits (bucket, identity, window_start, count)
       VALUES (?, ?, ?, 1)
       ON CONFLICT (bucket, identity) DO UPDATE SET
         count = CASE WHEN rate_limits.window_start = excluded.window_start
                      THEN rate_limits.count + 1 ELSE 1 END,
         window_start = excluded.window_start`,
    )
    .bind(rule.bucket, identity, windowStart)
    .run()

  const row = await db
    .prepare('SELECT count FROM rate_limits WHERE bucket = ? AND identity = ?')
    .bind(rule.bucket, identity)
    .first<{ count: number }>()

  if (row && row.count > rule.limit) {
    const retryAfter = Math.ceil((windowStart + rule.windowMs - now) / 1000)
    fail('rate_limited', 'Too many attempts. Try again later.', {
      'retry-after': String(retryAfter),
    })
  }
}

/** Best-effort client identity for unauthenticated routes. */
export function clientIp(request: Request): string {
  return request.headers.get('cf-connecting-ip') ?? 'unknown'
}
