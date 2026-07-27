import { describe, expect, it } from 'vitest'
import { barShares, coerce, formatCell, numericColumns, toResultSet, type ArrowLike } from './rows'

/** An Arrow table answers with row proxies that carry a `toJSON`; this is that shape. */
function table(columns: string[], rows: Record<string, unknown>[]): ArrowLike {
  return {
    schema: { fields: columns.map((name) => ({ name })) },
    numRows: rows.length,
    toArray: () => rows.map((row) => ({ toJSON: () => row })),
  }
}

describe('coerce', () => {
  it('turns the BigInt every count arrives as into a number', () => {
    expect(coerce(42n)).toBe(42)
  })

  it('keeps a value too large for a double as text rather than rounding it silently', () => {
    expect(coerce(9007199254740993n)).toBe('9007199254740993')
  })

  it('treats a missing measurement as missing, not as zero', () => {
    expect(coerce(null)).toBeNull()
    expect(coerce(undefined)).toBeNull()
    expect(coerce(Number.NaN)).toBeNull()
    expect(coerce(Number.POSITIVE_INFINITY)).toBeNull()
  })

  it('renders a timestamp without pretending to know the athlete’s timezone', () => {
    expect(coerce(new Date(Date.UTC(2026, 6, 27, 6, 30)))).toBe('2026-07-27 06:30:00')
  })

  it('passes ordinary values through', () => {
    expect(coerce('cycling')).toBe('cycling')
    expect(coerce(12.5)).toBe(12.5)
    expect(coerce(true)).toBe(true)
  })
})

describe('toResultSet', () => {
  it('flattens columns and rows in schema order', () => {
    const result = toResultSet(
      table(
        ['Sport', 'Distance (km)'],
        [
          { Sport: 'cycling', 'Distance (km)': 1204.4 },
          { Sport: 'running', 'Distance (km)': 311n },
        ],
      ),
    )
    expect(result.columns).toEqual(['Sport', 'Distance (km)'])
    expect(result.rows).toEqual([
      ['cycling', 1204.4],
      ['running', 311],
    ])
  })

  it('fills a column the row does not carry with null', () => {
    const result = toResultSet(table(['a', 'b'], [{ a: 1 }]))
    expect(result.rows).toEqual([[1, null]])
  })

  it('survives an empty answer', () => {
    expect(toResultSet(table(['a'], [])).rows).toEqual([])
  })
})

describe('barShares', () => {
  it('scales within the result, which is the only comparison a bar can make', () => {
    expect(barShares([[10], [5], [0]], 0)).toEqual([100, 50, 0])
  })

  it('draws nothing for a negative or non-numeric value', () => {
    expect(barShares([[10], [-4], ['x']], 0)).toEqual([100, 0, 0])
  })

  it('draws nothing at all rather than dividing by zero', () => {
    expect(barShares([[0], [0]], 0)).toEqual([0, 0])
  })
})

describe('numericColumns', () => {
  it('treats a column of numbers and nulls as numeric', () => {
    const result = { columns: ['a', 'b'], rows: [[1, 'x'] as (number | string)[], [null, 'y']] }
    expect(numericColumns(result)).toEqual([true, false])
  })
})

describe('formatCell', () => {
  it('says nothing was measured with the same dash the rest of the app uses', () => {
    expect(formatCell(null)).toBe('—')
  })

  // Digits only: the grouping and decimal marks are the reader's locale, not this test's.
  it('stops at two decimals, since the SQL already rounded', () => {
    expect(formatCell(1204.4567).replace(/\D/g, '')).toBe('120446')
  })
})
