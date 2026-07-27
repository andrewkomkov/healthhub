import { describe, expect, it } from 'vitest'
import { mapFields, type DescribedColumn } from './fields'
import { availability, looksReadOnly, QUESTIONS, starterSql, VIEW } from './queries'

const described = (...names: string[]): DescribedColumn[] =>
  names.map((name) => ({ name, type: 'DOUBLE' }))

const FULL = mapFields(
  described(
    'id',
    'sport',
    'title',
    'start_time',
    'tz_offset_minutes',
    'elapsed_seconds',
    'moving_seconds',
    'distance_m',
    'elevation_gain_m',
    'visibility',
    'year',
    'month',
  ),
)

const question = (id: string) => {
  const found = QUESTIONS.find((entry) => entry.id === id)
  if (!found) throw new Error(`No question ${id}`)
  return found
}

const options = { units: 'metric' as const, year: 2026, limit: 20 }

describe('every question', () => {
  for (const entry of QUESTIONS) {
    it(`${entry.id} reads from the one view the session creates`, () => {
      const sql = entry.build(FULL, options)
      expect(sql).toContain(`FROM ${VIEW}`)
      expect(sql.startsWith('SELECT')).toBe(true)
    })

    it(`${entry.id} is read-only, so the free-text guard would not object to it`, () => {
      expect(looksReadOnly(entry.build(FULL, options))).toBe(true)
    })

    it(`${entry.id} counts only the recording that represents its workout`, () => {
      expect(entry.build(FULL, options)).toContain(`WHERE "visibility" = 'active'`)
    })

    it(`${entry.id} orders by an alias it actually selects`, () => {
      const sql = entry.build(FULL, options)
      const ordered = [...sql.matchAll(/ORDER BY (.+)/g)].flatMap((match) =>
        (match[1] ?? '').split(',').map((part) => part.trim().replace(/ (ASC|DESC)$/i, '')),
      )
      for (const term of ordered) {
        if (!term.startsWith('"')) continue
        expect(sql.slice(0, sql.indexOf('FROM'))).toContain(`AS ${term}`)
      }
    })
  }
})

describe('distance by sport', () => {
  const sql = question('distance-by-sport').build(FULL, options)

  it('groups a year against a sport and totals the distance in kilometres', () => {
    expect(sql).toContain('CAST(CAST("year" AS INTEGER) AS VARCHAR) AS "Year"')
    expect(sql).toContain('"sport" AS "Sport"')
    expect(sql).toContain('sum("distance_m") / 1000')
    expect(sql).toContain('AS "Distance (km)"')
  })

  it('counts miles when the athlete does', () => {
    const imperial = question('distance-by-sport').build(FULL, { ...options, units: 'imperial' })
    expect(imperial).toContain('sum("distance_m") / 1609.344')
    expect(imperial).toContain('AS "Distance (mi)"')
  })

  it('prefers moving time over elapsed, the way every summary on the phone does', () => {
    expect(sql).toContain('coalesce("moving_seconds", "elapsed_seconds")')
  })

  it('asks about every year, since it is the whole-history question', () => {
    expect(sql).not.toContain('AND CAST')
  })
})

describe('month by month', () => {
  it('filters to the chosen year on the partition column, which prunes files', () => {
    const sql = question('month-by-month').build(FULL, options)
    expect(sql).toContain(`WHERE "visibility" = 'active'\n  AND CAST("year" AS INTEGER) = 2026`)
  })

  it('drops the year and keeps the archive filter when no year is chosen', () => {
    const sql = question('month-by-month').build(FULL, { ...options, year: null })
    expect(sql).toContain(`WHERE "visibility" = 'active'`)
    expect(sql).not.toContain('AND CAST')
  })

  it('never interpolates anything but an integer into the filter', () => {
    const sql = question('month-by-month').build(FULL, {
      ...options,
      year: 2026.9 as unknown as number,
    })
    expect(sql).toContain('= 2026')
  })
})

describe('all-time totals', () => {
  it('includes climbing when the archive recorded it', () => {
    expect(question('totals-by-sport').build(FULL, options)).toContain('AS "Climbed (m)"')
  })

  it('leaves the column out entirely rather than showing a column of nulls', () => {
    const thin = mapFields(described('sport', 'distance_m', 'elapsed_seconds'))
    const sql = question('totals-by-sport').build(thin, options)
    expect(sql).not.toContain('Climbed')
    expect(sql).toContain('AS "Hours"')
  })
})

describe('longest efforts', () => {
  it('titles the rows when the archive has titles', () => {
    expect(question('longest').build(FULL, options)).toContain('"title" AS "Title"')
  })

  it('still runs against an archive without them', () => {
    const untitled = mapFields(described('sport', 'start_time', 'distance_m', 'elapsed_seconds'))
    const sql = question('longest').build(untitled, options)
    expect(sql).not.toContain('Title')
    expect(sql).toContain('LIMIT 20')
  })
})

describe('availability', () => {
  it('says which column an archive would need before a question can be asked', () => {
    const thin = mapFields(described('start_time', 'elapsed_seconds'))
    const totals = availability(thin).find((entry) => entry.question.id === 'totals-by-sport')
    expect(totals?.missing).toEqual(['sport', 'distanceM'])
  })

  it('clears every question against a complete archive', () => {
    expect(availability(FULL).every((entry) => entry.missing.length === 0)).toBe(true)
  })
})

describe('looksReadOnly', () => {
  it('lets a query through', () => {
    expect(looksReadOnly(starterSql(FULL))).toBe(true)
    expect(looksReadOnly('SELECT * FROM activities WHERE sport = \'cycling\'')).toBe(true)
  })

  it('stops a statement that expects to write, before R2 has to', () => {
    expect(looksReadOnly('DELETE FROM activities')).toBe(false)
    expect(looksReadOnly('COPY activities TO \'s3://b/x.parquet\'')).toBe(false)
    expect(looksReadOnly('  create table t as select 1')).toBe(false)
  })

  it('is not fooled by a comment that mentions one', () => {
    expect(looksReadOnly('-- no delete here\nSELECT 1')).toBe(true)
    expect(looksReadOnly('/* drop */ SELECT 1')).toBe(true)
  })
})
