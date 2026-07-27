import { parquetWriteBuffer } from 'hyparquet-writer'
import { localDate, type ArchiveDataset } from '../lib/keys'

/**
 * Parquet assembly for the analytical tier.
 *
 * This module encodes rows; it does not compute them. Every number below is copied out of the
 * D1 column the phone wrote it into — no sum, no average, no re-derivation (Principle I).
 * That is what makes a query engine part of this architecture rather than a hole in it.
 *
 * **Codec: SNAPPY, decided here and deliberately not ZSTD.** R-013 asks for zstd; the runtime
 * does not offer it. `hyparquet-writer` ships one compressor (snappy) and takes a
 * *synchronous* `(bytes) => bytes` function for any other codec, while every compression
 * primitive workerd exposes is the asynchronous `CompressionStream` — which covers gzip,
 * deflate and deflate-raw, and rejects `'zstd'` outright. So there is no way to produce real
 * zstd pages here without shipping a pure-JS implementation. Asking for `codec: 'ZSTD'`
 * anyway is the trap: the writer falls back to `compressors[codec]?.(bytes) ?? bytes` and
 * emits *uncompressed* pages labelled ZSTD, which every reader refuses to open. Snappy is
 * read natively by DuckDB, ClickHouse and Polars, is splittable, and on this data shape gives
 * up a fraction of what partition pruning already saves.
 */
const CODEC = 'SNAPPY' as const

/**
 * The columns lifted from D1 into the `activities` dataset, in the order they are read.
 *
 * `route_polyline`, `bounds_json` and `channels_json` are excluded on purpose: they are per
 * activity display data, not analytical dimensions, and they would multiply the part size for
 * a column no aggregate query touches. The interactive tier already serves them.
 */
export const ACTIVITY_ARCHIVE_COLUMNS = `id, source_uid, source_package, source_count, sport,
  title, start_time, end_time, tz_offset_minutes, elapsed_seconds, moving_seconds, distance_m,
  elevation_gain_m, elevation_loss_m, calories_kcal, avg_speed_mps, max_speed_mps, avg_hr_bpm,
  max_hr_bpm, avg_cadence_rpm, avg_power_w, max_power_w, has_gps, sample_count, visibility,
  archived_reason, created_at, updated_at`

export interface ActivityArchiveRow {
  id: string
  source_uid: string
  source_package: string | null
  source_count: number | null
  sport: string
  title: string
  start_time: number
  end_time: number
  tz_offset_minutes: number
  elapsed_seconds: number
  moving_seconds: number | null
  distance_m: number | null
  elevation_gain_m: number | null
  elevation_loss_m: number | null
  calories_kcal: number | null
  avg_speed_mps: number | null
  max_speed_mps: number | null
  avg_hr_bpm: number | null
  max_hr_bpm: number | null
  avg_cadence_rpm: number | null
  avg_power_w: number | null
  max_power_w: number | null
  has_gps: number
  sample_count: number
  visibility: string
  archived_reason: string | null
  created_at: number
  updated_at: number
}

export interface PartMetadata {
  dataset: ArchiveDataset
  year: number
  month: number
  generation: number
  partIndex: number
}

/**
 * Encodes one part of the `activities` dataset.
 *
 * Nullability is per column and matches D1: a missing moving time is `null`, never zero.
 * Zero is not a measurement — storing it as one is the mistake that made the feed show
 * "0:00" for real workouts, and it would be far worse in a table someone runs `avg()` over.
 */
export function encodeActivityPart(rows: ActivityArchiveRow[], meta: PartMetadata): Uint8Array {
  const strings = (pick: (row: ActivityArchiveRow) => string) => rows.map(pick)
  const nullableStrings = (pick: (row: ActivityArchiveRow) => string | null) => rows.map(pick)
  const numbers = (pick: (row: ActivityArchiveRow) => number) => rows.map(pick)
  const instants = (pick: (row: ActivityArchiveRow) => number) =>
    rows.map((row) => new Date(pick(row)))
  const nullableNumbers = (pick: (row: ActivityArchiveRow) => number | null) => rows.map(pick)

  const buffer = parquetWriteBuffer({
    codec: CODEC,
    columnData: [
      { name: 'activity_id', type: 'STRING', data: strings((r) => r.id) },
      { name: 'source_uid', type: 'STRING', data: strings((r) => r.source_uid) },
      {
        name: 'source_package',
        type: 'STRING',
        nullable: true,
        data: nullableStrings((r) => r.source_package),
      },
      {
        name: 'source_count',
        type: 'INT32',
        nullable: true,
        data: nullableNumbers((r) => r.source_count),
      },
      { name: 'sport', type: 'STRING', data: strings((r) => r.sport) },
      { name: 'title', type: 'STRING', data: strings((r) => r.title) },

      // Instants stay UTC milliseconds with a TIMESTAMP logical type, so DuckDB reads them as
      // timestamps without a cast. The offset travels alongside for anyone reconstructing the
      // athlete's wall clock — the same pair D1 stores, for the same reason.
      //
      // They are `Date` objects and not the epoch numbers D1 hands back, because a TIMESTAMP
      // column's page statistics are only derived from a Date: give it a number and the writer
      // throws `unsupported type for statistics: INT64`, halfway through the file.
      { name: 'start_time', type: 'TIMESTAMP', data: instants((r) => r.start_time) },
      { name: 'end_time', type: 'TIMESTAMP', data: instants((r) => r.end_time) },
      { name: 'tz_offset_minutes', type: 'INT32', data: numbers((r) => r.tz_offset_minutes) },
      {
        name: 'local_date',
        type: 'STRING',
        data: strings((r) => localDate(r.start_time, r.tz_offset_minutes)),
      },

      { name: 'elapsed_seconds', type: 'INT32', data: numbers((r) => r.elapsed_seconds) },
      {
        name: 'moving_seconds',
        type: 'INT32',
        nullable: true,
        data: nullableNumbers((r) => r.moving_seconds),
      },
      {
        name: 'distance_m',
        type: 'DOUBLE',
        nullable: true,
        data: nullableNumbers((r) => r.distance_m),
      },
      {
        name: 'elevation_gain_m',
        type: 'DOUBLE',
        nullable: true,
        data: nullableNumbers((r) => r.elevation_gain_m),
      },
      {
        name: 'elevation_loss_m',
        type: 'DOUBLE',
        nullable: true,
        data: nullableNumbers((r) => r.elevation_loss_m),
      },
      {
        name: 'calories_kcal',
        type: 'DOUBLE',
        nullable: true,
        data: nullableNumbers((r) => r.calories_kcal),
      },
      {
        name: 'avg_speed_mps',
        type: 'DOUBLE',
        nullable: true,
        data: nullableNumbers((r) => r.avg_speed_mps),
      },
      {
        name: 'max_speed_mps',
        type: 'DOUBLE',
        nullable: true,
        data: nullableNumbers((r) => r.max_speed_mps),
      },
      {
        name: 'avg_hr_bpm',
        type: 'INT32',
        nullable: true,
        data: nullableNumbers((r) => r.avg_hr_bpm),
      },
      {
        name: 'max_hr_bpm',
        type: 'INT32',
        nullable: true,
        data: nullableNumbers((r) => r.max_hr_bpm),
      },
      {
        name: 'avg_cadence_rpm',
        type: 'DOUBLE',
        nullable: true,
        data: nullableNumbers((r) => r.avg_cadence_rpm),
      },
      {
        name: 'avg_power_w',
        type: 'DOUBLE',
        nullable: true,
        data: nullableNumbers((r) => r.avg_power_w),
      },
      {
        name: 'max_power_w',
        type: 'DOUBLE',
        nullable: true,
        data: nullableNumbers((r) => r.max_power_w),
      },

      { name: 'has_gps', type: 'BOOLEAN', data: rows.map((r) => r.has_gps === 1) },
      { name: 'sample_count', type: 'INT32', data: numbers((r) => r.sample_count) },

      // Archived duplicates are in the archive tier too — hidden is not deleted. The column
      // is here so a whole-history query can decide for itself, which is the only place that
      // decision can honestly be made.
      { name: 'visibility', type: 'STRING', data: strings((r) => r.visibility) },
      {
        name: 'archived_reason',
        type: 'STRING',
        nullable: true,
        data: nullableStrings((r) => r.archived_reason),
      },

      { name: 'created_at', type: 'TIMESTAMP', data: instants((r) => r.created_at) },
      { name: 'updated_at', type: 'TIMESTAMP', data: instants((r) => r.updated_at) },
    ],
    kvMetadata: [
      { key: 'healthhub.dataset', value: meta.dataset },
      { key: 'healthhub.year', value: String(meta.year) },
      { key: 'healthhub.month', value: String(meta.month) },
      { key: 'healthhub.generation', value: String(meta.generation) },
      { key: 'healthhub.part', value: String(meta.partIndex) },
      { key: 'healthhub.rows', value: String(rows.length) },
    ],
  })

  return new Uint8Array(buffer)
}
