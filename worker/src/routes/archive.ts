import { Hono } from 'hono'
import type { AppEnv } from '../types'
import {
  bucketName,
  clampTtl,
  duckdbSnippet,
  mintScopedCredentials,
  r2ApiConfig,
  READ_ONLY_PERMISSION,
  s3Endpoint,
} from '../archive/credentials'
import { fail } from '../lib/errors'
import { authenticate } from '../lib/guard'
import { archivePrefix } from '../lib/keys'
import { ARCHIVE_CREDENTIAL_LIMIT, enforce } from '../lib/ratelimit'
import { jsonBody, optionalInt, optionalStr } from '../lib/validate'

/**
 * The analytical archive tier — Hive-partitioned Parquet under `u/{user_id}/archive/`,
 * assembled by the scheduled compaction job and read by DuckDB, ClickHouse or Polars.
 *
 * Not to be confused with `GET /api/activities?view=archive`, which is the athlete's archived
 * duplicate recordings. Same word, unrelated feature.
 *
 * The Worker never reads a Parquet file it wrote (R-014). These routes do two things: they
 * say which parts are live — the manifest, which is the authority while a rebuild is in
 * flight — and they mint credentials scoped to the caller's own prefix and nothing else.
 */

interface MonthRow {
  year: number
  month: number
  dataset: string
  generation: number
  part_count: number
  row_count: number
  bytes: number
  compacted_at: number
}

interface PartRow {
  key: string
  year: number
  month: number
  dataset: string
}

export const archiveRoutes = new Hono<AppEnv>()

  .use('*', authenticate)

  /**
   * What has been compacted, and the exact object keys that make it up.
   *
   * The key list matters: a glob over a month's prefix is correct except during the seconds a
   * rebuild is replacing that month, while this list is transactional and always exact. A
   * client that cares reads `parts` and hands the keys to `read_parquet([...])`.
   */
  .get('/', async (c) => {
    const { userId } = c.get('principal')
    const config = r2ApiConfig(c.env)

    const [months, parts] = await Promise.all([
      c.env.DB.prepare(
        `SELECT year, month, dataset, generation, part_count, row_count, bytes, compacted_at
           FROM archive_months WHERE user_id = ?
          ORDER BY year DESC, month DESC`,
      )
        .bind(userId)
        .all<MonthRow>(),
      c.env.DB.prepare(
        `SELECT key, year, month, dataset
           FROM archive_parts WHERE user_id = ?
          ORDER BY year DESC, month DESC, part_index ASC`,
      )
        .bind(userId)
        .all<PartRow>(),
    ])

    const prefix = archivePrefix(userId)
    return c.json({
      prefix,
      // The bucket name is an identifier, not a secret — it is in wrangler.jsonc in plain
      // sight — so it is served even where credentials cannot be minted, because a client
      // with keys of its own still needs to know where to look.
      bucket: bucketName(c.env.R2_BUCKET_NAME),
      endpoint: config ? s3Endpoint(config) : null,
      credentialsAvailable: config !== null,
      months: months.results.map((month) => ({
        dataset: month.dataset,
        year: month.year,
        month: month.month,
        generation: month.generation,
        partCount: month.part_count,
        rowCount: month.row_count,
        bytes: month.bytes,
        compactedAt: month.compacted_at,
        // Keys in part order. A client hands these to `read_parquet([...])` instead of
        // globbing the prefix — which is not merely tidier: DuckDB-Wasm's S3 filesystem
        // cannot list a bucket at all, and a rebuild in flight can leave a glob briefly
        // wrong. This list is the manifest, and it flips in one transaction.
        parts: parts.results
          .filter(
            (part) =>
              part.dataset === month.dataset &&
              part.year === month.year &&
              part.month === month.month,
          )
          .map((part) => part.key),
      })),
    })
  })

  /** Credentials the athlete has issued, and when they stop working. Never the secret. */
  .get('/credentials', async (c) => {
    const { userId } = c.get('principal')
    const { results } = await c.env.DB.prepare(
      `SELECT id, access_key_id, prefix, permission, label, issued_at, expires_at
         FROM archive_credentials WHERE user_id = ? ORDER BY issued_at DESC LIMIT 50`,
    )
      .bind(userId)
      .all<{
        id: string
        access_key_id: string
        prefix: string
        permission: string
        label: string | null
        issued_at: number
        expires_at: number
      }>()

    const now = Date.now()
    return c.json({
      credentials: results.map((row) => ({
        id: row.id,
        accessKeyId: row.access_key_id,
        prefix: row.prefix,
        permission: row.permission,
        label: row.label,
        issuedAt: row.issued_at,
        expiresAt: row.expires_at,
        expired: row.expires_at <= now,
      })),
    })
  })

  /**
   * Mints a read-only key for the caller's own archive prefix.
   *
   * There is deliberately no way to ask for a different prefix, a different bucket or a
   * different permission: all three are derived, and the only thing the body can influence is
   * how soon the key stops working.
   */
  .post('/credentials', async (c) => {
    const { userId } = c.get('principal')
    const config = r2ApiConfig(c.env)
    if (!config) fail('not_found', 'This deployment does not issue archive credentials.')

    await enforce(c.env.DB, ARCHIVE_CREDENTIAL_LIMIT, userId)

    const body = await jsonBody(c.req.raw)
    const ttlSeconds = clampTtl(optionalInt(body, 'ttlSeconds'))
    const label = optionalStr(body, 'label', { max: 80 })

    const prefix = archivePrefix(userId)
    const credentials = await mintScopedCredentials(config, { prefix, ttlSeconds })

    const now = Date.now()
    const expiresAt = now + ttlSeconds * 1000
    const id = crypto.randomUUID()

    // Only the public half is recorded. The secret exists in this response and nowhere else.
    await c.env.DB.prepare(
      `INSERT INTO archive_credentials
         (id, user_id, access_key_id, prefix, permission, label, issued_at, expires_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind(
        id,
        userId,
        credentials.accessKeyId,
        prefix,
        READ_ONLY_PERMISSION,
        label,
        now,
        expiresAt,
      )
      .run()

    return c.json(
      {
        credentials: {
          id,
          accessKeyId: credentials.accessKeyId,
          secretAccessKey: credentials.secretAccessKey,
          sessionToken: credentials.sessionToken,
          permission: READ_ONLY_PERMISSION,
          bucket: config.bucket,
          prefix,
          endpoint: s3Endpoint(config),
          issuedAt: now,
          expiresAt,
          label,
        },
        duckdb: duckdbSnippet({ config, credentials, prefix }),
      },
      201,
    )
  })
