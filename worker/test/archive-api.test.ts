import { createExecutionContext, env, waitOnExecutionContext } from 'cloudflare:test'
import { afterEach, describe, expect, it, vi } from 'vitest'
import worker from '../src/index'
import { compactArchive } from '../src/archive/compaction'
import { DEFAULT_TTL_SECONDS, MAX_TTL_SECONDS } from '../src/archive/credentials'
import type { Bindings } from '../src/types'

const bindings = env as unknown as Bindings
const BASE = 'https://healthhub.test'

/**
 * `/api/archive` — the manifest, and the scoped credentials an athlete mints for their own
 * prefix.
 *
 * The interesting assertions are about what the Worker sends *upstream*: the prefix in the
 * Cloudflare request is the one property standing between one athlete and everyone else's
 * history, so it is checked against the request body rather than only against the response.
 */

let seq = 0
const uniqueEmail = () => `archive-api-${Date.now()}-${seq++}@example.com`

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const ctx = createExecutionContext()
  const response = await worker.fetch(new Request(`${BASE}${path}`, init), env, ctx)
  await waitOnExecutionContext(ctx)
  return response
}

/** Each athlete gets its own client IP: sign-up is rate limited per IP. */
async function newAthlete(): Promise<{ cookie: string; userId: string }> {
  const email = uniqueEmail()
  const registered = await request('/api/auth/register', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'cf-connecting-ip': `10.5.0.${(seq % 250) + 1}`,
    },
    body: JSON.stringify({ email, password: 'correct-horse-battery', displayName: 'Archivist' }),
  })
  expect(registered.status).toBe(201)
  const cookie = registered.headers.get('set-cookie')!.split(';')[0]!
  const row = await bindings.DB.prepare('SELECT id FROM users WHERE email_norm = ?')
    .bind(email.toLowerCase())
    .first<{ id: string }>()
  return { cookie, userId: row!.id }
}

/**
 * Turns the R2 API config on for one test.
 *
 * The pool's `env` is a live object shared by the whole run, so anything set here is undone
 * afterwards — otherwise the "not configured" case would pass or fail depending on file order.
 */
function withR2Api(): () => void {
  bindings.CF_ACCOUNT_ID = 'acct-test'
  bindings.R2_API_TOKEN = 'token-test'
  bindings.R2_PARENT_ACCESS_KEY_ID = 'parent-key'
  bindings.R2_BUCKET_NAME = 'healthhub-data'
  return () => {
    delete bindings.CF_ACCOUNT_ID
    delete bindings.R2_API_TOKEN
    delete bindings.R2_PARENT_ACCESS_KEY_ID
    delete bindings.R2_BUCKET_NAME
  }
}

/** Stands in for Cloudflare's temporary-credentials endpoint and records what it was asked. */
function stubCloudflare(): { calls: { url: string; body: Record<string, unknown> }[] } {
  const calls: { url: string; body: Record<string, unknown> }[] = []
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      body: JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>,
    })
    return new Response(
      JSON.stringify({
        success: true,
        result: {
          accessKeyId: 'scoped-key-id',
          secretAccessKey: 'scoped-secret',
          sessionToken: 'scoped-session-token',
        },
      }),
      { headers: { 'content-type': 'application/json' } },
    )
  })
  return { calls }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('archive manifest', () => {
  it('lists nothing before anything has been compacted', async () => {
    const { cookie, userId } = await newAthlete()
    const response = await request('/api/archive', { headers: { cookie } })
    expect(response.status).toBe(200)
    const body = (await response.json()) as { prefix: string; months: unknown[] }
    expect(body.prefix).toBe(`u/${userId}/archive/`)
    expect(body.months).toEqual([])
  })

  it('reports the exact part keys of a compacted month', async () => {
    const { cookie, userId } = await newAthlete()
    const start = Date.UTC(2019, 10, 12, 9)
    await bindings.DB.prepare(
      `INSERT INTO activities
         (id, user_id, source_uid, sport, title, start_time, end_time, tz_offset_minutes,
          elapsed_seconds, sample_count, created_at, updated_at)
       VALUES (?, ?, ?, 'running', 'Long Run', ?, ?, 0, 3600, 10, ?, ?)`,
    )
      .bind(`m-${userId}`, userId, `uid-m-${userId}`, start, start + 3_600_000, start, start)
      .run()

    await compactArchive(bindings, { now: Date.UTC(2026, 6, 27), maxMonthsPerRun: 500 })

    const body = (await (await request('/api/archive', { headers: { cookie } })).json()) as {
      bucket: string
      months: { year: number; month: number; rowCount: number; parts: string[] }[]
    }
    expect(body.bucket).toBe('healthhub-data')
    expect(body.months).toHaveLength(1)
    expect(body.months[0]).toMatchObject({ year: 2019, month: 11, rowCount: 1, partCount: 1 })
    // The manifest hands over keys, not a glob: DuckDB-Wasm cannot list a bucket.
    expect(body.months[0]!.parts).toEqual([
      `u/${userId}/archive/activities/year=2019/month=11/part-0001.parquet`,
    ])
  })

  it('shows an athlete only their own months', async () => {
    const mine = await newAthlete()
    const theirs = await newAthlete()
    const body = (await (
      await request('/api/archive', { headers: { cookie: theirs.cookie } })
    ).json()) as { prefix: string; months: unknown[] }
    expect(body.prefix).toBe(`u/${theirs.userId}/archive/`)
    expect(body.prefix).not.toContain(mine.userId)
    expect(body.months).toEqual([])
  })

  it('needs authentication', async () => {
    expect((await request('/api/archive')).status).toBe(401)
  })

  /**
   * Account deletion is the one place "nothing is ever deleted" does not apply, and the
   * archive tier is easy to miss there: the parts go with the `u/{user_id}/` prefix sweep,
   * but the rows that name them are in three tables nobody thought about in session 2.
   */
  it('leaves nothing behind when the account is deleted', async () => {
    const { cookie, userId } = await newAthlete()
    const start = Date.UTC(2018, 3, 4, 9)
    await bindings.DB.prepare(
      `INSERT INTO activities
         (id, user_id, source_uid, sport, title, start_time, end_time, tz_offset_minutes,
          elapsed_seconds, sample_count, created_at, updated_at)
       VALUES (?, ?, ?, 'cycling', 'Spring Ride', ?, ?, 0, 3600, 10, ?, ?)`,
    )
      .bind(`del-${userId}`, userId, `uid-del-${userId}`, start, start + 3_600_000, start, start)
      .run()
    await compactArchive(bindings, { now: Date.UTC(2026, 6, 27), maxMonthsPerRun: 500 })
    expect((await bindings.BLOBS.list({ prefix: `u/${userId}/` })).objects).toHaveLength(1)

    const deleted = await request('/api/auth/me', { method: 'DELETE', headers: { cookie } })
    expect(deleted.status).toBe(204)

    expect((await bindings.BLOBS.list({ prefix: `u/${userId}/` })).objects).toEqual([])
    const left = await bindings.DB.prepare(
      `SELECT (SELECT count(*) FROM archive_months WHERE user_id = ?1) AS months,
              (SELECT count(*) FROM archive_parts WHERE user_id = ?1) AS parts,
              (SELECT count(*) FROM archive_credentials WHERE user_id = ?1) AS credentials`,
    )
      .bind(userId)
      .first<{ months: number; parts: number; credentials: number }>()
    expect(left).toEqual({ months: 0, parts: 0, credentials: 0 })
  })
})

describe('archive credentials', () => {
  it('is absent when the deployment has no R2 API token', async () => {
    const { cookie } = await newAthlete()
    const response = await request('/api/archive/credentials', {
      method: 'POST',
      headers: { cookie, 'content-type': 'application/json' },
      body: '{}',
    })
    expect(response.status).toBe(404)
  })

  it('mints a read-only key scoped to the caller and records only its public half', async () => {
    const restore = withR2Api()
    const { calls } = stubCloudflare()
    try {
      const { cookie, userId } = await newAthlete()
      const response = await request('/api/archive/credentials', {
        method: 'POST',
        headers: { cookie, 'content-type': 'application/json' },
        body: JSON.stringify({ label: 'laptop duckdb' }),
      })
      expect(response.status).toBe(201)

      const body = (await response.json()) as {
        credentials: Record<string, unknown>
        duckdb: string
      }
      expect(body.credentials).toMatchObject({
        accessKeyId: 'scoped-key-id',
        secretAccessKey: 'scoped-secret',
        sessionToken: 'scoped-session-token',
        permission: 'object-read-only',
        prefix: `u/${userId}/archive/`,
        endpoint: 'https://acct-test.r2.cloudflarestorage.com',
      })
      expect(body.credentials.expiresAt).toBe(
        (body.credentials.issuedAt as number) + DEFAULT_TTL_SECONDS * 1000,
      )
      expect(body.duckdb).toContain(`s3://healthhub-data/u/${userId}/archive/activities/`)

      // What Cloudflare was actually asked for.
      expect(calls).toHaveLength(1)
      expect(calls[0]!.url).toBe(
        'https://api.cloudflare.com/client/v4/accounts/acct-test/r2/temp-access-credentials',
      )
      expect(calls[0]!.body).toMatchObject({
        bucket: 'healthhub-data',
        parentAccessKeyId: 'parent-key',
        permission: 'object-read-only',
        prefixes: [`u/${userId}/archive/`],
      })

      // The secret is returned once and never stored.
      const stored = await bindings.DB.prepare(
        'SELECT access_key_id, prefix, permission, label FROM archive_credentials WHERE user_id = ?',
      )
        .bind(userId)
        .first<Record<string, string>>()
      expect(stored).toEqual({
        access_key_id: 'scoped-key-id',
        prefix: `u/${userId}/archive/`,
        permission: 'object-read-only',
        label: 'laptop duckdb',
      })
      const columns = await bindings.DB.prepare(
        "SELECT name FROM pragma_table_info('archive_credentials')",
      ).all<{ name: string }>()
      expect(columns.results.map((column) => column.name)).not.toContain('secret_access_key')
    } finally {
      restore()
    }
  })

  /**
   * The property the whole feature rests on: an athlete can ask for a key, and cannot ask for
   * anyone else's. There is no parameter for it, so the attempt has to be made in the body and
   * shown to have no effect on what leaves the Worker.
   */
  it('ignores a prefix, bucket or permission named by the caller', async () => {
    const restore = withR2Api()
    const { calls } = stubCloudflare()
    try {
      const victim = await newAthlete()
      const attacker = await newAthlete()

      const response = await request('/api/archive/credentials', {
        method: 'POST',
        headers: { cookie: attacker.cookie, 'content-type': 'application/json' },
        body: JSON.stringify({
          prefix: `u/${victim.userId}/archive/`,
          prefixes: ['u/'],
          bucket: 'someone-elses-bucket',
          permission: 'object-read-write',
          parentAccessKeyId: 'not-ours',
        }),
      })
      expect(response.status).toBe(201)

      expect(calls[0]!.body).toMatchObject({
        bucket: 'healthhub-data',
        parentAccessKeyId: 'parent-key',
        permission: 'object-read-only',
        prefixes: [`u/${attacker.userId}/archive/`],
      })
      expect(JSON.stringify(calls[0]!.body)).not.toContain(victim.userId)
    } finally {
      restore()
    }
  })

  it('refuses a TTL outside the bounds', async () => {
    const restore = withR2Api()
    stubCloudflare()
    try {
      const { cookie } = await newAthlete()
      const tooLong = await request('/api/archive/credentials', {
        method: 'POST',
        headers: { cookie, 'content-type': 'application/json' },
        body: JSON.stringify({ ttlSeconds: MAX_TTL_SECONDS + 1 }),
      })
      expect(tooLong.status).toBe(422)

      const tooShort = await request('/api/archive/credentials', {
        method: 'POST',
        headers: { cookie, 'content-type': 'application/json' },
        body: JSON.stringify({ ttlSeconds: 60 }),
      })
      expect(tooShort.status).toBe(422)
    } finally {
      restore()
    }
  })

  it('surfaces an upstream failure as a 500 without leaking the reason', async () => {
    const restore = withR2Api()
    vi.stubGlobal(
      'fetch',
      async () =>
        new Response(
          JSON.stringify({
            success: false,
            errors: [{ code: 10001, message: 'parent access key parent-key is disabled' }],
          }),
          { status: 403, headers: { 'content-type': 'application/json' } },
        ),
    )
    try {
      const { cookie } = await newAthlete()
      const response = await request('/api/archive/credentials', {
        method: 'POST',
        headers: { cookie, 'content-type': 'application/json' },
        body: '{}',
      })
      expect(response.status).toBe(500)
      expect(await response.text()).not.toContain('parent-key')
    } finally {
      restore()
    }
  })

  it('lists issued credentials without their secrets', async () => {
    const restore = withR2Api()
    stubCloudflare()
    try {
      const { cookie } = await newAthlete()
      await request('/api/archive/credentials', {
        method: 'POST',
        headers: { cookie, 'content-type': 'application/json' },
        body: JSON.stringify({ ttlSeconds: 900 }),
      })

      const listed = (await (
        await request('/api/archive/credentials', { headers: { cookie } })
      ).json()) as { credentials: Record<string, unknown>[] }
      expect(listed.credentials).toHaveLength(1)
      expect(listed.credentials[0]).toMatchObject({
        accessKeyId: 'scoped-key-id',
        permission: 'object-read-only',
        expired: false,
      })
      expect(JSON.stringify(listed)).not.toContain('scoped-secret')
      expect(JSON.stringify(listed)).not.toContain('scoped-session-token')
    } finally {
      restore()
    }
  })
})
