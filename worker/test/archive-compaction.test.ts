import { createExecutionContext, env, waitOnExecutionContext } from 'cloudflare:test'
import { describe, expect, it } from 'vitest'
// hyparquet is hyparquet-writer's single, exactly-pinned dependency. Reading the parts back
// is the only way to prove what the compaction job actually wrote — a byte count would pass
// just as happily for a file with the month in it twice.
import { parquetReadObjects } from 'hyparquet'
import worker from '../src/index'
import { compactArchive } from '../src/archive/compaction'
import {
  MIN_PART_SIZE_BYTES,
  MULTIPART_THRESHOLD_BYTES,
  PART_SIZE_BYTES,
  putArchiveObject,
} from '../src/archive/upload'
import { archiveMonthPrefix } from '../src/lib/keys'
import type { Bindings } from '../src/types'

const bindings = env as unknown as Bindings

/**
 * The analytical tier, tested against real D1 and R2 in workerd.
 *
 * The property under test throughout is the one that would fail quietly: re-running a month
 * must *replace* it. An extra object left under a month's prefix is invisible to every
 * assertion about the rows the job wrote, and shows up only as an athlete's year totalling
 * more kilometres than they rode. So every re-run assertion reads back **everything under the
 * prefix**, not the parts the job says it wrote.
 */

/** Fixed "now" well after the months under test, so closedness is never in question. */
const NOW = Date.UTC(2026, 6, 27, 4, 0, 0)

let seq = 0

async function newUser(): Promise<string> {
  const id = `archive-user-${Date.now()}-${seq++}`
  await bindings.DB.prepare(
    `INSERT INTO users (id, email, email_norm, password_hash, display_name, created_at)
     VALUES (?, ?, ?, 'x', 'Archive Athlete', ?)`,
  )
    .bind(id, `${id}@example.com`, `${id}@example.com`, Date.now())
    .run()
  return id
}

interface SeedOptions {
  startTime: number
  sport?: string
  distanceM?: number
  updatedAt?: number
  tzOffsetMinutes?: number
}

async function seedActivity(userId: string, id: string, options: SeedOptions): Promise<void> {
  const updatedAt = options.updatedAt ?? options.startTime
  await bindings.DB.prepare(
    `INSERT INTO activities
       (id, user_id, source_uid, source_package, sport, title, start_time, end_time,
        tz_offset_minutes, elapsed_seconds, moving_seconds, distance_m, avg_hr_bpm, has_gps,
        sample_count, created_at, updated_at)
     VALUES (?, ?, ?, 'app.test', ?, ?, ?, ?, ?, 3600, NULL, ?, 138, 1, 1000, ?, ?)`,
  )
    .bind(
      id,
      userId,
      `uid-${id}`,
      options.sport ?? 'cycling',
      `Ride ${id}`,
      options.startTime,
      options.startTime + 3_600_000,
      options.tzOffsetMinutes ?? 180,
      options.distanceM ?? 30_000,
      updatedAt,
      updatedAt,
    )
    .run()
}

/** Every object under a prefix, which is what a query engine's glob would see. */
async function listKeys(prefix: string): Promise<string[]> {
  const listed = await bindings.BLOBS.list({ prefix })
  return listed.objects.map((object) => object.key).sort()
}

/** Every row a query engine would read from that prefix, parts included whether we meant to
 * write them or not. */
async function readRows(prefix: string): Promise<Record<string, unknown>[]> {
  const rows: Record<string, unknown>[] = []
  for (const key of await listKeys(prefix)) {
    const object = await bindings.BLOBS.get(key)
    expect(object).not.toBeNull()
    rows.push(...(await parquetReadObjects({ file: await object!.arrayBuffer() })))
  }
  return rows
}

/** The job scans every athlete, so a report is only meaningful filtered to one of them. */
const mine = (report: Awaited<ReturnType<typeof compactArchive>>, userId: string) =>
  report.compacted.filter((month) => month.userId === userId)

const run = (userId: string, overrides: Parameters<typeof compactArchive>[1] = {}) =>
  compactArchive(bindings, { now: NOW, maxMonthsPerRun: 500, ...overrides }).then((report) => {
    expect(report.failures.filter((failure) => failure.userId === userId)).toEqual([])
    return report
  })

describe('archive compaction', () => {
  it('compacts a closed month and leaves the current one alone', async () => {
    const userId = await newUser()
    await seedActivity(userId, 'a1', { startTime: Date.UTC(2020, 2, 3, 8), distanceM: 30_000 })
    await seedActivity(userId, 'a2', { startTime: Date.UTC(2020, 2, 15, 8), distanceM: 42_000 })
    // 01:00 UTC on 1 April, five hours west: April by the clock on the wall in Greenwich,
    // 20:00 on 31 March for the athlete. Partitioning is by their date, so this is March.
    await seedActivity(userId, 'a3', {
      startTime: Date.UTC(2020, 3, 1, 1),
      tzOffsetMinutes: -300,
    })
    // The current month is still open; it must not be touched.
    await seedActivity(userId, 'a4', { startTime: Date.UTC(2026, 6, 20, 8) })

    const report = await run(userId)
    expect(mine(report, userId)).toEqual([
      {
        userId,
        dataset: 'activities',
        year: 2020,
        month: 3,
        generation: 1,
        parts: 1,
        rows: 3,
        bytes: expect.any(Number),
        removedParts: 0,
      },
    ])

    const march = archiveMonthPrefix(userId, 'activities', 2020, 3)
    expect(await listKeys(march)).toEqual([`${march}part-0001.parquet`])
    expect(await listKeys(archiveMonthPrefix(userId, 'activities', 2026, 7))).toEqual([])

    const rows = await readRows(march)
    expect(rows.map((row) => row.activity_id)).toEqual(['a1', 'a2', 'a3'])
    // Values are copied out of D1, never recomputed.
    expect(rows.map((row) => row.distance_m)).toEqual([30_000, 42_000, 30_000])
    expect(rows[0]!.sport).toBe('cycling')
    expect(rows[0]!.local_date).toBe('2020-03-03')
    expect(rows[2]!.local_date).toBe('2020-03-31')
    // A missing moving time stays missing. Zero is not a measurement.
    expect(rows[0]!.moving_seconds).toBeNull()
  })

  it('skips a month nothing has changed in', async () => {
    const userId = await newUser()
    await seedActivity(userId, 'b1', { startTime: Date.UTC(2021, 4, 2, 8) })

    expect(mine(await run(userId), userId)).toHaveLength(1)

    const second = await run(userId)
    expect(mine(second, userId)).toEqual([])
    expect(second.unchanged).toBeGreaterThan(0)
  })

  it('rebuilds a month a backfill added to, without counting anything twice', async () => {
    const userId = await newUser()
    await seedActivity(userId, 'c1', { startTime: Date.UTC(2021, 7, 2, 8) })
    await seedActivity(userId, 'c2', { startTime: Date.UTC(2021, 7, 9, 8) })
    await run(userId)

    // A late sync finds a third ride in a month that was compacted long ago.
    await seedActivity(userId, 'c3', {
      startTime: Date.UTC(2021, 7, 5, 8),
      updatedAt: Date.UTC(2026, 6, 26),
    })

    const report = await run(userId)
    expect(mine(report, userId)[0]).toMatchObject({ year: 2021, month: 8, rows: 3, generation: 2 })

    const august = archiveMonthPrefix(userId, 'activities', 2021, 8)
    const rows = await readRows(august)
    expect(rows.map((row) => row.activity_id)).toEqual(['c1', 'c3', 'c2'])
    expect(rows).toHaveLength(3)
  })

  /**
   * The one that matters.
   *
   * A month that shrinks — an athlete deleting a duplicated source, an account tidied up —
   * produces fewer parts than the build before it. If the surplus parts are left behind, the
   * manifest looks perfect, the job reports the right row count, and the athlete's own query
   * silently reads the deleted rides forever. Nothing else in this suite would notice.
   */
  it('leaves no orphaned part when a rebuild produces fewer of them', async () => {
    const userId = await newUser()
    await seedActivity(userId, 'd1', { startTime: Date.UTC(2022, 1, 3, 8), distanceM: 10_000 })
    await seedActivity(userId, 'd2', { startTime: Date.UTC(2022, 1, 10, 8), distanceM: 20_000 })
    await seedActivity(userId, 'd3', { startTime: Date.UTC(2022, 1, 17, 8), distanceM: 30_000 })

    // One row per part, so the month is genuinely multi-part.
    expect(mine(await run(userId, { rowsPerPart: 1 }), userId)[0]).toMatchObject({ parts: 3 })

    const february = archiveMonthPrefix(userId, 'activities', 2022, 2)
    expect(await listKeys(february)).toEqual([
      `${february}part-0001.parquet`,
      `${february}part-0002.parquet`,
      `${february}part-0003.parquet`,
    ])

    await bindings.DB.prepare(
      'UPDATE activities SET deleted_at = ?, updated_at = ? WHERE id IN (?, ?)',
    )
      .bind(NOW, NOW, 'd2', 'd3')
      .run()

    const report = await run(userId, { rowsPerPart: 1 })
    expect(mine(report, userId)[0]).toMatchObject({ parts: 1, rows: 1, removedParts: 2 })

    // The prefix — not the manifest — is what a `month=02/*.parquet` glob reads.
    expect(await listKeys(february)).toEqual([`${february}part-0001.parquet`])

    const rows = await readRows(february)
    expect(rows).toHaveLength(1)
    expect(rows[0]!.activity_id).toBe('d1')
    expect(rows.reduce((sum, row) => sum + Number(row.distance_m), 0)).toBe(10_000)

    const parts = await bindings.DB.prepare(
      'SELECT key FROM archive_parts WHERE user_id = ? ORDER BY part_index',
    )
      .bind(userId)
      .all<{ key: string }>()
    expect(parts.results.map((part) => part.key)).toEqual([`${february}part-0001.parquet`])
  })

  it('removes a month whose activities have all gone', async () => {
    const userId = await newUser()
    await seedActivity(userId, 'e1', { startTime: Date.UTC(2022, 5, 4, 8) })
    await run(userId)

    const june = archiveMonthPrefix(userId, 'activities', 2022, 6)
    expect(await listKeys(june)).toHaveLength(1)

    await bindings.DB.prepare('UPDATE activities SET deleted_at = ? WHERE id = ?')
      .bind(NOW, 'e1')
      .run()

    const report = await run(userId)
    expect(report.emptied).toContainEqual({ userId, year: 2022, month: 6, removedParts: 1 })
    expect(await listKeys(june)).toEqual([])
    const months = await bindings.DB.prepare(
      'SELECT count(*) AS n FROM archive_months WHERE user_id = ?',
    )
      .bind(userId)
      .first<{ n: number }>()
    expect(months?.n).toBe(0)
  })

  it('keeps every athlete inside their own prefix', async () => {
    const first = await newUser()
    const second = await newUser()
    await seedActivity(first, 'f1', { startTime: Date.UTC(2023, 3, 4, 8) })
    await seedActivity(second, 'g1', { startTime: Date.UTC(2023, 3, 5, 8) })

    await run(first)

    const rowsOfFirst = await readRows(archiveMonthPrefix(first, 'activities', 2023, 4))
    const rowsOfSecond = await readRows(archiveMonthPrefix(second, 'activities', 2023, 4))
    expect(rowsOfFirst.map((row) => row.activity_id)).toEqual(['f1'])
    expect(rowsOfSecond.map((row) => row.activity_id)).toEqual(['g1'])
    expect(await listKeys(`u/${first}/`)).toHaveLength(1)
  })

  it('runs from the scheduled handler', async () => {
    const userId = await newUser()
    await seedActivity(userId, 'h1', { startTime: Date.UTC(2024, 8, 9, 8) })

    const ctx = createExecutionContext()
    await worker.scheduled(
      { cron: '0 4 * * *', scheduledTime: Date.now(), noRetry: () => undefined },
      bindings,
      ctx,
    )
    await waitOnExecutionContext(ctx)

    expect(await listKeys(archiveMonthPrefix(userId, 'activities', 2024, 9))).toHaveLength(1)
  })
})

describe('archive upload', () => {
  it('uses the documented thresholds', () => {
    expect(MULTIPART_THRESHOLD_BYTES).toBe(100 * 1024 * 1024)
    expect(PART_SIZE_BYTES).toBe(8 * 1024 * 1024)
  })

  it('puts a small object in one request', async () => {
    const bytes = new Uint8Array(1024).fill(7)
    const result = await putArchiveObject(bindings.BLOBS, 'test/upload/small.parquet', bytes)
    expect(result).toMatchObject({ parts: 1, multipart: false, bytes: 1024 })
  })

  /**
   * The real multipart path, at the smallest part size R2 accepts. The production numbers are
   * 100 MB and 8 MB, and neither fits in a 128 MB isolate alongside the buffer being written —
   * so the constants are asserted above and the behaviour is exercised here.
   */
  it('splits a large object into parts and reassembles it exactly', async () => {
    const partSize = 5 * 1024 * 1024
    const total = partSize * 2 + 4096
    const bytes = new Uint8Array(total)
    for (let i = 0; i < total; i++) bytes[i] = i % 251

    const result = await putArchiveObject(bindings.BLOBS, 'test/upload/large.bin', bytes, {
      multipartThresholdBytes: partSize,
      partSizeBytes: partSize,
    })
    expect(result).toMatchObject({ parts: 3, multipart: true, bytes: total })

    const stored = new Uint8Array(
      await (await bindings.BLOBS.get('test/upload/large.bin'))!.arrayBuffer(),
    )
    expect(stored.byteLength).toBe(total)
    // Part boundaries are where a mis-ordered or duplicated part would show.
    for (const offset of [0, partSize - 1, partSize, 2 * partSize, total - 1]) {
      expect(stored[offset]).toBe(offset % 251)
    }
  })

  /**
   * The same "replace, never add to" property as the compaction tests above, one layer down.
   *
   * A month that stays multi-part across a rebuild writes over a key whose previous value was
   * itself assembled from parts. If a completed multipart upload merged into what was already
   * at the key instead of replacing it, every assertion in this file would still pass — the
   * job wrote the right rows, the manifest names the right keys — and the athlete's totals
   * would grow a little every night. So this asserts the *whole stored object*, not its head:
   * a shorter second write must leave nothing of the longer first one behind.
   */
  it('replaces a multipart object wholesale instead of merging into it', async () => {
    const partSize = MIN_PART_SIZE_BYTES
    const key = 'test/upload/replaced.bin'
    const options = { multipartThresholdBytes: partSize, partSizeBytes: partSize }

    const first = new Uint8Array(partSize * 2 + 1024).fill(0xaa)
    expect(await putArchiveObject(bindings.BLOBS, key, first, options)).toMatchObject({
      parts: 3,
      multipart: true,
    })

    // Shorter, and still multipart: the case where a leftover tail would be invisible.
    const second = new Uint8Array(partSize + 512).fill(0xbb)
    expect(await putArchiveObject(bindings.BLOBS, key, second, options)).toMatchObject({
      parts: 2,
      multipart: true,
      bytes: second.byteLength,
    })

    const stored = new Uint8Array(await (await bindings.BLOBS.get(key))!.arrayBuffer())
    expect(stored.byteLength).toBe(second.byteLength)
    expect(stored.every((byte) => byte === 0xbb)).toBe(true)
  })
})
