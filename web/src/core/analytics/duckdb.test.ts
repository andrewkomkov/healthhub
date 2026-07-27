/**
 * @vitest-environment node
 *
 * The query layer against a real DuckDB, over a real Parquet file.
 *
 * Every other test here asserts the shape of a string. This one asserts that the string is
 * valid SQL and that it computes the right numbers, which is the only claim that matters: a
 * question built from a field map the archive did not quite match fails in the athlete's
 * browser, and there is no server-side log to find it in afterwards.
 *
 * It runs the same Wasm build the browser loads — the node bindings out of the same package —
 * so the engine, the Parquet reader and the Hive partitioning are the production ones. What it
 * cannot exercise is the transport: S3 signing against R2 and the CORS rule that has to allow
 * this origin are deployment facts, and they are verified by opening the page.
 *
 * The fixture mirrors `worker/src/archive/parquet.ts` — the column names and types that job
 * actually writes — laid out in the key layout data-model.md specifies. If the job's schema
 * moves, this test is where the disagreement surfaces, rather than in someone's browser.
 */
import { mkdtempSync, mkdirSync, rmSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { describedColumns, mapFields, type FieldMap } from './fields'
import { availability, QUESTIONS, starterSql } from './queries'
import { toResultSet, type ArrowLike, type ResultSet } from './rows'
import { createViewSql, DESCRIBE_SQL } from './session'

const require = createRequire(import.meta.url)

interface BlockingConnection {
  query(sql: string): unknown
  close(): void
}

let connection: BlockingConnection
let directory: string
let fields: FieldMap

const run = (sql: string): ResultSet => toResultSet(connection.query(sql) as ArrowLike)

/** First occurrence wins, so a key that repeats cannot quietly shadow the row being asserted. */
const rowsByFirstColumn = (result: ResultSet) => {
  const map = new Map<string, typeof result.rows[number]>()
  for (const row of result.rows) {
    const key = String(row[0])
    if (!map.has(key)) map.set(key, row)
  }
  return map
}

beforeAll(async () => {
  const duckdb = require('@duckdb/duckdb-wasm/blocking')
  const dist = dirname(require.resolve('@duckdb/duckdb-wasm/dist/duckdb-node-blocking.cjs'))
  const bundles = {
    mvp: {
      mainModule: resolve(dist, 'duckdb-mvp.wasm'),
      mainWorker: resolve(dist, 'duckdb-node-mvp.worker.cjs'),
    },
    eh: {
      mainModule: resolve(dist, 'duckdb-eh.wasm'),
      mainWorker: resolve(dist, 'duckdb-node-eh.worker.cjs'),
    },
  }

  const db = await duckdb.createDuckDB(bundles, new duckdb.VoidLogger(), duckdb.NODE_RUNTIME)
  await db.instantiate(() => undefined)
  connection = db.connect() as BlockingConnection

  // Two months in two years, in the archive's own key layout, so the Hive partition columns
  // are real rather than asserted.
  directory = mkdtempSync(join(tmpdir(), 'healthhub-archive-'))
  const parts: string[] = []
  for (const [year, month, values] of FIXTURE) {
    const folder = join(directory, `year=${year}`, `month=${month}`)
    mkdirSync(folder, { recursive: true })
    const part = join(folder, 'part-0001.parquet')
    connection.query(
      `CREATE OR REPLACE TABLE staging AS SELECT ${TYPED} FROM (VALUES ${values}) ${NAMES}`,
    )
    connection.query(`COPY staging TO '${part}' (FORMAT PARQUET, COMPRESSION ZSTD)`)
    expect(statSync(part).size).toBeGreaterThan(0)
    parts.push(part)
  }

  connection.query(createViewSql(parts, true))
  fields = mapFields(describedColumns(run(DESCRIBE_SQL)))
}, 60_000)

afterAll(() => {
  connection?.close()
  if (directory) rmSync(directory, { recursive: true, force: true })
})

/**
 * The columns `worker/src/archive/parquet.ts` writes, with the types it writes them as.
 *
 * Three of these are the whole point of keeping this fixture honest rather than convenient:
 * the identifier is `activity_id` and not `id`; `start_time` is a Parquet TIMESTAMP and not
 * D1's epoch milliseconds, so it takes an interval rather than arithmetic; and `visibility` is
 * there, because the archive keeps the duplicate recordings the feed set aside and a total
 * that ignores it counts one ride once per app that saw it.
 *
 * The casts also matter mechanically: an untyped `VALUES` list makes DuckDB infer `DECIMAL`,
 * whose sums come back as strings and whose widths refuse to union across two files.
 */
const SCHEMA: [string, string][] = [
  ['activity_id', 'VARCHAR'],
  ['sport', 'VARCHAR'],
  ['title', 'VARCHAR'],
  ['start_time', 'TIMESTAMP'],
  ['tz_offset_minutes', 'INTEGER'],
  ['local_date', 'VARCHAR'],
  ['elapsed_seconds', 'INTEGER'],
  ['moving_seconds', 'INTEGER'],
  ['distance_m', 'DOUBLE'],
  ['elevation_gain_m', 'DOUBLE'],
  ['avg_hr_bpm', 'INTEGER'],
  ['visibility', 'VARCHAR'],
]

const TYPED = SCHEMA.map(([name, type]) => `CAST(${name} AS ${type}) AS ${name}`).join(', ')
const NAMES = `AS t(${SCHEMA.map(([name]) => name).join(', ')})`

/**
 * [year, month, rows]. Distances in metres and times in seconds, as D1 stores them, and start
 * times that really do fall inside the partition they are filed under — the partition column
 * and the timestamp are two different answers to "which month is this", and a fixture where
 * they disagree would hide a query that read the wrong one.
 */
const FIXTURE: [string, string, string][] = [
  [
    '2025',
    '11',
    `('a1','cycling','Autumn ride','2025-11-01 09:00:00',60,'2025-11-01',7200,7000,60000.0,800.0,138,'active'),
     ('a2','running','Parkrun','2025-11-08 08:00:00',60,'2025-11-08',1500,1450,5000.0,40.0,162,'active')`,
  ],
  [
    '2026',
    '07',
    `('b1','cycling','Long ride','2026-07-26 04:40:00',120,'2026-07-26',14400,14000,120000.0,1500.0,141,'active'),
     ('b2','cycling','Commute','2026-07-27 08:00:00',120,'2026-07-27',1800,1700,15000.0,60.0,128,'active'),
     ('b3','running','Evening run','2026-07-28 17:00:00',120,'2026-07-28',3600,3500,10000.0,120.0,155,'active'),
     -- The same long ride as b1, as a second app recorded it. It is in the archive because
     -- nothing is ever deleted, and it must appear in no total below.
     ('b4','cycling','Long ride','2026-07-26 04:41:00',120,'2026-07-26',14300,13900,118000.0,1480.0,140,'archived')`,
  ],
]

describe('the archive as DuckDB sees it', () => {
  it('exposes the archive columns and the Hive partitions together', () => {
    expect(fields.sport?.name).toBe('sport')
    expect(fields.distanceM?.name).toBe('distance_m')
    // The identifier the compaction job writes, not the one D1 calls it.
    expect(fields.id?.name).toBe('activity_id')
    expect(fields.visibility?.name).toBe('visibility')
    // A timestamp, which is why the local-clock expression cannot be epoch arithmetic.
    expect(fields.startTime?.type).toContain('TIMESTAMP')
    // Free from the key. Filtering on these is what prunes whole files.
    expect(fields.year?.name).toBe('year')
    expect(fields.month?.name).toBe('month')
  })

  it('clears every question this client can ask', () => {
    expect(availability(fields).filter((entry) => entry.missing.length > 0)).toEqual([])
  })
})

describe('distance by sport, by year', () => {
  it('adds up one athlete’s history the way the athlete would', () => {
    const question = QUESTIONS.find((entry) => entry.id === 'distance-by-sport')!
    const result = run(question.build(fields, { units: 'metric', year: null, limit: 20 }))

    expect(result.columns).toEqual(['Year', 'Sport', 'Activities', 'Distance (km)', 'Hours'])
    expect(result.rows).toEqual([
      ['2026', 'cycling', 2, 135, 4.4],
      ['2026', 'running', 1, 10, 1],
      ['2025', 'cycling', 1, 60, 1.9],
      ['2025', 'running', 1, 5, 0.4],
    ])
  })

  it('answers in miles for an athlete who thinks in miles', () => {
    const question = QUESTIONS.find((entry) => entry.id === 'distance-by-sport')!
    const result = run(question.build(fields, { units: 'imperial', year: null, limit: 20 }))
    expect(result.columns[3]).toBe('Distance (mi)')
    // The newest year's biggest sport leads: 135 km of cycling.
    expect(result.rows[0]?.slice(0, 2)).toEqual(['2026', 'cycling'])
    expect(result.rows[0]?.[3]).toBeCloseTo(83.9, 1)
  })
})

describe('month by month', () => {
  it('filters to one year on the partition column', () => {
    const question = QUESTIONS.find((entry) => entry.id === 'month-by-month')!
    const result = run(question.build(fields, { units: 'metric', year: 2026, limit: 20 }))
    expect(result.rows).toEqual([[7, 3, 145, 5.3]])
  })

  it('covers every year when none is chosen', () => {
    const question = QUESTIONS.find((entry) => entry.id === 'month-by-month')!
    const result = run(question.build(fields, { units: 'metric', year: null, limit: 20 }))
    expect(result.rows.map((row) => row[0])).toEqual([7, 11])
  })
})

describe('all-time totals', () => {
  it('counts every activity in the archive exactly once', () => {
    const question = QUESTIONS.find((entry) => entry.id === 'totals-by-sport')!
    const result = run(question.build(fields, { units: 'metric', year: null, limit: 20 }))
    const bySport = rowsByFirstColumn(result)
    expect(bySport.get('cycling')).toEqual(['cycling', 3, 195, 6.3, 2360])
    expect(bySport.get('running')).toEqual(['running', 2, 15, 1.4, 160])
  })

  /**
   * The archived duplicate of the long ride is 118 km sitting in the same file. Counting it
   * would report 313 km of cycling — the tier-two version of the 89 km ride that was really
   * 42 km, and the reason `visibility` is in the Parquet at all.
   */
  it('leaves the duplicate recordings out, without leaving them behind', () => {
    const question = QUESTIONS.find((entry) => entry.id === 'totals-by-sport')!
    const sql = question.build(fields, { units: 'metric', year: null, limit: 20 })
    expect(sql).toContain(`WHERE "visibility" = 'active'`)

    const everything = run(`SELECT count(*) AS n FROM activities`)
    const counted = run(`${sql}`).rows.reduce((total, row) => total + Number(row[1]), 0)
    expect(everything.rows[0]?.[0]).toBe(6)
    expect(counted).toBe(5)
  })
})

describe('longest efforts', () => {
  it('ranks single activities and dates them in the athlete’s own timezone', () => {
    const question = QUESTIONS.find((entry) => entry.id === 'longest')!
    const result = run(question.build(fields, { units: 'metric', year: null, limit: 3 }))
    expect(result.rows.length).toBe(3)
    expect(result.rows[0]?.[1]).toBe('Long ride')
    expect(result.rows[0]?.[3]).toBe(120)
    // 1785040800000 is 2026-07-26 04:40Z; +120 minutes puts it on the 26th where it was ridden.
    expect(result.rows[0]?.[0]).toBe('2026-07-26')
  })
})

describe('the starter query in the free-text box', () => {
  it('runs as written, because an athlete will press Run before editing it', () => {
    const result = run(starterSql(fields))
    expect(result.columns).toEqual(['sport', 'n'])
    expect(result.rows).toEqual([
      ['cycling', 3],
      ['running', 2],
    ])
  })
})

describe('an archive missing a column', () => {
  it('still answers the questions that do not need it', () => {
    connection.query(
      `CREATE OR REPLACE VIEW activities AS
       SELECT activity_id, sport, start_time, elapsed_seconds, distance_m, visibility,
              year, month
       FROM read_parquet('${join(directory, '**', '*.parquet')}', union_by_name = true,
                         hive_partitioning = true)`,
    )
    const thin = mapFields(describedColumns(run(DESCRIBE_SQL)))
    expect(thin.movingSeconds).toBeUndefined()
    expect(thin.elevationGainM).toBeUndefined()

    const totals = QUESTIONS.find((entry) => entry.id === 'totals-by-sport')!
    expect(availability(thin).find((entry) => entry.question.id === totals.id)?.missing).toEqual([])

    const result = run(totals.build(thin, { units: 'metric', year: null, limit: 20 }))
    // No climbing column at all, and the hours fall back to elapsed time.
    expect(result.columns).toEqual(['Sport', 'Activities', 'Distance (km)', 'Hours'])
    expect(rowsByFirstColumn(result).get('cycling')).toEqual(['cycling', 3, 195, 6.5])
  })
})
