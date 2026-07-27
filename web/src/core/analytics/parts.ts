import type { ArchiveMonth } from '../api/client'

/**
 * Which Parquet objects a query should read, and how much of the archive that is.
 *
 * The manifest in D1 (`archive_months` / `archive_parts`, migration 0007) is the authority on
 * what is live: R2 has no multi-object transaction, so a prefix listing taken while a month is
 * being rebuilt can hold a mixture of two generations. Asking the API instead of globbing is
 * therefore not a workaround — it is the design. It also happens to be the only option in the
 * browser, because DuckDB-Wasm's S3 filesystem cannot list a bucket at all; `s3://…/*.parquet`
 * resolves to nothing there, and every file has to be named.
 */

const pad = (value: number, width: number) => String(value).padStart(width, '0')

/** The key layout fixed by data-model.md. Parts are dense and 1-based. */
export function partKey(
  userId: string,
  dataset: string,
  year: number,
  month: number,
  index: number,
): string {
  return `u/${userId}/archive/${dataset}/year=${year}/month=${pad(month, 2)}/part-${pad(index, 4)}.parquet`
}

/**
 * The keys of one month's live parts.
 *
 * `parts` from the API wins whenever it is there — it is the manifest, and it knows about a
 * sparse or renamed part. Deriving from `partCount` is the fallback for a response that only
 * counted, and it is safe precisely because the layout is specified rather than incidental.
 */
export function monthKeys(userId: string, month: ArchiveMonth): string[] {
  if (month.parts && month.parts.length > 0) return month.parts
  return Array.from({ length: month.partCount }, (_, index) =>
    partKey(userId, month.dataset, month.year, month.month, index + 1),
  )
}

/** `s3://bucket/key` — the form DuckDB signs with the athlete's scoped credentials. */
export function s3Url(bucket: string, key: string): string {
  return `s3://${bucket}/${key}`
}

export interface Coverage {
  months: number
  parts: number
  rows: number
  bytes: number
  /** Ascending, so a year picker reads the way a calendar does. */
  years: number[]
  earliest: { year: number; month: number } | null
  latest: { year: number; month: number } | null
}

/**
 * What the athlete actually has compacted, for the `activities` dataset only.
 *
 * `samples` is declared in the schema and deliberately not produced (0007): building it would
 * mean decoding `.hht` in the Worker. Counting it here would promise an archive that is not
 * there.
 */
export function coverage(months: ArchiveMonth[], dataset = 'activities'): Coverage {
  const relevant = months
    .filter((month) => month.dataset === dataset)
    .sort((a, b) => a.year - b.year || a.month - b.month)

  const years = [...new Set(relevant.map((month) => month.year))].sort((a, b) => a - b)
  const first = relevant[0]
  const last = relevant[relevant.length - 1]

  return {
    months: relevant.length,
    parts: relevant.reduce((total, month) => total + month.partCount, 0),
    rows: relevant.reduce((total, month) => total + month.rowCount, 0),
    bytes: relevant.reduce((total, month) => total + month.bytes, 0),
    years,
    earliest: first ? { year: first.year, month: first.month } : null,
    latest: last ? { year: last.year, month: last.month } : null,
  }
}

/**
 * Every part of the `activities` dataset, oldest first, as `s3://` URLs.
 *
 * The whole history goes into one `read_parquet` list. Partition pruning is what keeps that
 * cheap: DuckDB reads each file's footer, sees the row-group statistics, and skips the ones a
 * `WHERE` cannot match. A year of one athlete's activities is a few megabytes of footer and
 * column data — which is the entire point of R-013.
 */
export function archiveUrls(
  userId: string,
  bucket: string,
  months: ArchiveMonth[],
  dataset = 'activities',
): string[] {
  return months
    .filter((month) => month.dataset === dataset)
    .sort((a, b) => a.year - b.year || a.month - b.month)
    .flatMap((month) => monthKeys(userId, month).map((key) => s3Url(bucket, key)))
}

/** Bytes, for a sentence about how much of their own bucket a query touched. */
export function megabytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} kB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
