/**
 * Arrow results, flattened into something a table can render.
 *
 * DuckDB answers in Apache Arrow: columnar, zero-copy, and full of types JSX cannot print —
 * `BigInt` for every count, `Date` for every timestamp, decimals as objects. This is the one
 * place that knows about them, so the screen only ever sees strings, numbers and nulls.
 */

export type Cell = string | number | boolean | null

export interface ResultSet {
  columns: string[]
  rows: Cell[][]
}

/** An Arrow table, described by what is actually used rather than imported from the library. */
export interface ArrowLike {
  schema: { fields: readonly { name: string }[] }
  numRows: number
  toArray(): readonly unknown[]
}

export function coerce(value: unknown): Cell {
  if (value === null || value === undefined) return null

  if (typeof value === 'bigint') {
    // Counts and epoch milliseconds arrive as BigInt. Beyond 2^53 a Number would round
    // silently, and a wrong total is worse than a long string.
    return value >= BigInt(Number.MIN_SAFE_INTEGER) && value <= BigInt(Number.MAX_SAFE_INTEGER)
      ? Number(value)
      : value.toString()
  }

  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string' || typeof value === 'boolean') return value
  if (value instanceof Date) return value.toISOString().slice(0, 19).replace('T', ' ')

  // A DECIMAL column arrives as an Arrow decimal object, not a number — an assembler that
  // wrote its distances as decimals would otherwise put `1204.4` in a table as text and sort
  // it as text. Anything whose own `toString` is a finite number is one.
  const text = String(value)
  const asNumber = Number(text)
  return text.trim() !== '' && Number.isFinite(asNumber) ? asNumber : text
}

export function toResultSet(table: ArrowLike): ResultSet {
  const columns = table.schema.fields.map((field) => field.name)
  const rows = table.toArray().map((entry) => {
    const record = asRecord(entry)
    return columns.map((name) => coerce(record[name]))
  })
  return { columns, rows }
}

function asRecord(entry: unknown): Record<string, unknown> {
  if (entry && typeof entry === 'object') {
    const proxy = entry as { toJSON?: () => Record<string, unknown> }
    if (typeof proxy.toJSON === 'function') return proxy.toJSON()
    return entry as Record<string, unknown>
  }
  return {}
}

const NUMBER_FORMAT = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 })

export function formatCell(value: Cell): string {
  if (value === null) return '—'
  if (typeof value === 'number') return NUMBER_FORMAT.format(value)
  if (typeof value === 'boolean') return value ? 'yes' : 'no'
  return value
}

/**
 * How long each row's bar should be, as a percentage of the largest value in that column.
 *
 * The comparison is within one result, which is the only comparison a bar can honestly make.
 * Negative and non-numeric values get no bar rather than an inverted one.
 */
export function barShares(rows: Cell[][], columnIndex: number): number[] {
  const values = rows.map((row) => {
    const value = row[columnIndex]
    return typeof value === 'number' && value > 0 ? value : 0
  })
  const largest = values.reduce((max, value) => Math.max(max, value), 0)
  return values.map((value) => (largest > 0 ? (value / largest) * 100 : 0))
}

/** Right-align a column only when every value in it is a number. */
export function numericColumns(result: ResultSet): boolean[] {
  return result.columns.map((_, index) =>
    result.rows.every((row) => {
      const value = row[index]
      return value === null || typeof value === 'number'
    }),
  )
}
