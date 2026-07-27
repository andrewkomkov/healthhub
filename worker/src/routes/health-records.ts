import { Hono } from 'hono'
import type { AppEnv } from '../types'
import { decodeCursor, encodeCursor } from '../lib/cursor'
import { fail } from '../lib/errors'
import { authenticate, requireDevice } from '../lib/guard'
import { localDate, sleepStagesKey } from '../lib/keys'
import { enforce, SYNC_LIMIT } from '../lib/ratelimit'
import {
  arr,
  int,
  jsonBody,
  num,
  optionalInt,
  optionalNum,
  optionalStr,
  str,
} from '../lib/validate'

/**
 * Daily-grain health data: sleep, and the point measurements that make up recovery.
 *
 * Mounted at `/api/health-records` rather than `/api/health`, which is the unauthenticated
 * liveness probe and stays that way — two routers on one prefix would resolve by registration
 * order, which is not a property anybody should have to reason about.
 *
 * Storage split, decided in migration 0006 and explained there: scalar measurements and the
 * per-night summary are D1 rows, because they are queried by kind and date and paged like the
 * feed. A night's *stage intervals* are one R2 object, because there are tens to hundreds per
 * night, they are only ever read whole with that night, and one row per interval is how the
 * row count explodes.
 *
 * Nothing here computes. Stage totals, HRV values and everything else arrive already derived
 * from the phone and are stored verbatim. No readiness score, no sleep-quality index, no
 * seven-day average is produced at the edge — those are the device's job (Principle I), and a
 * route here that produced one would be a defect regardless of how small it looked.
 */

/** Health Connect's stage enum, one column each. Order is display order, not enum order. */
const STAGE_COLUMNS = [
  ['awake', 'awake_seconds'],
  ['awakeInBed', 'awake_in_bed_seconds'],
  ['outOfBed', 'out_of_bed_seconds'],
  ['sleeping', 'sleeping_seconds'],
  ['light', 'light_seconds'],
  ['deep', 'deep_seconds'],
  ['rem', 'rem_seconds'],
  ['unknown', 'unknown_seconds'],
] as const

/**
 * Which record types exist is the phone's knowledge, not the edge's.
 *
 * A slug is checked for shape and nothing else. An allow-list here would mean that every new
 * Health Connect type the Android registry learns needs a Worker deploy before it can be
 * uploaded at all — which is exactly the silent-loss failure Principle VI forbids, dressed up
 * as validation.
 */
function kindSlug(body: Record<string, unknown>): string {
  const value = str(body, 'kind', { max: 60 })
  if (!/^[a-z][a-z0-9_]*$/.test(value)) {
    fail('validation_failed', '"kind" must be a lowercase slug, e.g. "resting_heart_rate".')
  }
  return value
}

interface MeasurementRow {
  id: string
  kind: string
  source_uid: string
  source_package: string | null
  measured_at: number
  tz_offset_minutes: number
  local_date: string
  value: number
  secondary_value: number | null
  unit: string
}

const measurementItem = (row: MeasurementRow) => ({
  id: row.id,
  kind: row.kind,
  sourceUid: row.source_uid,
  sourcePackage: row.source_package,
  measuredAt: row.measured_at,
  tzOffsetMinutes: row.tz_offset_minutes,
  localDate: row.local_date,
  value: row.value,
  // Only blood pressure uses this today (systolic/diastolic). The Worker stores two numbers
  // and a unit; what they mean is the client's business.
  secondaryValue: row.secondary_value,
  unit: row.unit,
})

interface SleepRow {
  id: string
  source_uid: string
  source_package: string | null
  title: string | null
  start_time: number
  end_time: number
  tz_offset_minutes: number
  local_date: string
  total_seconds: number
  time_in_bed_seconds: number | null
  stage_count: number
  stages_key: string | null
  stages_bytes: number | null
  [column: string]: unknown
}

const sleepItem = (row: SleepRow) => ({
  id: row.id,
  sourceUid: row.source_uid,
  sourcePackage: row.source_package,
  title: row.title,
  startTime: row.start_time,
  endTime: row.end_time,
  tzOffsetMinutes: row.tz_offset_minutes,
  localDate: row.local_date,
  totalSeconds: row.total_seconds,
  timeInBedSeconds: row.time_in_bed_seconds,
  stages: Object.fromEntries(
    STAGE_COLUMNS.map(([name, column]) => [name, (row[column] as number | null) ?? null]),
  ),
  stageCount: row.stage_count,
  hypnogram: { stored: Boolean(row.stages_key), bytes: row.stages_bytes },
})

const SLEEP_COLUMNS = `id, source_uid, source_package, title, start_time, end_time,
  tz_offset_minutes, local_date, total_seconds, time_in_bed_seconds, stage_count,
  stages_key, stages_bytes, ${STAGE_COLUMNS.map(([, column]) => column).join(', ')}`

/**
 * Shared by both lists: `limit`, `from`, `to` and a keyset cursor over the given time column.
 *
 * The default limit is 90 rather than the feed's 30 because these screens are trends — three
 * months of nights is the shape of the question being asked, not three months of scrolling.
 */
function timeWindow(
  query: (key: string) => string | undefined,
  timeColumn: string,
): { where: string[]; binds: (string | number)[]; limit: number } {
  const limit = Math.min(Math.max(Number(query('limit') ?? 90), 1), 366)
  const where: string[] = ['user_id = ?']
  const binds: (string | number)[] = []

  const from = query('from')
  const to = query('to')
  if (from) {
    where.push(`${timeColumn} >= ?`)
    binds.push(Number(from))
  }
  if (to) {
    where.push(`${timeColumn} <= ?`)
    binds.push(Number(to))
  }

  const cursor = query('cursor')
  if (cursor) {
    const { ts, id } = decodeCursor(cursor)
    where.push(`(${timeColumn} < ? OR (${timeColumn} = ? AND id < ?))`)
    binds.push(ts, ts, id)
  }

  return { where, binds, limit }
}

export const healthRecordRoutes = new Hono<AppEnv>()

  .use('*', authenticate)

  /**
   * Uploads scalar measurements in a batch.
   *
   * Idempotent on `(user_id, sourceUid)` for the same reason activities are: a sync that dies
   * halfway is retried, not reconciled. One D1 batch per request, so a day's worth of readings
   * is one round trip.
   */
  .post('/measurements', requireDevice, async (c) => {
    const principal = c.get('principal')
    await enforce(c.env.DB, SYNC_LIMIT, principal.deviceId as string)

    const body = await jsonBody(c.req.raw)
    const measurements = arr(body, 'measurements', 2000)
    if (measurements.length === 0) return c.json({ accepted: 0 })

    const now = Date.now()
    const db = c.env.DB

    await db.batch(
      measurements.map((raw) => {
        const entry = raw as Record<string, unknown>
        const measuredAt = int(entry, 'measuredAt')
        const tzOffsetMinutes = optionalInt(entry, 'tzOffsetMinutes') ?? 0

        return db
          .prepare(
            `INSERT INTO health_measurements (
               id, user_id, source_uid, source_package, kind, measured_at, tz_offset_minutes,
               local_date, value, secondary_value, unit, created_at, updated_at
             ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
             ON CONFLICT (user_id, source_uid) DO UPDATE SET
               source_package = excluded.source_package,
               kind = excluded.kind,
               measured_at = excluded.measured_at,
               tz_offset_minutes = excluded.tz_offset_minutes,
               local_date = excluded.local_date,
               value = excluded.value,
               secondary_value = excluded.secondary_value,
               unit = excluded.unit,
               updated_at = excluded.updated_at`,
          )
          .bind(
            crypto.randomUUID(),
            principal.userId,
            str(entry, 'sourceUid', { max: 200 }),
            optionalStr(entry, 'sourcePackage', { max: 200 }),
            kindSlug(entry),
            measuredAt,
            tzOffsetMinutes,
            localDate(measuredAt, tzOffsetMinutes),
            num(entry, 'value'),
            optionalNum(entry, 'secondaryValue'),
            str(entry, 'unit', { max: 20 }),
            now,
            now,
          )
      }),
    )

    return c.json({ accepted: measurements.length })
  })

  /**
   * The trend query: one kind, newest first. One indexed D1 read, no R2 access, no maths.
   *
   * A client that wants a weekly average computes it from these rows. That is not an
   * oversight — see the note at the top of this file.
   */
  .get('/measurements', async (c) => {
    const userId = c.get('principal').userId
    const { where, binds, limit } = timeWindow((k) => c.req.query(k), 'measured_at')

    const kind = c.req.query('kind')
    if (kind) {
      where.push('kind = ?')
      binds.push(kind)
    }

    const { results } = await c.env.DB.prepare(
      `SELECT id, kind, source_uid, source_package, measured_at, tz_offset_minutes, local_date,
              value, secondary_value, unit
         FROM health_measurements
        WHERE ${where.join(' AND ')}
        ORDER BY measured_at DESC, id DESC
        LIMIT ?`,
    )
      .bind(userId, ...binds, limit + 1)
      .all<MeasurementRow>()

    const hasMore = results.length > limit
    const page = hasMore ? results.slice(0, limit) : results
    const last = page[page.length - 1]

    return c.json({
      measurements: page.map(measurementItem),
      nextCursor: hasMore && last ? encodeCursor(last.measured_at, last.id) : null,
    })
  })

  /**
   * Uploads one night's summary.
   *
   * The stage totals in the body were computed on the phone from the intervals it read, and
   * are stored exactly as sent. The intervals themselves go to R2 in a second request, the
   * same two-step an activity and its telemetry use.
   */
  .post('/sleep', requireDevice, async (c) => {
    const principal = c.get('principal')
    await enforce(c.env.DB, SYNC_LIMIT, principal.deviceId as string)

    const body = await jsonBody(c.req.raw)
    const sourceUid = str(body, 'sourceUid', { max: 200 })
    const startTime = int(body, 'startTime')
    const endTime = int(body, 'endTime')
    if (endTime < startTime) fail('validation_failed', '"endTime" precedes "startTime".')

    const tzOffsetMinutes = optionalInt(body, 'tzOffsetMinutes') ?? 0
    const stages = (body['stages'] ?? {}) as Record<string, unknown>
    if (typeof stages !== 'object' || stages === null || Array.isArray(stages)) {
      fail('validation_failed', '"stages" must be an object of stage name to seconds.')
    }

    const existing = await c.env.DB.prepare(
      'SELECT id FROM sleep_sessions WHERE user_id = ? AND source_uid = ?',
    )
      .bind(principal.userId, sourceUid)
      .first<{ id: string }>()

    const id = existing?.id ?? crypto.randomUUID()
    const now = Date.now()
    const stageColumns = STAGE_COLUMNS.map(([, column]) => column)

    await c.env.DB.prepare(
      `INSERT INTO sleep_sessions (
         id, user_id, source_uid, source_package, title, start_time, end_time,
         tz_offset_minutes, local_date, total_seconds, time_in_bed_seconds,
         ${stageColumns.join(', ')}, stage_count, created_at, updated_at
       ) VALUES (?,?,?,?,?,?,?,?,?,?,?,${stageColumns.map(() => '?').join(',')},?,?,?)
       ON CONFLICT (user_id, source_uid) DO UPDATE SET
         source_package = excluded.source_package,
         title = excluded.title,
         start_time = excluded.start_time,
         end_time = excluded.end_time,
         tz_offset_minutes = excluded.tz_offset_minutes,
         local_date = excluded.local_date,
         total_seconds = excluded.total_seconds,
         time_in_bed_seconds = excluded.time_in_bed_seconds,
         ${stageColumns.map((column) => `${column} = excluded.${column}`).join(', ')},
         stage_count = excluded.stage_count,
         updated_at = excluded.updated_at`,
    )
      .bind(
        id,
        principal.userId,
        sourceUid,
        optionalStr(body, 'sourcePackage', { max: 200 }),
        optionalStr(body, 'title', { max: 200 }),
        startTime,
        endTime,
        tzOffsetMinutes,
        // A night is named for the morning it ended, so consecutive nights never collide on
        // one calendar day just because one bedtime fell either side of midnight.
        localDate(endTime, tzOffsetMinutes),
        int(body, 'totalSeconds'),
        optionalInt(body, 'timeInBedSeconds'),
        ...STAGE_COLUMNS.map(([name]) => optionalInt(stages, name)),
        optionalInt(body, 'stageCount') ?? 0,
        now,
        now,
      )
      .run()

    return c.json(
      { sleep: { id, stagesUploadPath: `/api/health-records/sleep/${id}/stages` } },
      existing ? 200 : 201,
    )
  })

  /** Nights, newest first. Stage totals come back with each row; no R2 object is touched. */
  .get('/sleep', async (c) => {
    const userId = c.get('principal').userId
    const { where, binds, limit } = timeWindow((k) => c.req.query(k), 'start_time')

    const { results } = await c.env.DB.prepare(
      `SELECT ${SLEEP_COLUMNS} FROM sleep_sessions
        WHERE ${where.join(' AND ')}
        ORDER BY start_time DESC, id DESC
        LIMIT ?`,
    )
      .bind(userId, ...binds, limit + 1)
      .all<SleepRow>()

    const hasMore = results.length > limit
    const page = hasMore ? results.slice(0, limit) : results
    const last = page[page.length - 1]

    return c.json({
      sleeps: page.map(sleepItem),
      nextCursor: hasMore && last ? encodeCursor(last.start_time, last.id) : null,
    })
  })

  .get('/sleep/:id', async (c) => {
    const row = await ownedSleep(c.env.DB, c.get('principal').userId, c.req.param('id'))
    return c.json({ sleep: sleepItem(row) })
  })

  /**
   * Uploads a night's hypnogram: the stage intervals, gzipped JSON, straight into R2.
   *
   * Byte routing only — the Worker checks ownership and streams. There is deliberately no code
   * path here that parses the body, so nothing at the edge can start deriving a stage total
   * from it.
   */
  .put('/sleep/:id/stages', requireDevice, async (c) => {
    const principal = c.get('principal')
    const id = c.req.param('id')
    const row = await ownedSleep(c.env.DB, principal.userId, id)

    if (!c.req.raw.body) fail('validation_failed', 'Body must be the stage interval list.')

    const key = sleepStagesKey(principal.userId, id, row.end_time, row.tz_offset_minutes)
    const object = await c.env.BLOBS.put(key, c.req.raw.body, {
      httpMetadata: {
        contentType: 'application/json',
        // Stored with whatever encoding the phone sent, and served back the same way. The web
        // client sniffs the gzip magic rather than trusting the header — see AGENT-NOTES.
        contentEncoding: c.req.header('content-encoding') ?? undefined,
      },
    })

    await c.env.DB.prepare(
      'UPDATE sleep_sessions SET stages_key = ?, stages_bytes = ?, updated_at = ? WHERE id = ?',
    )
      .bind(key, object?.size ?? null, Date.now(), id)
      .run()

    return c.body(null, 204)
  })

  /**
   * Ownership-checked R2 proxy for the hypnogram (R-004).
   *
   * Never `immutable`: a source can correct a night and the phone re-uploads it, so the object
   * is revalidated against its ETag rather than cached forever. Telemetry is served the same
   * way now, for the same reason — a route import rewrites it.
   */
  .get('/sleep/:id/stages', async (c) => {
    const row = await ownedSleep(c.env.DB, c.get('principal').userId, c.req.param('id'))
    // Not uploaded yet is "still syncing", not an error worth shouting about.
    if (!row.stages_key) fail('not_found', 'No stage detail for this night yet.')

    const object = await c.env.BLOBS.get(row.stages_key, { onlyIf: c.req.raw.headers })
    if (!object) fail('not_found', 'Stage detail object is missing.')
    if (!('body' in object) || object.body === null) return c.body(null, 304)

    const headers = new Headers()
    object.writeHttpMetadata(headers)
    headers.set('etag', object.httpEtag)
    headers.set('cache-control', 'private, max-age=0, must-revalidate')
    headers.set('content-type', 'application/json')

    return new Response(object.body, { status: 200, headers })
  })

/**
 * Loads a night the caller owns, or 404s.
 *
 * 404 and not 403 on a mismatch, for the same reason as everywhere else: a 403 confirms the
 * identifier exists and turns the id space into an enumeration oracle.
 */
async function ownedSleep(db: D1Database, userId: string, id: string): Promise<SleepRow> {
  const row = await db
    .prepare(`SELECT ${SLEEP_COLUMNS} FROM sleep_sessions WHERE id = ? AND user_id = ?`)
    .bind(id, userId)
    .first<SleepRow>()
  if (!row) fail('not_found', 'No such sleep session.')
  return row
}
