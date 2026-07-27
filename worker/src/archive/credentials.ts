import type { Bindings } from '../types'
import { fail } from '../lib/errors'

/**
 * Scoped, read-only, expiring R2 credentials an athlete mints for their own archive prefix.
 *
 * This is the one place where something other than the Worker is allowed to read an athlete's
 * bytes, and it exists because of R-014: DuckDB runs in the athlete's browser (or on their
 * laptop), reads Parquet over range requests, and R2 charges no egress. Proxying hundreds of
 * range requests through the Worker would add nothing but latency and a bill.
 *
 * Three properties make that safe, and all three are enforced here rather than trusted:
 *
 * - **The prefix comes from the principal, never from the request.** `u/{user_id}/archive/`,
 *   built from the authenticated user id. A body that names someone else's prefix is ignored;
 *   there is no parameter that could carry one.
 * - **Read only.** Cloudflare's `object-read-only` permission. A leaked credential cannot
 *   write, cannot delete and cannot list another prefix.
 * - **Expiring.** Cloudflare temporary credentials cannot be revoked before their TTL, so the
 *   TTL is bounded here (15 minutes to 7 days, an hour by default) instead of open-ended.
 *
 * The parent access key never leaves the Worker's secrets, and the minted secret is returned
 * to the athlete once and never stored — D1 keeps the access key id and the expiry so the
 * athlete can see what is outstanding.
 *
 * Like Auth0, this is **optional**: a fork that has not created an R2 API token still gets
 * everything else, and the endpoint answers 404.
 */

export interface R2ApiConfig {
  accountId: string
  apiToken: string
  parentAccessKeyId: string
  bucket: string
}

export const READ_ONLY_PERMISSION = 'object-read-only'
export const MIN_TTL_SECONDS = 15 * 60
export const MAX_TTL_SECONDS = 7 * 24 * 60 * 60
export const DEFAULT_TTL_SECONDS = 60 * 60

/**
 * The bucket behind the `BLOBS` binding.
 *
 * A Worker cannot read a binding's bucket name at runtime, so it is configured. It is an
 * identifier rather than a secret — wrangler.jsonc carries the same value in plain sight —
 * and a fork that renamed the bucket sets `R2_BUCKET_NAME` to match.
 */
export function bucketName(configured: string | undefined): string {
  return configured ?? 'healthhub-data'
}

export function r2ApiConfig(env: Bindings): R2ApiConfig | null {
  const { CF_ACCOUNT_ID, R2_API_TOKEN, R2_PARENT_ACCESS_KEY_ID, R2_BUCKET_NAME } = env
  if (!CF_ACCOUNT_ID || !R2_API_TOKEN || !R2_PARENT_ACCESS_KEY_ID) return null
  return {
    accountId: CF_ACCOUNT_ID,
    apiToken: R2_API_TOKEN,
    parentAccessKeyId: R2_PARENT_ACCESS_KEY_ID,
    bucket: bucketName(R2_BUCKET_NAME),
  }
}

export interface ScopedCredentials {
  accessKeyId: string
  secretAccessKey: string
  sessionToken: string
}

interface TempCredentialsResponse {
  success?: boolean
  errors?: { code?: number; message?: string }[]
  result?: Partial<ScopedCredentials>
}

/** The S3-compatible endpoint the athlete's query engine talks to. */
export function s3Endpoint(config: R2ApiConfig): string {
  return `https://${config.accountId}.r2.cloudflarestorage.com`
}

/**
 * Asks Cloudflare for credentials restricted to one prefix of one bucket.
 *
 * `prefixes` is what does the scoping — the returned key can list and read below it and
 * nowhere else, so an athlete cannot reach another athlete's archive even with a valid key in
 * hand.
 */
export async function mintScopedCredentials(
  config: R2ApiConfig,
  request: { prefix: string; ttlSeconds: number },
): Promise<ScopedCredentials> {
  const response = await fetch(
    `https://api.cloudflare.com/client/v4/accounts/${config.accountId}/r2/temp-access-credentials`,
    {
      method: 'POST',
      headers: {
        authorization: `Bearer ${config.apiToken}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        bucket: config.bucket,
        parentAccessKeyId: config.parentAccessKeyId,
        permission: READ_ONLY_PERMISSION,
        ttlSeconds: request.ttlSeconds,
        prefixes: [request.prefix],
      }),
    },
  )

  const body = (await response.json().catch(() => null)) as TempCredentialsResponse | null
  const result = body?.result

  if (!response.ok || !result?.accessKeyId || !result.secretAccessKey || !result.sessionToken) {
    // The upstream message can name the account and the parent key, so it is logged and not
    // returned. The athlete gets a flat answer; the operator gets the detail in `wrangler tail`.
    console.error('r2 temp credentials failed', {
      status: response.status,
      errors: body?.errors,
    })
    fail('internal', 'Could not issue archive credentials. Try again shortly.')
  }

  return {
    accessKeyId: result.accessKeyId,
    secretAccessKey: result.secretAccessKey,
    sessionToken: result.sessionToken,
  }
}

export function clampTtl(seconds: number | null): number {
  if (seconds === null) return DEFAULT_TTL_SECONDS
  if (seconds < MIN_TTL_SECONDS || seconds > MAX_TTL_SECONDS) {
    fail(
      'validation_failed',
      `"ttlSeconds" must be between ${MIN_TTL_SECONDS} and ${MAX_TTL_SECONDS}.`,
    )
  }
  return Math.floor(seconds)
}

/**
 * A DuckDB snippet the athlete can paste unchanged.
 *
 * `TYPE s3` rather than `TYPE r2` because these are temporary credentials and only the s3
 * secret provider carries a session token. Hive partitioning is on, so `year` and `month` are
 * real columns and `WHERE year = 2026` never opens another month's file.
 */
export function duckdbSnippet(params: {
  config: R2ApiConfig
  credentials: ScopedCredentials
  prefix: string
}): string {
  const { config, credentials, prefix } = params
  return [
    'CREATE OR REPLACE SECRET healthhub (',
    '  TYPE s3,',
    `  KEY_ID '${credentials.accessKeyId}',`,
    `  SECRET '${credentials.secretAccessKey}',`,
    `  SESSION_TOKEN '${credentials.sessionToken}',`,
    `  ENDPOINT '${config.accountId}.r2.cloudflarestorage.com',`,
    "  REGION 'auto',",
    "  URL_STYLE 'path'",
    ');',
    '',
    'SELECT sport, count(*) AS activities, sum(distance_m) / 1000 AS km',
    `FROM read_parquet('s3://${config.bucket}/${prefix}activities/year=*/month=*/*.parquet',`,
    '                  hive_partitioning = true)',
    "WHERE visibility = 'active'",
    'GROUP BY sport',
    'ORDER BY km DESC;',
  ].join('\n')
}
