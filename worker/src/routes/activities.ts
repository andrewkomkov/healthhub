import { Hono } from 'hono'
import type { AppEnv } from '../types'
import { fail } from '../lib/errors'
import { authenticate, ownedActivity, requireDevice } from '../lib/guard'
import { activityPrefix } from '../lib/keys'
import { enforce, SYNC_LIMIT } from '../lib/ratelimit'
import {
  arr,
  bool,
  int,
  jsonBody,
  num,
  optionalInt,
  optionalNum,
  optionalStr,
  str,
} from '../lib/validate'

const FEED_COLUMNS = `id, sport, title, start_time, tz_offset_minutes, elapsed_seconds,
  moving_seconds, distance_m, elevation_gain_m, avg_speed_mps, avg_hr_bpm, has_gps,
  route_polyline, bounds_json`

interface FeedRow {
  id: string
  sport: string
  title: string
  start_time: number
  tz_offset_minutes: number
  elapsed_seconds: number
  moving_seconds: number | null
  distance_m: number | null
  elevation_gain_m: number | null
  avg_speed_mps: number | null
  avg_hr_bpm: number | null
  has_gps: number
  route_polyline: string | null
  bounds_json: string | null
}

const feedItem = (row: FeedRow) => ({
  id: row.id,
  sport: row.sport,
  title: row.title,
  startTime: row.start_time,
  tzOffsetMinutes: row.tz_offset_minutes,
  elapsedSeconds: row.elapsed_seconds,
  movingSeconds: row.moving_seconds,
  distanceM: row.distance_m,
  elevationGainM: row.elevation_gain_m,
  avgSpeedMps: row.avg_speed_mps,
  avgHrBpm: row.avg_hr_bpm,
  hasGps: row.has_gps === 1,
  routePolyline: row.route_polyline,
  bounds: row.bounds_json ? (JSON.parse(row.bounds_json) as number[]) : null,
})

/** Keyset cursors are opaque to the client but are just `startTime:id`. */
function encodeCursor(startTime: number, id: string): string {
  return btoa(`${startTime}:${id}`).replace(/=+$/, '')
}

function decodeCursor(cursor: string): { startTime: number; id: string } {
  let decoded: string
  try {
    decoded = atob(cursor)
  } catch {
    fail('validation_failed', 'Malformed cursor.')
  }
  const sep = decoded.indexOf(':')
  const startTime = Number(decoded.slice(0, sep))
  const id = decoded.slice(sep + 1)
  if (!Number.isFinite(startTime) || !id) fail('validation_failed', 'Malformed cursor.')
  return { startTime, id }
}

export const activityRoutes = new Hono<AppEnv>()

  .use('*', authenticate)

  /**
   * Uploads one activity's summary and precomputed metrics.
   *
   * Idempotent on (user_id, source_uid): re-posting the same Health Connect record updates
   * the existing row rather than creating a second one, which is what lets an interrupted
   * sync simply be retried (FR-003, SC-006).
   *
   * Splits and zones arrive already computed by the phone and are stored verbatim — the
   * Worker never recomputes them (Principle I).
   */
  .post('/', requireDevice, async (c) => {
    const principal = c.get('principal')
    await enforce(c.env.DB, SYNC_LIMIT, principal.deviceId as string)

    const body = await jsonBody(c.req.raw)
    const sourceUid = str(body, 'sourceUid', { max: 200 })
    const startTime = int(body, 'startTime')
    const endTime = int(body, 'endTime')
    if (endTime < startTime) fail('validation_failed', '"endTime" precedes "startTime".')

    const now = Date.now()
    const existing = await c.env.DB.prepare(
      'SELECT id FROM activities WHERE user_id = ? AND source_uid = ?',
    )
      .bind(principal.userId, sourceUid)
      .first<{ id: string }>()

    const id = existing?.id ?? crypto.randomUUID()
    const bounds = body['bounds']
    const channels = arr(body, 'channels', 64)

    await c.env.DB.prepare(
      `INSERT INTO activities (
         id, user_id, source_uid, source_device_id, sport, title, description,
         start_time, end_time, tz_offset_minutes, elapsed_seconds, moving_seconds,
         distance_m, elevation_gain_m, elevation_loss_m, calories_kcal,
         avg_speed_mps, max_speed_mps, avg_hr_bpm, max_hr_bpm, avg_cadence_rpm,
         avg_power_w, max_power_w, has_gps, route_polyline, bounds_json,
         sample_count, channels_json, created_at, updated_at
       ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
       ON CONFLICT (user_id, source_uid) DO UPDATE SET
         sport = excluded.sport,
         title = excluded.title,
         description = COALESCE(excluded.description, activities.description),
         start_time = excluded.start_time,
         end_time = excluded.end_time,
         tz_offset_minutes = excluded.tz_offset_minutes,
         elapsed_seconds = excluded.elapsed_seconds,
         moving_seconds = excluded.moving_seconds,
         distance_m = excluded.distance_m,
         elevation_gain_m = excluded.elevation_gain_m,
         elevation_loss_m = excluded.elevation_loss_m,
         calories_kcal = excluded.calories_kcal,
         avg_speed_mps = excluded.avg_speed_mps,
         max_speed_mps = excluded.max_speed_mps,
         avg_hr_bpm = excluded.avg_hr_bpm,
         max_hr_bpm = excluded.max_hr_bpm,
         avg_cadence_rpm = excluded.avg_cadence_rpm,
         avg_power_w = excluded.avg_power_w,
         max_power_w = excluded.max_power_w,
         has_gps = excluded.has_gps,
         route_polyline = excluded.route_polyline,
         bounds_json = excluded.bounds_json,
         sample_count = excluded.sample_count,
         channels_json = excluded.channels_json,
         deleted_at = NULL,
         updated_at = excluded.updated_at`,
    )
      .bind(
        id,
        principal.userId,
        sourceUid,
        principal.deviceId ?? null,
        str(body, 'sport', { max: 40 }),
        str(body, 'title', { max: 200 }),
        optionalStr(body, 'description', { max: 4000 }),
        startTime,
        endTime,
        optionalInt(body, 'tzOffsetMinutes') ?? 0,
        int(body, 'elapsedSeconds'),
        optionalInt(body, 'movingSeconds'),
        optionalNum(body, 'distanceM'),
        optionalNum(body, 'elevationGainM'),
        optionalNum(body, 'elevationLossM'),
        optionalNum(body, 'caloriesKcal'),
        optionalNum(body, 'avgSpeedMps'),
        optionalNum(body, 'maxSpeedMps'),
        optionalInt(body, 'avgHrBpm'),
        optionalInt(body, 'maxHrBpm'),
        optionalNum(body, 'avgCadenceRpm'),
        optionalNum(body, 'avgPowerW'),
        optionalNum(body, 'maxPowerW'),
        bool(body, 'hasGps') ? 1 : 0,
        optionalStr(body, 'routePolyline', { max: 200_000 }),
        Array.isArray(bounds) ? JSON.stringify(bounds) : null,
        optionalInt(body, 'sampleCount') ?? 0,
        JSON.stringify(channels),
        now,
        now,
      )
      .run()

    await replaceDerived(c.env.DB, id, body)

    return c.json(
      { activity: { id, telemetryUploadPath: `/api/activities/${id}/telemetry` } },
      existing ? 200 : 201,
    )
  })

  /** The feed query (FR-010, FR-013, FR-015). One indexed read; no R2 access. */
  .get('/', async (c) => {
    const userId = c.get('principal').userId
    const limit = Math.min(Math.max(Number(c.req.query('limit') ?? 30), 1), 100)
    const sport = c.req.query('sport')
    const from = c.req.query('from')
    const to = c.req.query('to')
    const cursor = c.req.query('cursor')

    const where: string[] = ['user_id = ?', 'deleted_at IS NULL']
    const binds: (string | number)[] = [userId]

    if (sport) {
      where.push('sport = ?')
      binds.push(sport)
    }
    if (from) {
      where.push('start_time >= ?')
      binds.push(Number(from))
    }
    if (to) {
      where.push('start_time <= ?')
      binds.push(Number(to))
    }
    if (cursor) {
      // Keyset pagination: stable even as new activities arrive at the head.
      const { startTime, id } = decodeCursor(cursor)
      where.push('(start_time < ? OR (start_time = ? AND id < ?))')
      binds.push(startTime, startTime, id)
    }

    const { results } = await c.env.DB.prepare(
      `SELECT ${FEED_COLUMNS} FROM activities
       WHERE ${where.join(' AND ')}
       ORDER BY start_time DESC, id DESC
       LIMIT ?`,
    )
      .bind(...binds, limit + 1)
      .all<FeedRow>()

    const hasMore = results.length > limit
    const page = hasMore ? results.slice(0, limit) : results
    const last = page[page.length - 1]

    return c.json({
      activities: page.map(feedItem),
      nextCursor: hasMore && last ? encodeCursor(last.start_time, last.id) : null,
    })
  })

  /** Full detail, including splits and zones. Still no R2 access. */
  .get('/:id', async (c) => {
    const userId = c.get('principal').userId
    const id = c.req.param('id')
    const row = await ownedActivity<Record<string, unknown>>(c.env.DB, userId, id)

    const [splits, zones] = await Promise.all([
      c.env.DB.prepare(
        `SELECT idx, unit, distance_m, elapsed_seconds, moving_seconds, avg_speed_mps,
                elevation_gain_m, elevation_loss_m, avg_hr_bpm, avg_power_w
         FROM activity_splits WHERE activity_id = ? ORDER BY unit, idx`,
      )
        .bind(id)
        .all(),
      c.env.DB.prepare(
        `SELECT kind, zone_index, lower_bound, upper_bound, seconds
         FROM activity_zones WHERE activity_id = ? ORDER BY kind, zone_index`,
      )
        .bind(id)
        .all(),
    ])

    return c.json({
      activity: {
        ...feedItem(row as unknown as FeedRow),
        description: row['description'],
        endTime: row['end_time'],
        elevationLossM: row['elevation_loss_m'],
        caloriesKcal: row['calories_kcal'],
        maxSpeedMps: row['max_speed_mps'],
        maxHrBpm: row['max_hr_bpm'],
        avgCadenceRpm: row['avg_cadence_rpm'],
        avgPowerW: row['avg_power_w'],
        maxPowerW: row['max_power_w'],
        sampleCount: row['sample_count'],
        channels: JSON.parse((row['channels_json'] as string) ?? '[]') as string[],
        telemetry: {
          full: Boolean(row['telemetry_key']),
          preview: Boolean(row['preview_key']),
          bytes: row['telemetry_bytes'] ?? null,
        },
        splits: splits.results.map((s) => ({
          idx: s['idx'],
          unit: s['unit'],
          distanceM: s['distance_m'],
          elapsedSeconds: s['elapsed_seconds'],
          movingSeconds: s['moving_seconds'],
          avgSpeedMps: s['avg_speed_mps'],
          elevationGainM: s['elevation_gain_m'],
          elevationLossM: s['elevation_loss_m'],
          avgHrBpm: s['avg_hr_bpm'],
          avgPowerW: s['avg_power_w'],
        })),
        zones: zones.results.map((z) => ({
          kind: z['kind'],
          zoneIndex: z['zone_index'],
          lowerBound: z['lower_bound'],
          upperBound: z['upper_bound'],
          seconds: z['seconds'],
        })),
      },
    })
  })

  /** Rename and describe (FR-016). */
  .patch('/:id', async (c) => {
    const userId = c.get('principal').userId
    const id = c.req.param('id')
    await ownedActivity(c.env.DB, userId, id, 'id')

    const body = await jsonBody(c.req.raw)
    const title = optionalStr(body, 'title', { max: 200 })
    const description = optionalStr(body, 'description', { max: 4000 })

    if (title !== null) {
      await c.env.DB.prepare('UPDATE activities SET title = ?, updated_at = ? WHERE id = ?')
        .bind(title, Date.now(), id)
        .run()
    }
    if (description !== null) {
      await c.env.DB.prepare('UPDATE activities SET description = ?, updated_at = ? WHERE id = ?')
        .bind(description, Date.now(), id)
        .run()
    }

    const row = await ownedActivity<FeedRow & { description: string | null }>(c.env.DB, userId, id)
    return c.json({ activity: { ...feedItem(row), description: row.description } })
  })

  .delete('/:id', async (c) => {
    const userId = c.get('principal').userId
    const id = c.req.param('id')
    const row = await ownedActivity<{ start_time: number; tz_offset_minutes: number }>(
      c.env.DB,
      userId,
      id,
      'start_time, tz_offset_minutes',
    )

    const prefix = activityPrefix(userId, id, row.start_time, row.tz_offset_minutes)
    await c.env.BLOBS.delete([`${prefix}/full.hht`, `${prefix}/preview.hht`])

    const db = c.env.DB
    await db.batch([
      db.prepare('DELETE FROM activity_splits WHERE activity_id = ?').bind(id),
      db.prepare('DELETE FROM activity_zones WHERE activity_id = ?').bind(id),
      db.prepare('DELETE FROM activities WHERE id = ? AND user_id = ?').bind(id, userId),
    ])

    return c.body(null, 204)
  })

/**
 * Replaces an activity's derived rows.
 *
 * Delete-then-insert rather than upsert: a re-sync may produce a different number of splits
 * (a corrected distance changes the kilometre count), and leftovers from the previous run
 * would silently corrupt the splits table.
 */
async function replaceDerived(
  db: D1Database,
  activityId: string,
  body: Record<string, unknown>,
): Promise<void> {
  const splits = arr(body, 'splits', 5000)
  const zones = arr(body, 'zones', 40)

  const statements: D1PreparedStatement[] = [
    db.prepare('DELETE FROM activity_splits WHERE activity_id = ?').bind(activityId),
    db.prepare('DELETE FROM activity_zones WHERE activity_id = ?').bind(activityId),
  ]

  for (const raw of splits) {
    const s = raw as Record<string, unknown>
    statements.push(
      db
        .prepare(
          `INSERT INTO activity_splits (activity_id, idx, unit, distance_m, elapsed_seconds,
             moving_seconds, avg_speed_mps, elevation_gain_m, elevation_loss_m, avg_hr_bpm, avg_power_w)
           VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
        )
        .bind(
          activityId,
          int(s, 'idx'),
          s['unit'] === 'mi' ? 'mi' : 'km',
          num(s, 'distanceM'),
          num(s, 'elapsedSeconds'),
          optionalNum(s, 'movingSeconds'),
          optionalNum(s, 'avgSpeedMps'),
          optionalNum(s, 'elevationGainM'),
          optionalNum(s, 'elevationLossM'),
          optionalInt(s, 'avgHrBpm'),
          optionalNum(s, 'avgPowerW'),
        ),
    )
  }

  for (const raw of zones) {
    const z = raw as Record<string, unknown>
    statements.push(
      db
        .prepare(
          `INSERT INTO activity_zones (activity_id, kind, zone_index, lower_bound, upper_bound, seconds)
           VALUES (?,?,?,?,?,?)`,
        )
        .bind(
          activityId,
          z['kind'] === 'power' ? 'power' : 'hr',
          int(z, 'zoneIndex'),
          num(z, 'lowerBound'),
          optionalNum(z, 'upperBound'),
          num(z, 'seconds'),
        ),
    )
  }

  await db.batch(statements)
}
