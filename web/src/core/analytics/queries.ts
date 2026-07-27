import type { UnitSystem } from '../format'
import {
  activeOnly,
  column,
  localTimestamp,
  missingFields,
  monthExpression,
  quoteIdentifier,
  yearExpression,
  type Field,
  type FieldMap,
} from './fields'

/**
 * The questions an athlete asks of a decade of their own training.
 *
 * These are whole-history aggregates — the one thing the interactive path cannot answer, since
 * it reads exactly one activity at a time (R-013). Every one of them runs in the browser over
 * Parquet in the athlete's own bucket, so the marginal cost of asking is zero: R2 charges no
 * egress, and nothing is aggregated on the edge (Principle I, R-014).
 *
 * Column aliases carry their unit because the result table renders whatever comes back without
 * knowing what it means. Converting in SQL also means the number the athlete sees and the
 * number they would get from the same query in the DuckDB CLI are the same number.
 */

export const VIEW = 'activities'

export interface QueryOptions {
  units: UnitSystem
  /** `null` means every year the archive holds. */
  year: number | null
  limit: number
}

export interface Question {
  id: string
  title: string
  blurb: string
  requires: readonly Field[]
  /** True when the question is about one year rather than about all of them. */
  yearly?: boolean
  /** The output column a comparison bar is drawn from, by alias. */
  measure?: string
  build(map: FieldMap, options: QueryOptions): string
}

const DISTANCE_DIVISOR = { metric: 1000, imperial: 1609.344 } as const
const distanceUnit = (units: UnitSystem) => (units === 'imperial' ? 'mi' : 'km')
const elevationUnit = (units: UnitSystem) => (units === 'imperial' ? 'ft' : 'm')

function distanceSum(map: FieldMap, units: UnitSystem): string {
  return `round(sum(${column(map, 'distanceM')}) / ${DISTANCE_DIVISOR[units]}, 1)`
}

function elevationSum(map: FieldMap, units: UnitSystem): string {
  const metres = `sum(${column(map, 'elevationGainM')})`
  return units === 'imperial' ? `round(${metres} * 3.28084)` : `round(${metres})`
}

function hoursSum(map: FieldMap): string {
  const seconds = map.movingSeconds
    ? `coalesce(${column(map, 'movingSeconds')}, ${column(map, 'elapsedSeconds')})`
    : column(map, 'elapsedSeconds')
  return `round(sum(${seconds}) / 3600.0, 1)`
}

/**
 * The `WHERE` every question shares.
 *
 * `activeOnly` is not optional in spirit: the archive holds the duplicate recordings the feed
 * set aside, because nothing is ever deleted, and a sum over all of them counts one ride once
 * per app that wrote it. A year filter is added on top, and both prune before any measure
 * column is read.
 */
function where(map: FieldMap, year: number | null, ...extra: (string | null)[]): string {
  const conditions = [
    activeOnly(map),
    year === null ? null : `${yearExpression(map)} = ${Math.trunc(year)}`,
    ...extra,
  ].filter((condition): condition is string => condition !== null)
  return conditions.length === 0 ? '' : `WHERE ${conditions.join('\n  AND ')}`
}

const alias = (label: string) => quoteIdentifier(label)

export const QUESTIONS: Question[] = [
  {
    id: 'distance-by-sport',
    title: 'Distance by sport, by year',
    blurb: 'Every year you have, split by what you were doing. The question this tier exists for.',
    requires: ['sport', 'startTime', 'distanceM', 'elapsedSeconds'],
    measure: 'Distance',
    build: (map, options) => {
      const unit = distanceUnit(options.units)
      return [
        'SELECT',
        // Text, because a year is a label rather than a quantity: as a number the browser's
        // own locale formats it with a thousands separator, and "2 026" is not a year.
        `  CAST(${yearExpression(map)} AS VARCHAR) AS ${alias('Year')},`,
        `  ${column(map, 'sport')} AS ${alias('Sport')},`,
        `  count(*) AS ${alias('Activities')},`,
        `  ${distanceSum(map, options.units)} AS ${alias(`Distance (${unit})`)},`,
        `  ${hoursSum(map)} AS ${alias('Hours')}`,
        `FROM ${VIEW}`,
        where(map, null),
        `GROUP BY 1, 2`,
        `ORDER BY ${alias('Year')} DESC, ${alias(`Distance (${unit})`)} DESC`,
      ]
        .filter(Boolean)
        .join('\n')
    },
  },
  {
    id: 'month-by-month',
    title: 'Month by month',
    blurb: 'One year in twelve rows — the shape of a season, and where it went quiet.',
    requires: ['startTime', 'distanceM', 'elapsedSeconds'],
    yearly: true,
    measure: 'Distance',
    build: (map, options) => {
      const unit = distanceUnit(options.units)
      return [
        'SELECT',
        `  ${monthExpression(map)} AS ${alias('Month')},`,
        `  count(*) AS ${alias('Activities')},`,
        `  ${distanceSum(map, options.units)} AS ${alias(`Distance (${unit})`)},`,
        `  ${hoursSum(map)} AS ${alias('Hours')}`,
        `FROM ${VIEW}`,
        where(map, options.year),
        `GROUP BY 1`,
        `ORDER BY 1`,
      ]
        .filter(Boolean)
        .join('\n')
    },
  },
  {
    id: 'totals-by-sport',
    title: 'Everything, ever',
    blurb: 'All-time totals per sport, counted from the archive rather than from a running tally.',
    requires: ['sport', 'distanceM', 'elapsedSeconds'],
    measure: 'Hours',
    build: (map, options) => {
      const unit = distanceUnit(options.units)
      const climb = map.elevationGainM
        ? `,\n  ${elevationSum(map, options.units)} AS ${alias(`Climbed (${elevationUnit(options.units)})`)}`
        : ''
      return [
        'SELECT',
        `  ${column(map, 'sport')} AS ${alias('Sport')},`,
        `  count(*) AS ${alias('Activities')},`,
        `  ${distanceSum(map, options.units)} AS ${alias(`Distance (${unit})`)},`,
        `  ${hoursSum(map)} AS ${alias('Hours')}${climb}`,
        `FROM ${VIEW}`,
        where(map, null),
        `GROUP BY 1`,
        `ORDER BY ${alias(`Distance (${unit})`)} DESC`,
      ]
        .filter(Boolean)
        .join('\n')
    },
  },
  {
    id: 'longest',
    title: 'Longest efforts',
    blurb: 'The furthest single activities in the whole archive, newest tie first.',
    requires: ['startTime', 'sport', 'distanceM', 'elapsedSeconds'],
    measure: 'Distance',
    build: (map, options) => {
      const unit = distanceUnit(options.units)
      const title = map.title ? `  ${column(map, 'title')} AS ${alias('Title')},\n` : ''
      return [
        'SELECT',
        `  strftime(${localTimestamp(map)}, '%Y-%m-%d') AS ${alias('Date')},`,
        title + `  ${column(map, 'sport')} AS ${alias('Sport')},`,
        `  round(${column(map, 'distanceM')} / ${DISTANCE_DIVISOR[options.units]}, 1) AS ${alias(`Distance (${unit})`)},`,
        `  round(${column(map, 'elapsedSeconds')} / 3600.0, 2) AS ${alias('Hours')}`,
        `FROM ${VIEW}`,
        where(map, null, `${column(map, 'distanceM')} IS NOT NULL`),
        `ORDER BY ${alias(`Distance (${unit})`)} DESC`,
        `LIMIT ${Math.trunc(options.limit)}`,
      ]
        .filter(Boolean)
        .join('\n')
    },
  },
]

export interface AvailableQuestion {
  question: Question
  /** Empty when the question can run. Otherwise the columns the archive turned out to lack. */
  missing: Field[]
}

export function availability(map: FieldMap): AvailableQuestion[] {
  return QUESTIONS.map((question) => ({
    question,
    missing: missingFields(map, question.requires),
  }))
}

/**
 * A starting point for the free-text box.
 *
 * It is deliberately the same SQL an athlete would paste into the DuckDB CLI against their own
 * bucket — the view is the only thing this page adds, and `SELECT * FROM activities` is a
 * complete sentence in both places.
 */
export function starterSql(map: FieldMap): string {
  const sport = map.sport ? `${column(map, 'sport')}, ` : ''
  return [
    `SELECT ${sport}count(*) AS n`,
    `FROM ${VIEW}`,
    where(map, null),
    'GROUP BY ALL',
    'ORDER BY n DESC',
  ]
    .filter(Boolean)
    .join('\n')
}

/**
 * A guard for the free-text box: this page holds read-only credentials, and a statement that
 * expects to change something should say so here rather than fail obscurely at R2's edge.
 * It is a courtesy, not a security boundary — the credentials are what actually enforce this,
 * and they are scoped to the athlete's own prefix and read-only at the source.
 */
const MUTATING = /\b(insert|update|delete|drop|create|alter|copy|attach|export|install|load)\b/i

export function looksReadOnly(sql: string): boolean {
  const withoutComments = sql.replace(/--[^\n]*/g, ' ').replace(/\/\*[\s\S]*?\*\//g, ' ')
  return !MUTATING.test(withoutComments)
}
