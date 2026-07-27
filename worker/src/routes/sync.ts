import { Hono } from 'hono'
import type { AppEnv } from '../types'
import { authenticate, requireDevice } from '../lib/guard'
import { arr, int, jsonBody, oneOf, optionalInt, optionalStr, str } from '../lib/validate'

/**
 * Sync bookkeeping: cursors and reports.
 *
 * Cursors are advanced by the device only after its uploads are confirmed. That ordering is
 * the whole resumability story — an interrupted sync simply re-reads from the last confirmed
 * cursor, and the idempotent activity upsert absorbs anything that was already delivered.
 */
export const syncRoutes = new Hono<AppEnv>()

  .use('*', authenticate)

  .get('/cursors', requireDevice, async (c) => {
    const { results } = await c.env.DB.prepare(
      'SELECT record_type, change_token, synced_until FROM sync_cursors WHERE device_id = ?',
    )
      .bind(c.get('principal').deviceId as string)
      .all<{ record_type: string; change_token: string | null; synced_until: number | null }>()

    return c.json({
      cursors: results.map((r) => ({
        recordType: r.record_type,
        changeToken: r.change_token,
        syncedUntil: r.synced_until,
      })),
    })
  })

  .put('/cursors', requireDevice, async (c) => {
    const deviceId = c.get('principal').deviceId as string
    const body = await jsonBody(c.req.raw)
    const cursors = arr(body, 'cursors', 200)
    const now = Date.now()

    const statements = cursors.map((raw) => {
      const cursor = raw as Record<string, unknown>
      return c.env.DB.prepare(
        `INSERT INTO sync_cursors (device_id, record_type, change_token, synced_until, updated_at)
         VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (device_id, record_type) DO UPDATE SET
           change_token = excluded.change_token,
           synced_until = excluded.synced_until,
           updated_at = excluded.updated_at`,
      ).bind(
        deviceId,
        str(cursor, 'recordType', { max: 120 }),
        optionalStr(cursor, 'changeToken', { max: 4000 }),
        optionalInt(cursor, 'syncedUntil'),
        now,
      )
    })

    if (statements.length > 0) await c.env.DB.batch(statements)
    return c.body(null, 204)
  })

  /**
   * A sync report (FR-006). `unhandledTypes` is how Principle VI's "fail loudly" requirement
   * reaches the athlete: a record type the app did not understand is recorded and surfaced,
   * never silently discarded.
   */
  .post('/reports', requireDevice, async (c) => {
    const deviceId = c.get('principal').deviceId as string
    const body = await jsonBody(c.req.raw)

    const id = crypto.randomUUID()
    await c.env.DB.prepare(
      `INSERT INTO sync_reports (id, device_id, started_at, finished_at, status,
         sessions_synced, samples_synced, failures_json, unhandled_types_json, message)
       VALUES (?,?,?,?,?,?,?,?,?,?)`,
    )
      .bind(
        id,
        deviceId,
        int(body, 'startedAt'),
        optionalInt(body, 'finishedAt'),
        oneOf(body, 'status', ['running', 'ok', 'partial', 'failed'] as const),
        optionalInt(body, 'sessionsSynced') ?? 0,
        optionalInt(body, 'samplesSynced') ?? 0,
        JSON.stringify(arr(body, 'failures', 1000)),
        JSON.stringify(arr(body, 'unhandledTypes', 200)),
        optionalStr(body, 'message', { max: 2000 }),
      )
      .run()

    return c.json({ report: { id } }, 201)
  })

  .get('/reports', async (c) => {
    const principal = c.get('principal')
    const limit = Math.min(Math.max(Number(c.req.query('limit') ?? 20), 1), 100)

    // A browser session sees reports from every device on the account; a device sees its own.
    const query =
      principal.kind === 'device'
        ? c.env.DB.prepare(
            `SELECT r.* FROM sync_reports r WHERE r.device_id = ?
             ORDER BY r.started_at DESC LIMIT ?`,
          ).bind(principal.deviceId as string, limit)
        : c.env.DB.prepare(
            `SELECT r.* FROM sync_reports r
             JOIN devices d ON d.id = r.device_id
             WHERE d.user_id = ? ORDER BY r.started_at DESC LIMIT ?`,
          ).bind(principal.userId, limit)

    const { results } = await query.all<{
      id: string
      device_id: string
      started_at: number
      finished_at: number | null
      status: string
      sessions_synced: number
      samples_synced: number
      failures_json: string
      unhandled_types_json: string
      message: string | null
    }>()

    return c.json({
      reports: results.map((r) => ({
        id: r.id,
        deviceId: r.device_id,
        startedAt: r.started_at,
        finishedAt: r.finished_at,
        status: r.status,
        sessionsSynced: r.sessions_synced,
        samplesSynced: r.samples_synced,
        failures: JSON.parse(r.failures_json) as unknown[],
        unhandledTypes: JSON.parse(r.unhandled_types_json) as string[],
        message: r.message,
      })),
    })
  })
