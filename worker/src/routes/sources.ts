import { Hono } from 'hono'
import type { AppEnv } from '../types'
import { authenticate } from '../lib/guard'
import { arr, int, jsonBody, optionalStr, str } from '../lib/validate'

/**
 * Which apps the athlete trusts.
 *
 * When a bike computer and a fitness app both record the same ride, only the athlete can say
 * which one is right — their head unit measured the distance, their phone was in a pocket.
 * This is that answer, stored per user and applied by the phone when it decides which
 * recording represents a workout.
 *
 * Sources are discovered rather than configured: the phone reports every package it has seen
 * writing data, and they appear here for ordering. A source is never deleted, and disabling
 * one archives its activities rather than dropping them.
 */
export const sourceRoutes = new Hono<AppEnv>()

  .use('*', authenticate)

  .get('/', async (c) => {
    const { results } = await c.env.DB.prepare(
      `SELECT s.package_name, s.priority, s.enabled, s.label, s.first_seen_at, s.last_seen_at,
              (SELECT COUNT(*) FROM activities a
                WHERE a.user_id = s.user_id AND a.source_package = s.package_name) AS activity_count
         FROM source_preferences s
        WHERE s.user_id = ?
        ORDER BY s.priority ASC, s.package_name ASC`,
    )
      .bind(c.get('principal').userId)
      .all<{
        package_name: string
        priority: number
        enabled: number
        label: string | null
        first_seen_at: number
        last_seen_at: number | null
        activity_count: number
      }>()

    return c.json({
      sources: results.map((row) => ({
        packageName: row.package_name,
        priority: row.priority,
        enabled: row.enabled === 1,
        label: row.label,
        firstSeenAt: row.first_seen_at,
        lastSeenAt: row.last_seen_at,
        activityCount: row.activity_count,
      })),
    })
  })

  /**
   * Registers sources the phone has observed.
   *
   * Called during sync. Existing rows keep the athlete's ordering — discovery must never
   * reshuffle a preference they set deliberately.
   */
  .post('/seen', async (c) => {
    const userId = c.get('principal').userId
    const body = await jsonBody(c.req.raw)
    const packages = arr(body, 'packages', 100)
    const now = Date.now()

    if (packages.length === 0) return c.body(null, 204)

    await c.env.DB.batch(
      packages.map((raw) => {
        const entry = raw as Record<string, unknown>
        return c.env.DB.prepare(
          `INSERT INTO source_preferences (user_id, package_name, label, first_seen_at, last_seen_at)
           VALUES (?, ?, ?, ?, ?)
           ON CONFLICT (user_id, package_name) DO UPDATE SET
             last_seen_at = excluded.last_seen_at,
             label = COALESCE(excluded.label, source_preferences.label)`,
        ).bind(
          userId,
          str(entry, 'packageName', { max: 200 }),
          optionalStr(entry, 'label', { max: 120 }),
          now,
          now,
        )
      }),
    )

    return c.body(null, 204)
  })

  /** Reorders and enables/disables sources. The order given is the order stored. */
  .put('/', async (c) => {
    const userId = c.get('principal').userId
    const body = await jsonBody(c.req.raw)
    const sources = arr(body, 'sources', 100)
    const now = Date.now()

    await c.env.DB.batch(
      sources.map((raw, index) => {
        const entry = raw as Record<string, unknown>
        const enabled = entry['enabled'] === false ? 0 : 1
        return c.env.DB.prepare(
          `INSERT INTO source_preferences (user_id, package_name, priority, enabled, first_seen_at, last_seen_at)
           VALUES (?, ?, ?, ?, ?, ?)
           ON CONFLICT (user_id, package_name) DO UPDATE SET
             priority = excluded.priority,
             enabled = excluded.enabled,
             last_seen_at = excluded.last_seen_at`,
        ).bind(
          userId,
          str(entry, 'packageName', { max: 200 }),
          entry['priority'] === undefined ? index : int(entry, 'priority'),
          enabled,
          now,
          now,
        )
      }),
    )

    return c.body(null, 204)
  })
