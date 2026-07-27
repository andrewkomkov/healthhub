import { configuredBundleBase, selfHostedBundles, type DuckDbBundles } from './bundle'
import { describedColumns, mapFields, quoteLiteral, type FieldMap } from './fields'
import { VIEW } from './queries'
import { toResultSet, type ArrowLike, type ResultSet } from './rows'

/**
 * DuckDB, in the athlete's browser, reading Parquet out of the athlete's own bucket.
 *
 * The placement is the decision (R-014): a query engine on the edge would put analysis back on
 * a server, which is the one thing this architecture exists to avoid, and would need long-lived
 * S3 credentials for every athlete to live in the Worker. Here the credentials are scoped,
 * read-only, time-limited and never leave the tab — and R2 charges no egress, so a query over a
 * decade of history costs nobody anything.
 *
 * Everything below is behind a dynamic `import()`. The library is comfortably larger than the
 * rest of the application put together, and the feed's first screen has a two-second budget it
 * would obliterate. Nothing on the feed path may import this file.
 */

type DuckDbModule = typeof import('@duckdb/duckdb-wasm')
type AsyncDuckDB = InstanceType<DuckDbModule['AsyncDuckDB']>
type AsyncConnection = Awaited<ReturnType<AsyncDuckDB['connect']>>

export interface S3Credentials {
  /** `<account>.r2.cloudflarestorage.com`, with or without a scheme. */
  endpoint: string
  bucket: string
  accessKeyId: string
  secretAccessKey: string
  /** Present for temporary credentials, absent for a plain R2 API token. */
  sessionToken?: string | null
  region?: string | null
}

export interface QueryOutcome {
  result: ResultSet
  elapsedMs: number
}

/**
 * The view every question reads from.
 *
 * `union_by_name` keeps a rebuilt month readable next to an older one whose column set was not
 * identical, and `hive_partitioning` turns `year=2026/month=07` in the key into columns — which
 * is what lets a filter on a year skip whole files before fetching any column data. A file that
 * already has a `year` column of its own makes that ambiguous, hence the `hive = false` form.
 *
 * Exported because it is the statement under test: the paired DuckDB test builds this same view
 * over a real Parquet file rather than over a description of one.
 */
export function createViewSql(urls: readonly string[], hive: boolean): string {
  const list = urls.map(quoteLiteral).join(', ')
  return `CREATE OR REPLACE VIEW ${VIEW} AS SELECT * FROM read_parquet([${list}], union_by_name = true${
    hive ? ', hive_partitioning = true' : ''
  })`
}

export const DESCRIBE_SQL = `DESCRIBE SELECT * FROM ${VIEW}`

/** DuckDB wants a bare host in `s3_endpoint`; a pasted URL is the likeliest input. */
export function endpointHost(endpoint: string): string {
  return endpoint
    .trim()
    .replace(/^https?:\/\//i, '')
    .replace(/\/+$/, '')
}

export class ArchiveSession {
  private constructor(
    private readonly duckdb: DuckDbModule,
    private readonly db: AsyncDuckDB,
    private readonly connection: AsyncConnection,
    readonly version: string,
    /** How long the engine took to arrive and start, for the status line. */
    readonly loadMs: number,
  ) {}

  static async open(): Promise<ArchiveSession> {
    const started = performance.now()
    const duckdb = await import('@duckdb/duckdb-wasm')

    const base = configuredBundleBase()
    const bundles: DuckDbBundles = base
      ? selfHostedBundles(base)
      : (duckdb.getJsDelivrBundles() as DuckDbBundles)
    const bundle = await duckdb.selectBundle(bundles)
    if (!bundle.mainWorker) throw new Error('No DuckDB worker for this browser.')

    // `createWorker` wraps the URL in a blob that calls `importScripts`, which is what makes a
    // cross-origin worker legal at all — `new Worker('https://cdn…')` is blocked outright.
    const worker = await duckdb.createWorker(bundle.mainWorker)
    const db = new duckdb.AsyncDuckDB(new duckdb.ConsoleLogger(duckdb.LogLevel.WARNING), worker)
    await db.instantiate(bundle.mainModule, bundle.pthreadWorker ?? undefined)

    const connection = await db.connect()
    const version = await db.getVersion().catch(() => 'unknown')
    return new ArchiveSession(duckdb, db, connection, version, performance.now() - started)
  }

  /**
   * Hands DuckDB the athlete's scoped keys.
   *
   * They are set on the connection and held nowhere else — not in `localStorage`, not in a
   * cookie, not in a URL. Closing the tab is what revokes them early; the issued TTL is what
   * revokes them anyway, because Cloudflare's temporary credentials cannot be withdrawn.
   */
  async useCredentials(credentials: S3Credentials): Promise<void> {
    // Exactly five settings, and no more: DuckDB-Wasm does not load the httpfs extension —
    // it has its own S3 filesystem written in JS — so `s3_url_style` and `s3_use_ssl` are not
    // parameters here at all, and asking for either throws "Extension parameter … was not
    // found after autoloading" before the first byte is read. The CLI accepts both. Verified
    // against 1.32.0 in the browser and in the node bindings.
    const statements = [
      `SET s3_endpoint = ${quoteLiteral(endpointHost(credentials.endpoint))}`,
      `SET s3_region = ${quoteLiteral(credentials.region?.trim() || 'auto')}`,
      `SET s3_access_key_id = ${quoteLiteral(credentials.accessKeyId.trim())}`,
      `SET s3_secret_access_key = ${quoteLiteral(credentials.secretAccessKey.trim())}`,
    ]
    if (credentials.sessionToken?.trim()) {
      statements.push(`SET s3_session_token = ${quoteLiteral(credentials.sessionToken.trim())}`)
    }
    for (const statement of statements) await this.connection.query(statement)
  }

  /**
   * Registers Parquet parts the athlete dropped onto the page, and answers with the names to
   * query them by.
   *
   * This exists because the archive is not always reachable — an athlete auditing a month they
   * pulled out of the R2 dashboard, or a deployment whose bucket has no CORS rule for this
   * origin yet, still has the file itself. DuckDB reads it through the browser's own
   * `FileReader`, so nothing is uploaded anywhere.
   */
  async registerFiles(files: readonly File[]): Promise<string[]> {
    const names: string[] = []
    for (const file of files) {
      await this.db.registerFileHandle(
        file.name,
        file,
        this.duckdb.DuckDBDataProtocol.BROWSER_FILEREADER,
        true,
      )
      names.push(file.name)
    }
    return names
  }

  /**
   * Points the `activities` view at a set of Parquet objects and reports what they contain.
   *
   * The retry without Hive partitioning is for an archive whose files already carry a `year`
   * column: the partition would collide with it, and the athlete would see a binder error
   * rather than their history.
   */
  async attach(urls: readonly string[]): Promise<FieldMap> {
    if (urls.length === 0) throw new Error('There are no Parquet parts to read.')

    try {
      await this.connection.query(createViewSql(urls, true))
      await this.connection.query(`SELECT 1 FROM ${VIEW} LIMIT 0`)
    } catch {
      await this.connection.query(createViewSql(urls, false))
    }

    const described = toResultSet(
      (await this.connection.query(DESCRIBE_SQL)) as unknown as ArrowLike,
    )
    return mapFields(describedColumns(described))
  }

  async run(sql: string): Promise<QueryOutcome> {
    const started = performance.now()
    const table = (await this.connection.query(sql)) as unknown as ArrowLike
    return { result: toResultSet(table), elapsedMs: performance.now() - started }
  }

  async close(): Promise<void> {
    await this.connection.close().catch(() => undefined)
    await this.db.terminate().catch(() => undefined)
  }
}
