import type { Bindings } from '../types'
import { archiveMonthPrefix, archivePartKey, type ArchiveDataset } from '../lib/keys'
import { ACTIVITY_ARCHIVE_COLUMNS, encodeActivityPart, type ActivityArchiveRow } from './parquet'
import { putArchiveObject, type UploadOptions, type UploadResult } from './upload'

/**
 * Compaction of closed months into the analytical Parquet tier (R-013).
 *
 * The job is **assembly**: it reads activity rows the phone already computed, encodes them as
 * Parquet, and writes the objects. It sums nothing and averages nothing. The only aggregates
 * in this file are `COUNT(*)` and `MAX(updated_at)` over row *metadata*, used to decide
 * whether a month needs rebuilding at all — they never touch a distance, a heart rate or a
 * duration, and none of them reaches an athlete's screen.
 *
 * The `samples` dataset described in data-model.md is deliberately not produced here. Filling
 * it would mean opening every `.hht` object and decoding it in the Worker, which is precisely
 * what Principle I forbids; it belongs on the device that already has the samples in memory.
 *
 * ## Re-running a month
 *
 * A month is rebuilt from scratch whenever its activity rows have changed — a backfill can
 * land a ride in a month compacted six weeks ago. The rebuild must replace the month, never
 * add to it, and the failure that would be quietest is leaving one part of the previous
 * build behind: a query engine globbing `month=07/*.parquet` would then count some rides
 * twice, and nothing would ever complain. So the order here is fixed:
 *
 *   1. delete every object under the month's prefix that the new build will not produce,
 *   2. write the new parts over the stable, dense `part-NNNN` names,
 *   3. flip the D1 manifest in one batch.
 *
 * Deleting first means the prefix can transiently be missing rows, never holding them twice —
 * an undercount that the next nightly run repairs, rather than an overcount nobody notices.
 * At realistic volumes a month is a single part of a few kilobytes and step 2 is one atomic
 * PUT, so the window does not exist at all; the manifest is what keeps the multi-part case
 * honest, and `GET /api/archive` serves it so a reader can ask instead of guessing.
 */

/** Only `activities` is assembled here; `samples` is reserved. See the note above. */
const DATASET: ArchiveDataset = 'activities'

/**
 * How long after a month ends before it counts as closed.
 *
 * Partitioning is by the athlete's *local* date, and local dates run from UTC-12 to UTC+14,
 * so the last hours of a month are still being recorded somewhere long after the UTC month
 * has rolled over. Two days covers the spread and a phone that syncs the next morning.
 */
const MONTH_CLOSE_GRACE_MS = 48 * 60 * 60 * 1000

/** A daily cron with years of history behind it should do a bounded amount of work. */
const MAX_MONTHS_PER_RUN = 12

/**
 * Rows per part.
 *
 * R-013 targets 128–512 MB parts, and that target is unreachable *and irrelevant* here. It is
 * unreachable because a part is encoded in memory inside an isolate with a 128 MB ceiling; it
 * is irrelevant because one row per activity means a busy athlete's month is a few thousand
 * rows and a few hundred kilobytes. The small-files problem the target defends against is
 * about thousands of objects per query — a year of history is twelve. So the cut here is a
 * memory bound, not a size target, and in practice it never triggers.
 */
const ROWS_PER_PART = 50_000

export interface CompactionOptions {
  /** Defaults to `Date.now()`; the cron passes `controller.scheduledTime`. */
  now?: number
  maxMonthsPerRun?: number
  rowsPerPart?: number
  monthCloseGraceMs?: number
  upload?: UploadOptions
}

export interface CompactedMonth {
  userId: string
  dataset: ArchiveDataset
  year: number
  month: number
  generation: number
  parts: number
  rows: number
  bytes: number
  /** Objects from a previous, longer build that this one removed. */
  removedParts: number
}

export interface CompactionReport {
  startedAt: number
  finishedAt: number
  /** Athlete-months that currently hold activities. */
  candidates: number
  /** Unchanged since the last build. */
  unchanged: number
  /** Changed, but the month is not closed yet. */
  deferred: number
  compacted: CompactedMonth[]
  /** Months whose activities all disappeared: their parts were removed. */
  emptied: { userId: string; year: number; month: number; removedParts: number }[]
  failures: { userId: string; year: number; month: number; message: string }[]
}

interface CandidateRow {
  user_id: string
  ym: string
  row_count: number
  watermark: number
}

interface ManifestRow {
  user_id: string
  year: number
  month: number
  generation: number
  part_count: number
  source_watermark: number
  source_rows: number
}

export async function compactArchive(
  env: Bindings,
  options: CompactionOptions = {},
): Promise<CompactionReport> {
  const now = options.now ?? Date.now()
  const grace = options.monthCloseGraceMs ?? MONTH_CLOSE_GRACE_MS
  const maxMonths = options.maxMonthsPerRun ?? MAX_MONTHS_PER_RUN

  const report: CompactionReport = {
    startedAt: now,
    finishedAt: now,
    candidates: 0,
    unchanged: 0,
    deferred: 0,
    compacted: [],
    emptied: [],
    failures: [],
  }

  const candidates = await scanCandidates(env.DB)
  const manifest = await loadManifest(env.DB)
  report.candidates = candidates.length

  const seen = new Set<string>()
  const pending: { userId: string; year: number; month: number; candidate: CandidateRow }[] = []

  for (const candidate of candidates) {
    const parsed = parseMonth(candidate.ym)
    if (!parsed) continue
    const { year, month } = parsed
    const id = monthId(candidate.user_id, year, month)
    seen.add(id)

    const built = manifest.get(id)
    if (
      built &&
      built.source_watermark === candidate.watermark &&
      built.source_rows === candidate.row_count
    ) {
      report.unchanged++
      continue
    }
    if (!isClosed(year, month, now, grace)) {
      report.deferred++
      continue
    }
    pending.push({ userId: candidate.user_id, year, month, candidate })
  }

  // A month whose activities have all gone — every source removed, or an account emptied —
  // keeps its parts otherwise, and a query engine would keep counting them.
  for (const [id, built] of manifest) {
    if (seen.has(id)) continue
    const prefix = archiveMonthPrefix(built.user_id, DATASET, built.year, built.month)
    const removed = await removeObjects(env.BLOBS, await listPrefix(env.BLOBS, prefix))
    await env.DB.batch([
      env.DB.prepare(
        'DELETE FROM archive_parts WHERE user_id = ? AND dataset = ? AND year = ? AND month = ?',
      ).bind(built.user_id, DATASET, built.year, built.month),
      env.DB.prepare(
        'DELETE FROM archive_months WHERE user_id = ? AND dataset = ? AND year = ? AND month = ?',
      ).bind(built.user_id, DATASET, built.year, built.month),
    ])
    report.emptied.push({
      userId: built.user_id,
      year: built.year,
      month: built.month,
      removedParts: removed,
    })
  }

  // Oldest first, so a long backlog drains in an order the athlete can reason about.
  pending.sort((a, b) => a.year - b.year || a.month - b.month)

  for (const item of pending.slice(0, maxMonths)) {
    try {
      report.compacted.push(
        await compactMonth(env, item.userId, item.year, item.month, {
          watermark: item.candidate.watermark,
          sourceRows: item.candidate.row_count,
          generation:
            (manifest.get(monthId(item.userId, item.year, item.month))?.generation ?? 0) + 1,
          rowsPerPart: options.rowsPerPart ?? ROWS_PER_PART,
          upload: options.upload,
          now,
        }),
      )
    } catch (error) {
      // One athlete's bad month must not stop every other athlete's compaction.
      report.failures.push({
        userId: item.userId,
        year: item.year,
        month: item.month,
        message: error instanceof Error ? error.message : String(error),
      })
    }
  }

  report.finishedAt = Date.now()
  return report
}

/**
 * Every athlete-month that currently holds activities, with the two numbers that decide
 * whether it needs rebuilding.
 *
 * The month key is the athlete's *local* month — the same shift by `tz_offset_minutes` that
 * builds the R2 key — so a 23:40 ride on the 31st lands in the month the athlete would name.
 */
async function scanCandidates(db: D1Database): Promise<CandidateRow[]> {
  const { results } = await db
    .prepare(
      `SELECT user_id,
              strftime('%Y-%m', (start_time + tz_offset_minutes * 60000) / 1000, 'unixepoch') AS ym,
              COUNT(*) AS row_count,
              MAX(updated_at) AS watermark
         FROM activities
        WHERE deleted_at IS NULL
        GROUP BY user_id, ym`,
    )
    .all<CandidateRow>()
  return results
}

async function loadManifest(db: D1Database): Promise<Map<string, ManifestRow>> {
  const { results } = await db
    .prepare(
      `SELECT user_id, year, month, generation, part_count, source_watermark, source_rows
         FROM archive_months WHERE dataset = ?`,
    )
    .bind(DATASET)
    .all<ManifestRow>()
  return new Map(results.map((row) => [monthId(row.user_id, row.year, row.month), row]))
}

interface MonthBuild {
  watermark: number
  sourceRows: number
  generation: number
  rowsPerPart: number
  upload?: UploadOptions
  now: number
}

async function compactMonth(
  env: Bindings,
  userId: string,
  year: number,
  month: number,
  build: MonthBuild,
): Promise<CompactedMonth> {
  const ym = `${year}-${String(month).padStart(2, '0')}`
  const { results: rows } = await env.DB.prepare(
    `SELECT ${ACTIVITY_ARCHIVE_COLUMNS}
       FROM activities
      WHERE user_id = ?
        AND deleted_at IS NULL
        AND strftime('%Y-%m', (start_time + tz_offset_minutes * 60000) / 1000, 'unixepoch') = ?
      ORDER BY start_time ASC, id ASC`,
  )
    .bind(userId, ym)
    .all<ActivityArchiveRow>()

  const prefix = archiveMonthPrefix(userId, DATASET, year, month)
  const chunks = chunk(rows, build.rowsPerPart)
  const keys = chunks.map((_, index) => archivePartKey(prefix, index + 1))

  // Step 1: anything under the prefix that this build will not write is stale by definition —
  // the previous build's tail parts, or an object left by a run that died mid-write. Removing
  // it *before* writing is what makes a double count impossible; see the note at the top.
  const existing = await listPrefix(env.BLOBS, prefix)
  const wanted = new Set(keys)
  const removedParts = await removeObjects(
    env.BLOBS,
    existing.filter((key) => !wanted.has(key)),
  )

  // Step 2: write the parts.
  const written: (UploadResult & { rows: number; index: number })[] = []
  for (const [index, part] of chunks.entries()) {
    const key = keys[index]
    if (!key) continue
    const bytes = encodeActivityPart(part, {
      dataset: DATASET,
      year,
      month,
      generation: build.generation,
      partIndex: index + 1,
    })
    const result = await putArchiveObject(env.BLOBS, key, bytes, build.upload)
    written.push({ ...result, rows: part.length, index: index + 1 })
  }

  // Step 3: flip the manifest. A D1 batch runs as a single transaction, so the month's part
  // list is never half-replaced for anyone reading it through the API.
  const totalBytes = written.reduce((sum, part) => sum + part.bytes, 0)
  await env.DB.batch([
    env.DB.prepare(
      'DELETE FROM archive_parts WHERE user_id = ? AND dataset = ? AND year = ? AND month = ?',
    ).bind(userId, DATASET, year, month),
    ...written.map((part) =>
      env.DB.prepare(
        `INSERT INTO archive_parts
           (key, user_id, dataset, year, month, part_index, generation, row_count, bytes, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        part.key,
        userId,
        DATASET,
        year,
        month,
        part.index,
        build.generation,
        part.rows,
        part.bytes,
        build.now,
      ),
    ),
    env.DB.prepare(
      `INSERT INTO archive_months
         (user_id, dataset, year, month, generation, part_count, row_count, bytes,
          source_watermark, source_rows, compacted_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (user_id, dataset, year, month) DO UPDATE SET
         generation = excluded.generation,
         part_count = excluded.part_count,
         row_count = excluded.row_count,
         bytes = excluded.bytes,
         source_watermark = excluded.source_watermark,
         source_rows = excluded.source_rows,
         compacted_at = excluded.compacted_at`,
    ).bind(
      userId,
      DATASET,
      year,
      month,
      build.generation,
      written.length,
      rows.length,
      totalBytes,
      build.watermark,
      build.sourceRows,
      build.now,
    ),
  ])

  return {
    userId,
    dataset: DATASET,
    year,
    month,
    generation: build.generation,
    parts: written.length,
    rows: rows.length,
    bytes: totalBytes,
    removedParts,
  }
}

/** A month is closed once the athlete's local clock cannot still be inside it. */
function isClosed(year: number, month: number, now: number, grace: number): boolean {
  // Date.UTC takes a 0-based month, so passing the 1-based `month` gives the first instant of
  // the month after it.
  return Date.UTC(year, month, 1) + grace <= now
}

function parseMonth(ym: string): { year: number; month: number } | null {
  const match = /^(\d{4})-(\d{2})$/.exec(ym)
  if (!match) return null
  return { year: Number(match[1]), month: Number(match[2]) }
}

const monthId = (userId: string, year: number, month: number) => `${userId}|${year}|${month}`

function chunk<T>(items: T[], size: number): T[][] {
  if (items.length === 0) return []
  const out: T[][] = []
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size))
  return out
}

async function listPrefix(bucket: R2Bucket, prefix: string): Promise<string[]> {
  const keys: string[] = []
  let cursor: string | undefined
  do {
    const page = await bucket.list({ prefix, cursor })
    keys.push(...page.objects.map((object) => object.key))
    cursor = page.truncated ? page.cursor : undefined
  } while (cursor)
  return keys
}

async function removeObjects(bucket: R2Bucket, keys: string[]): Promise<number> {
  if (keys.length === 0) return 0
  // R2 takes at most 1000 keys per delete call.
  for (let i = 0; i < keys.length; i += 1000) {
    await bucket.delete(keys.slice(i, i + 1000))
  }
  return keys.length
}
