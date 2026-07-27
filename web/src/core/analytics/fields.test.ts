import { describe, expect, it } from 'vitest'
import {
  activeOnly,
  column,
  localTimestamp,
  mapFields,
  missingFields,
  monthExpression,
  quoteIdentifier,
  quoteLiteral,
  yearExpression,
  type DescribedColumn,
} from './fields'

const described = (...names: string[]): DescribedColumn[] =>
  names.map((name) => ({ name, type: 'BIGINT' }))

/** The compaction job's own types, for the fields where the type changes the SQL. */
const typed = (...columns: [string, string][]): DescribedColumn[] =>
  columns.map(([name, type]) => ({ name, type }))

describe('mapFields', () => {
  it('reads the D1 column names the compaction job writes', () => {
    const map = mapFields(described('sport', 'start_time', 'distance_m', 'elapsed_seconds'))
    expect(map.sport?.name).toBe('sport')
    expect(map.startTime?.name).toBe('start_time')
    expect(map.distanceM?.name).toBe('distance_m')
  })

  it('reads the API spelling just as happily, so either producer works', () => {
    const map = mapFields(described('sport', 'startTime', 'distanceM', 'elapsedSeconds'))
    expect(map.startTime?.name).toBe('startTime')
    expect(map.distanceM?.name).toBe('distanceM')
  })

  it('keeps the name the archive actually uses, not the one this client thinks in', () => {
    expect(mapFields(described('DISTANCE_M')).distanceM?.name).toBe('DISTANCE_M')
  })

  it('finds nothing it was not given', () => {
    const map = mapFields(described('sport'))
    expect(map.distanceM).toBeUndefined()
    expect(missingFields(map, ['sport', 'distanceM'])).toEqual(['distanceM'])
  })

  it('takes the first spelling when a file carries two of them', () => {
    expect(mapFields(described('distance_m', 'distance')).distanceM?.name).toBe('distance_m')
  })
})

describe('quoting', () => {
  it('doubles an embedded quote rather than ending the token', () => {
    expect(quoteIdentifier('od"d')).toBe('"od""d"')
    expect(quoteLiteral("it's")).toBe("'it''s'")
  })

  it('refuses to reference a column that is not there', () => {
    expect(() => column(mapFields([]), 'sport')).toThrow(/no column/)
  })
})

describe('the local clock', () => {
  it('adds an interval when the archive stores a real timestamp, which is what the job writes', () => {
    const map = mapFields(
      typed(['start_time', 'TIMESTAMP'], ['tz_offset_minutes', 'INTEGER']),
    )
    expect(localTimestamp(map)).toBe(
      '("start_time" + to_minutes(coalesce("tz_offset_minutes", 0)))',
    )
  })

  it('does the epoch arithmetic when it stores D1’s milliseconds instead', () => {
    const map = mapFields(typed(['start_time', 'BIGINT'], ['tz_offset_minutes', 'INTEGER']))
    expect(localTimestamp(map)).toBe(
      'epoch_ms("start_time" + coalesce("tz_offset_minutes", 0) * 60000)',
    )
  })

  it('falls back to UTC when the archive did not record an offset', () => {
    expect(localTimestamp(mapFields(described('start_time')))).toBe('epoch_ms("start_time")')
    expect(localTimestamp(mapFields(typed(['start_time', 'TIMESTAMP'])))).toBe('"start_time"')
  })
})

describe('activeOnly', () => {
  it('keeps the recordings that represent their workout, so nothing is counted twice', () => {
    expect(activeOnly(mapFields(described('visibility')))).toBe(`"visibility" = 'active'`)
  })

  it('adds no condition to an archive that does not carry the column', () => {
    expect(activeOnly(mapFields(described('sport')))).toBeNull()
  })
})

describe('yearExpression', () => {
  it('uses the Hive partition column when there is one, because it prunes whole files', () => {
    const map = mapFields(described('year', 'month', 'start_time', 'local_date'))
    expect(yearExpression(map)).toBe('CAST("year" AS INTEGER)')
    expect(monthExpression(map)).toBe('CAST("month" AS INTEGER)')
  })

  it('reads the stored local date next, which is arithmetic already done once', () => {
    const map = mapFields(described('local_date', 'start_time'))
    expect(yearExpression(map)).toBe('CAST(substr("local_date", 1, 4) AS INTEGER)')
    expect(monthExpression(map)).toBe('CAST(substr("local_date", 6, 2) AS INTEGER)')
  })

  it('computes it from the timestamp only when it has to', () => {
    const map = mapFields(described('start_time', 'tz_offset_minutes'))
    expect(yearExpression(map)).toContain('strftime(epoch_ms(')
  })
})
