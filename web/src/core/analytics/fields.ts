/**
 * The bridge between the questions this screen asks and the columns the compaction job wrote.
 *
 * The Parquet is assembled in the Worker from `activities` rows, so its columns are the D1
 * column names — `start_time`, `distance_m`. A client that hard-codes those breaks the day the
 * assembler emits the API's camelCase spelling instead, and the failure is a SQL binder error
 * three layers down. Instead the session asks DuckDB what is in the files (`DESCRIBE`) and
 * every query is built from what came back: a logical field resolves to whatever the archive
 * actually calls it, and a question whose fields are absent says so in words rather than
 * failing at run time.
 */

import type { ResultSet } from './rows'

export type Field =
  | 'id'
  | 'sport'
  | 'title'
  | 'startTime'
  | 'localDate'
  | 'tzOffsetMinutes'
  | 'elapsedSeconds'
  | 'movingSeconds'
  | 'distanceM'
  | 'elevationGainM'
  | 'caloriesKcal'
  | 'avgSpeedMps'
  | 'avgHrBpm'
  | 'avgPowerW'
  /**
   * `active` or `archived`. The archive holds every recording, including the duplicates the
   * feed sets aside — nothing is ever deleted — so a total that does not filter on this counts
   * the same ride once per app that recorded it.
   */
  | 'visibility'
  /** Hive partition columns, present only when the path itself is read as data. */
  | 'year'
  | 'month'

/**
 * Accepted spellings per field. Case, underscores and hyphens are ignored, so `start_time`,
 * `startTime` and `START_TIME` are one entry; the extra aliases are for columns a producer
 * could plausibly name differently.
 */
const ALIASES: Record<Field, string[]> = {
  id: ['activity_id', 'id'],
  sport: ['sport', 'sport_type', 'activity_type'],
  title: ['title', 'name'],
  startTime: ['start_time', 'started_at', 'start_time_ms'],
  localDate: ['local_date', 'local_start_date'],
  tzOffsetMinutes: ['tz_offset_minutes', 'timezone_offset_minutes'],
  elapsedSeconds: ['elapsed_seconds', 'elapsed_time_seconds'],
  movingSeconds: ['moving_seconds', 'moving_time_seconds'],
  distanceM: ['distance_m', 'distance', 'distance_metres', 'distance_meters'],
  elevationGainM: ['elevation_gain_m', 'elevation_gain', 'total_ascent_m'],
  caloriesKcal: ['calories_kcal', 'calories'],
  avgSpeedMps: ['avg_speed_mps', 'average_speed_mps'],
  avgHrBpm: ['avg_hr_bpm', 'average_heart_rate_bpm'],
  avgPowerW: ['avg_power_w', 'average_power_w'],
  visibility: ['visibility'],
  year: ['year'],
  month: ['month'],
}

const normalise = (name: string) => name.toLowerCase().replace(/[^a-z0-9]/g, '')

export interface DescribedColumn {
  name: string
  type: string
}

/**
 * `DESCRIBE` answers with a table of its own — `column_name`, `column_type`, and three columns
 * about constraints that mean nothing for a Parquet file. Read by name rather than by position,
 * because that shape is DuckDB's to change.
 */
export function describedColumns(described: ResultSet): DescribedColumn[] {
  const nameIndex = described.columns.indexOf('column_name')
  const typeIndex = described.columns.indexOf('column_type')
  if (nameIndex < 0) throw new Error('DESCRIBE did not answer with a column_name.')
  return described.rows.map((row) => ({
    name: String(row[nameIndex] ?? ''),
    type: typeIndex >= 0 ? String(row[typeIndex] ?? '') : '',
  }))
}

/**
 * What the archive turned out to contain, keyed by the name this client thinks in.
 *
 * The type travels with the name because it changes the SQL: the compaction job writes
 * `start_time` as a Parquet TIMESTAMP, while an athlete's own export might hold the epoch
 * milliseconds D1 stores. Those need different arithmetic and the difference is invisible
 * until a query fails.
 */
export type FieldMap = Partial<Record<Field, DescribedColumn>>

export function mapFields(columns: DescribedColumn[]): FieldMap {
  const byNormalised = new Map<string, DescribedColumn>()
  for (const column of columns) {
    // First writer wins: a file with both `distance_m` and `distance` should resolve to the
    // one the schema declares first rather than to whichever sorted last.
    const key = normalise(column.name)
    if (!byNormalised.has(key)) byNormalised.set(key, column)
  }

  const map: FieldMap = {}
  for (const [field, aliases] of Object.entries(ALIASES) as [Field, string[]][]) {
    for (const alias of aliases) {
      const actual = byNormalised.get(normalise(alias))
      if (actual !== undefined) {
        map[field] = actual
        break
      }
    }
  }
  return map
}

export function missingFields(map: FieldMap, required: readonly Field[]): Field[] {
  return required.filter((field) => map[field] === undefined)
}

/** SQL identifier quoting. DuckDB doubles an embedded quote, like every other dialect. */
export function quoteIdentifier(name: string): string {
  return `"${name.replace(/"/g, '""')}"`
}

/** SQL string literal quoting, for credentials and for a year that came from a select. */
export function quoteLiteral(value: string): string {
  return `'${value.replace(/'/g, "''")}'`
}

/**
 * A column reference for a field the caller has already checked is present.
 *
 * Throwing rather than emitting `NULL` is deliberate: a question that reaches this with a
 * missing field is a bug in its `requires` list, and a silent column of nulls would look like
 * an athlete with no data rather than like the mistake it is.
 */
export function column(map: FieldMap, field: Field): string {
  const actual = map[field]
  if (actual === undefined) throw new Error(`The archive has no column for ${field}.`)
  return quoteIdentifier(actual.name)
}

/** The archive's own type for a field, uppercased. Empty when the field is absent. */
export function typeOf(map: FieldMap, field: Field): string {
  return (map[field]?.type ?? '').toUpperCase()
}

/**
 * A timestamp in the athlete's own wall clock.
 *
 * Every instant in the archive is UTC with the offset the workout was recorded at travelling
 * beside it, exactly as D1 stores the pair — so a 23:30 ride in Lisbon stays on the day the
 * athlete rode it, whatever timezone the browser asking the question is in.
 *
 * The compaction job writes a Parquet TIMESTAMP, which arrives as a timestamp and takes an
 * interval; a hand-made export of the same rows might hold D1's epoch milliseconds, which
 * takes `epoch_ms`. Both are supported because the failure mode of guessing is a binder error
 * in the athlete's browser.
 */
export function localTimestamp(map: FieldMap): string {
  const start = column(map, 'startTime')
  const offset =
    map.tzOffsetMinutes === undefined ? null : `coalesce(${column(map, 'tzOffsetMinutes')}, 0)`

  if (typeOf(map, 'startTime').includes('TIMESTAMP')) {
    return offset === null ? start : `(${start} + to_minutes(${offset}))`
  }
  return offset === null ? `epoch_ms(${start})` : `epoch_ms(${start} + ${offset} * 60000)`
}

/**
 * The recordings that represent their workout, which is what every total here is about.
 *
 * Health Connect hands the same ride over from several apps, and the archive keeps all of
 * them — nothing is ever deleted. Summing without this counts a ride once per app that saw it,
 * which is the exact failure R-011 was written about, one tier further down.
 */
export function activeOnly(map: FieldMap): string | null {
  if (map.visibility === undefined) return null
  return `${column(map, 'visibility')} = 'active'`
}

/**
 * The calendar year of an activity, cheapest form first.
 *
 * The Hive partition column is free — it comes from the object's own key, so a filter on it
 * prunes whole files before a byte of column data is fetched. That is the difference between
 * a year's question costing a few megabytes and costing the whole archive.
 */
export function yearExpression(map: FieldMap): string {
  if (map.year !== undefined) return `CAST(${column(map, 'year')} AS INTEGER)`
  if (map.localDate !== undefined) return `CAST(substr(${column(map, 'localDate')}, 1, 4) AS INTEGER)`
  return `CAST(strftime(${localTimestamp(map)}, '%Y') AS INTEGER)`
}

export function monthExpression(map: FieldMap): string {
  if (map.month !== undefined) return `CAST(${column(map, 'month')} AS INTEGER)`
  if (map.localDate !== undefined) return `CAST(substr(${column(map, 'localDate')}, 6, 2) AS INTEGER)`
  return `CAST(strftime(${localTimestamp(map)}, '%m') AS INTEGER)`
}
