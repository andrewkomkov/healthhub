import { createExecutionContext, env, waitOnExecutionContext } from 'cloudflare:test'
import { beforeEach, describe, expect, it } from 'vitest'
import { CURRENT_CLIENT_SCHEME } from '../src/auth/password'
import worker from '../src/index'
import type { Bindings } from '../src/types'

/** The pool's `env` is untyped here; the Worker's own binding types are the truth. */
const bindings = env as unknown as Bindings

/**
 * Contract tests for the Worker.
 *
 * These run against real D1 and R2 inside workerd, because what is being tested is the SQL,
 * the ownership checks and the bindings — the exact things a mock would paper over.
 */

const BASE = 'https://healthhub.test'

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const ctx = createExecutionContext()
  const response = await worker.fetch(new Request(`${BASE}${path}`, init), env, ctx)
  // Lets any waitUntil work settle before the assertions run.
  await waitOnExecutionContext(ctx)
  return response
}

function json(path: string, body: unknown, init: RequestInit = {}) {
  return request(path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(init.headers ?? {}) },
    body: JSON.stringify(body),
    ...init,
  })
}

/**
 * Signs up an athlete and registers a device, returning both credentials.
 *
 * Each athlete gets its own client IP: auth is rate-limited per IP, and without this the
 * tenth sign-up in a suite gets a 429 — which is the limiter working correctly, not a bug.
 */
async function newAthlete(email: string) {
  const ip = { 'cf-connecting-ip': `10.0.0.${(counter % 250) + 1}` }
  const registered = await json(
    '/api/auth/register',
    { email, password: 'correct-horse-battery', displayName: 'Test Athlete' },
    { headers: ip },
  )
  expect(registered.status).toBe(201)

  const cookie = registered.headers.get('set-cookie')!.split(';')[0]!

  const device = await json(
    '/api/devices',
    { name: 'Pixel Test', platform: 'android' },
    { headers: { cookie } },
  )
  expect(device.status).toBe(201)
  const { token } = (await device.json()) as { token: string }

  return { cookie, auth: { authorization: `Bearer ${token}` } }
}

const sampleActivity = (sourceUid: string, overrides: Record<string, unknown> = {}) => ({
  sourceUid,
  sport: 'cycling',
  title: 'Evening Ride',
  startTime: 1_753_600_000_000,
  endTime: 1_753_618_000_000,
  tzOffsetMinutes: 180,
  elapsedSeconds: 18_000,
  distanceM: 92_310.4,
  avgHrBpm: 138,
  hasGps: true,
  routePolyline: '_p~iF~ps|U_ulLnnqC_mqNvxq`@',
  sampleCount: 184_320,
  channels: ['t', 'lat', 'lon', 'hr'],
  splits: [
    { unit: 'km', idx: 0, distanceM: 1000, elapsedSeconds: 186.2, avgHrBpm: 131 },
  ],
  zones: [{ kind: 'hr', zoneIndex: 1, lowerBound: 0, upperBound: 120, seconds: 1840 }],
  ...overrides,
})

let counter = 0
const uniqueEmail = () => `athlete-${Date.now()}-${counter++}@example.com`

/**
 * A stand-in for what a client's KDF produces.
 *
 * The Worker treats a proof as opaque bytes and could not check one if it wanted to: the
 * client derives it at 600,000 PBKDF2 iterations, six times the ceiling this runtime enforces,
 * which is the whole reason the client-side pass exists. What the two real implementations
 * agree on is pinned by `fixtures/auth/prehash-v1.json` instead, from the web and Kotlin sides.
 */
const proof = (seed: string) => ({
  scheme: CURRENT_CLIENT_SCHEME,
  value: btoa(seed.padEnd(32, '.').slice(0, 32)),
})

const storedRecord = (emailNorm: string) =>
  bindings.DB.prepare('SELECT password_hash FROM users WHERE email_norm = ?')
    .bind(emailNorm)
    .first<{ password_hash: string }>()
    .then((row) => row!.password_hash)

describe('auth', () => {
  it('registers, signs in and reports the current athlete', async () => {
    const email = uniqueEmail()
    const { cookie } = await newAthlete(email)

    const me = await request('/api/auth/me', { headers: { cookie } })
    expect(me.status).toBe(200)
    expect(((await me.json()) as { user: { email: string } }).user.email).toBe(email)
  })

  it('refuses a duplicate email', async () => {
    const email = uniqueEmail()
    await newAthlete(email)
    const again = await json('/api/auth/register', {
      email,
      password: 'correct-horse-battery',
      displayName: 'Impostor',
    })
    expect(again.status).toBe(409)
  })

  it('gives the same answer for an unknown email and a wrong password', async () => {
    const email = uniqueEmail()
    await newAthlete(email)
    const ip = { 'cf-connecting-ip': '10.9.9.9' }

    const wrongPassword = await json(
      '/api/auth/login',
      { email, password: 'nope-nope-nope' },
      { headers: ip },
    )
    const unknownEmail = await json(
      '/api/auth/login',
      { email: uniqueEmail(), password: 'nope-nope-nope' },
      { headers: ip },
    )

    expect(wrongPassword.status).toBe(401)
    expect(unknownEmail.status).toBe(401)
    // Distinguishing the two would let an attacker enumerate registered addresses.
    expect(await wrongPassword.json()).toEqual(await unknownEmail.json())
  })

  it('rejects a request with no credentials', async () => {
    expect((await request('/api/activities')).status).toBe(401)
  })

  it('rate-limits repeated sign-in attempts from one address', async () => {
    const ip = { 'cf-connecting-ip': '203.0.113.7' }
    const email = uniqueEmail()

    let sawRateLimit = false
    for (let i = 0; i < 15; i += 1) {
      const response = await json(
        '/api/auth/login',
        { email, password: 'wrong-password-here' },
        { headers: ip },
      )
      if (response.status === 429) {
        expect(response.headers.get('retry-after')).toBeTruthy()
        sawRateLimit = true
        break
      }
    }
    expect(sawRateLimit).toBe(true)
  })

  it('rejects a short password', async () => {
    const response = await json(
      '/api/auth/register',
      { email: uniqueEmail(), password: 'short', displayName: 'Test' },
      { headers: { 'cf-connecting-ip': '198.51.100.4' } },
    )
    expect(response.status).toBe(422)
  })
})

/**
 * The client-side pre-hash (R-006 amendment).
 *
 * Three clients and the Worker had to change together, and the thing that would break quietly
 * is the accounts that already exist: their stored record was built from the password itself,
 * and no proof can ever verify it. These tests are the proof that it still opens.
 */
describe('password schemes', () => {
  it('registers and signs in without the password ever leaving the client', async () => {
    const email = uniqueEmail()
    const ip = { 'cf-connecting-ip': '198.51.100.20' }

    const registered = await json(
      '/api/auth/register',
      { email, displayName: 'Test Athlete', passwordProofs: [proof('proof-one')] },
      { headers: ip },
    )
    expect(registered.status).toBe(201)
    expect(await storedRecord(email)).toContain(`$${CURRENT_CLIENT_SCHEME}`)

    const signedIn = await json(
      '/api/auth/login',
      { email, passwordProofs: [proof('proof-one')] },
      { headers: ip },
    )
    expect(signedIn.status).toBe(200)

    const wrong = await json(
      '/api/auth/login',
      { email, passwordProofs: [proof('proof-two')] },
      { headers: ip },
    )
    expect(wrong.status).toBe(401)
  })

  it('lets an account created under the old scheme sign in, and migrates it', async () => {
    const email = uniqueEmail()
    const ip = { 'cf-connecting-ip': '198.51.100.21' }

    // Exactly what the Worker stored before the amendment: four segments, no client scheme.
    const registered = await json(
      '/api/auth/register',
      { email, password: 'correct-horse-battery', displayName: 'Long-standing Athlete' },
      { headers: ip },
    )
    expect(registered.status).toBe(201)
    const before = await storedRecord(email)
    expect(before.split('$')).toHaveLength(4)

    // A proof alone cannot open it — the record was not built from one, and accepting it
    // would mean accepting a credential that proves nothing about this account.
    const proofOnly = await json(
      '/api/auth/login',
      { email, passwordProofs: [proof('proof-three')] },
      { headers: ip },
    )
    expect(proofOnly.status).toBe(401)

    // An up-to-date client sends both. The password opens the old record; the proof replaces it.
    const migrating = await json(
      '/api/auth/login',
      {
        email,
        password: 'correct-horse-battery',
        passwordProofs: [proof('proof-three')],
      },
      { headers: ip },
    )
    expect(migrating.status).toBe(200)

    const after = await storedRecord(email)
    expect(after.split('$')).toHaveLength(5)
    expect(after.endsWith(`$${CURRENT_CLIENT_SCHEME}`)).toBe(true)
    expect(after).not.toBe(before)

    // And the account now opens on the proof alone, which is what says the migration was real
    // rather than merely recorded.
    const afterwards = await json(
      '/api/auth/login',
      { email, passwordProofs: [proof('proof-three')] },
      { headers: ip },
    )
    expect(afterwards.status).toBe(200)

    // The password that used to work no longer does on its own: the record it matched is gone.
    const stalePassword = await json(
      '/api/auth/login',
      { email, password: 'correct-horse-battery' },
      { headers: ip },
    )
    expect(stalePassword.status).toBe(401)
  })

  it('still signs in a client that has not been updated at all', async () => {
    const email = uniqueEmail()
    const ip = { 'cf-connecting-ip': '198.51.100.22' }

    await json(
      '/api/auth/register',
      { email, password: 'correct-horse-battery', displayName: 'Old Client' },
      { headers: ip },
    )
    const signedIn = await json(
      '/api/auth/login',
      { email, password: 'correct-horse-battery' },
      { headers: ip },
    )
    expect(signedIn.status).toBe(200)
    // Nothing to migrate to: the request offered no proof, so the record is left alone.
    expect((await storedRecord(email)).split('$')).toHaveLength(4)
  })

  it('refuses a scheme it does not implement and a proof of the wrong width', async () => {
    const ip = { 'cf-connecting-ip': '198.51.100.23' }

    const unknownScheme = await json(
      '/api/auth/register',
      {
        email: uniqueEmail(),
        displayName: 'Test',
        passwordProofs: [{ scheme: 'pbkdf2-sha256/1/v9', value: proof('x').value }],
      },
      { headers: ip },
    )
    expect(unknownScheme.status).toBe(422)

    const shortProof = await json(
      '/api/auth/register',
      {
        email: uniqueEmail(),
        displayName: 'Test',
        passwordProofs: [{ scheme: CURRENT_CLIENT_SCHEME, value: btoa('too short') }],
      },
      { headers: ip },
    )
    expect(shortProof.status).toBe(422)

    const nothing = await json(
      '/api/auth/register',
      { email: uniqueEmail(), displayName: 'Test' },
      { headers: ip },
    )
    expect(nothing.status).toBe(422)
  })
})

describe('activities', () => {
  let auth: Record<string, string>
  let cookie: string

  beforeEach(async () => {
    const athlete = await newAthlete(uniqueEmail())
    auth = athlete.auth
    cookie = athlete.cookie
  })

  it('accepts an upload and stores its splits and zones verbatim', async () => {
    const created = await json('/api/activities', sampleActivity('hc-1'), { headers: auth })
    expect(created.status).toBe(201)

    const { activity } = (await created.json()) as { activity: { id: string } }
    const detail = await request(`/api/activities/${activity.id}`, { headers: { cookie } })
    const body = (await detail.json()) as {
      activity: { splits: unknown[]; zones: unknown[]; distanceM: number }
    }

    expect(body.activity.splits).toHaveLength(1)
    expect(body.activity.zones).toHaveLength(1)
    expect(body.activity.distanceM).toBeCloseTo(92_310.4)
  })

  it('returns the source uid on the detail response and in the feed', async () => {
    // The phone needs this to find the Health Connect session behind an activity before asking
    // for its GPS track. Without it the match falls back to start time plus source package,
    // which is ambiguous for the same walk recorded by two apps — and the imported route then
    // lands on the duplicate of the activity the athlete was looking at.
    const created = await json('/api/activities', sampleActivity('hc-route-match'), {
      headers: auth,
    })
    const { activity } = (await created.json()) as { activity: { id: string } }

    const detail = await request(`/api/activities/${activity.id}`, { headers: { cookie } })
    const body = (await detail.json()) as { activity: { sourceUid: string } }
    expect(body.activity.sourceUid).toBe('hc-route-match')

    // It is on the feed too, which it deliberately was not until the route backfill existed:
    // that pass asks "which of these workouts has no track, and which recording is each one"
    // and would otherwise have to fetch every detail row to find out, or match by start time —
    // the ambiguity this field was added to remove in the first place.
    const feed = await request('/api/activities', { headers: { cookie } })
    const { activities } = (await feed.json()) as { activities: Record<string, unknown>[] }
    expect(activities[0]).toHaveProperty('sourceUid', 'hc-route-match')
  })

  it('is idempotent on the source id', async () => {
    const first = await json('/api/activities', sampleActivity('hc-repeat'), { headers: auth })
    const second = await json('/api/activities', sampleActivity('hc-repeat'), { headers: auth })

    expect(first.status).toBe(201)
    expect(second.status).toBe(200) // updated, not created again

    const feed = await request('/api/activities', { headers: { cookie } })
    const { activities } = (await feed.json()) as { activities: unknown[] }
    expect(activities).toHaveLength(1)
  })

  it('hides duplicates from the feed but keeps them retrievable', async () => {
    await json('/api/activities', sampleActivity('hc-primary'), { headers: auth })
    await json(
      '/api/activities',
      sampleActivity('hc-secondary', { duplicateOf: 'hc-primary', sourceCount: 2 }),
      { headers: auth },
    )

    const feed = await request('/api/activities', { headers: { cookie } })
    expect(((await feed.json()) as { activities: unknown[] }).activities).toHaveLength(1)

    const all = await request('/api/activities?view=all', { headers: { cookie } })
    expect(((await all.json()) as { activities: unknown[] }).activities).toHaveLength(2)
  })

  it('pages with a stable keyset cursor', async () => {
    for (let i = 0; i < 5; i += 1) {
      const offset = i * 86_400_000
      const created = await json(
        '/api/activities',
        sampleActivity(`hc-page-${i}`, {
          // Both ends move: shifting only the start pushes it past the end, which the
          // validator correctly rejects with a 422.
          startTime: 1_753_600_000_000 + offset,
          endTime: 1_753_618_000_000 + offset,
        }),
        { headers: auth },
      )
      expect(created.status).toBe(201)
    }

    const first = await request('/api/activities?limit=2', { headers: { cookie } })
    const page = (await first.json()) as { activities: { id: string }[]; nextCursor: string }
    expect(page.activities).toHaveLength(2)
    expect(page.nextCursor).toBeTruthy()

    const second = await request(`/api/activities?limit=2&cursor=${page.nextCursor}`, {
      headers: { cookie },
    })
    const next = (await second.json()) as { activities: { id: string }[] }
    // No overlap between pages, which is the whole point of keyset over offset.
    const ids = new Set(page.activities.map((a) => a.id))
    expect(next.activities.some((a) => ids.has(a.id))).toBe(false)
  })

  it('rejects an end time before the start time', async () => {
    const response = await json(
      '/api/activities',
      sampleActivity('hc-backwards', { endTime: 1_753_500_000_000 }),
      { headers: auth },
    )
    expect(response.status).toBe(422)
  })

  it('refuses an upload authenticated with a browser session', async () => {
    // Only registered devices may write; a stolen session cookie must not be able to.
    const response = await json('/api/activities', sampleActivity('hc-session'), {
      headers: { cookie },
    })
    expect(response.status).toBe(403)
  })
})

describe('telemetry', () => {
  it('round-trips through R2 and refuses another athlete', async () => {
    const owner = await newAthlete(uniqueEmail())
    const created = await json('/api/activities', sampleActivity('hc-telemetry'), {
      headers: owner.auth,
    })
    const { activity } = (await created.json()) as { activity: { id: string } }

    const payload = new Uint8Array([72, 72, 84, 49, 1, 2, 3, 4])
    const put = await request(`/api/activities/${activity.id}/telemetry?variant=full`, {
      method: 'PUT',
      headers: { ...owner.auth, 'content-type': 'application/octet-stream' },
      body: payload,
    })
    expect(put.status).toBe(204)

    const get = await request(`/api/activities/${activity.id}/telemetry?variant=full`, {
      headers: { cookie: owner.cookie },
    })
    expect(get.status).toBe(200)
    expect(new Uint8Array(await get.arrayBuffer())).toEqual(payload)

    // Never `immutable`: importing a GPS route rewrites this object at the same key, and a
    // client holding a year-long copy would keep drawing a map with no route on it (R-015).
    expect(get.headers.get('cache-control')).toBe('private, max-age=0, must-revalidate')

    const rewritten = new Uint8Array([72, 72, 84, 49, 9, 9, 9, 9])
    await request(`/api/activities/${activity.id}/telemetry?variant=full`, {
      method: 'PUT',
      headers: { ...owner.auth, 'content-type': 'application/octet-stream' },
      body: rewritten,
    })
    const reread = await request(`/api/activities/${activity.id}/telemetry?variant=full`, {
      headers: { cookie: owner.cookie },
    })
    expect(new Uint8Array(await reread.arrayBuffer())).toEqual(rewritten)

    // A different athlete gets 404, not 403 — a 403 would confirm the id exists.
    const stranger = await newAthlete(uniqueEmail())
    const denied = await request(`/api/activities/${activity.id}/telemetry?variant=full`, {
      headers: { cookie: stranger.cookie },
    })
    expect(denied.status).toBe(404)
  })

  it('reports a missing variant as not found rather than erroring', async () => {
    const owner = await newAthlete(uniqueEmail())
    const created = await json('/api/activities', sampleActivity('hc-nopreview'), {
      headers: owner.auth,
    })
    const { activity } = (await created.json()) as { activity: { id: string } }

    const response = await request(`/api/activities/${activity.id}/telemetry?variant=preview`, {
      headers: { cookie: owner.cookie },
    })
    expect(response.status).toBe(404)
  })
})

describe('isolation', () => {
  it('never shows one athlete another athlete’s activity', async () => {
    const alice = await newAthlete(uniqueEmail())
    const created = await json('/api/activities', sampleActivity('hc-alice'), {
      headers: alice.auth,
    })
    const { activity } = (await created.json()) as { activity: { id: string } }

    const mallory = await newAthlete(uniqueEmail())
    const direct = await request(`/api/activities/${activity.id}`, {
      headers: { cookie: mallory.cookie },
    })
    expect(direct.status).toBe(404)

    const feed = await request('/api/activities', { headers: { cookie: mallory.cookie } })
    expect(((await feed.json()) as { activities: unknown[] }).activities).toHaveLength(0)
  })
})

describe('theme', () => {
  it('accepts a palette from a device and returns it to the browser', async () => {
    const athlete = await newAthlete(uniqueEmail())

    const put = await request('/api/theme', {
      method: 'PUT',
      headers: { ...athlete.auth, 'content-type': 'application/json' },
      body: JSON.stringify({
        light: { primary: '#43683D', surface: '#FBFAF5' },
        dark: { primary: '#A8D39B', surface: '#12140E' },
      }),
    })
    expect(put.status).toBe(204)

    const get = await request('/api/theme', { headers: { cookie: athlete.cookie } })
    const body = (await get.json()) as { theme: { light: Record<string, string> } }
    expect(body.theme.light['primary']).toBe('#43683d')
  })

  it('drops unknown roles instead of storing them', async () => {
    const athlete = await newAthlete(uniqueEmail())
    await request('/api/theme', {
      method: 'PUT',
      headers: { ...athlete.auth, 'content-type': 'application/json' },
      body: JSON.stringify({
        light: { primary: '#43683D', 'evil--}: url(x); --x': '#000000' },
        dark: { primary: '#A8D39B' },
      }),
    })

    const get = await request('/api/theme', { headers: { cookie: athlete.cookie } })
    const body = (await get.json()) as { theme: { light: Record<string, string> } }
    // This JSON becomes CSS custom properties, so an unrecognised key must never survive.
    expect(Object.keys(body.theme.light)).toEqual(['primary'])
  })

  it('rejects a value that is not a hex colour', async () => {
    const athlete = await newAthlete(uniqueEmail())
    const response = await request('/api/theme', {
      method: 'PUT',
      headers: { ...athlete.auth, 'content-type': 'application/json' },
      body: JSON.stringify({ light: { primary: 'red; --x: y' }, dark: { primary: '#000000' } }),
    })
    expect(response.status).toBe(422)
  })
})

describe('health', () => {
  it('answers the health check', async () => {
    const response = await request('/api/health')
    expect(response.status).toBe(200)
    expect(await response.json()).toEqual({ ok: true })
  })
})

const sampleNight = (sourceUid: string, overrides: Record<string, unknown> = {}) => ({
  sourceUid,
  sourcePackage: 'com.google.android.apps.fitness',
  title: 'Sleep',
  // 2025-07-26 23:10 to 2025-07-27 07:05 local, at +03:00 — deliberately either side of
  // local midnight, which is the case the local_date rule exists for.
  startTime: 1_753_560_600_000,
  endTime: 1_753_589_100_000,
  tzOffsetMinutes: 180,
  totalSeconds: 28_500,
  timeInBedSeconds: 29_400,
  stages: { awake: 900, light: 15_600, deep: 6_000, rem: 6_000 },
  stageCount: 42,
  ...overrides,
})

describe('health records', () => {
  let auth: Record<string, string>
  let cookie: string

  beforeEach(async () => {
    const athlete = await newAthlete(uniqueEmail())
    auth = athlete.auth
    cookie = athlete.cookie
  })

  it('stores measurements verbatim and is idempotent on the source id', async () => {
    const measurements = [
      {
        sourceUid: 'hc-rhr-1',
        kind: 'resting_heart_rate',
        measuredAt: 1_753_596_300_000,
        tzOffsetMinutes: 180,
        value: 48,
        unit: 'bpm',
      },
      {
        sourceUid: 'hc-bp-1',
        kind: 'blood_pressure',
        measuredAt: 1_753_596_400_000,
        tzOffsetMinutes: 180,
        value: 118,
        secondaryValue: 74,
        unit: 'mmHg',
      },
    ]

    const first = await json('/api/health-records/measurements', { measurements }, { headers: auth })
    expect(first.status).toBe(200)
    expect(await first.json()).toEqual({ accepted: 2 })

    // Re-reading a Health Connect window must never double a reading.
    await json('/api/health-records/measurements', { measurements }, { headers: auth })

    const listed = await request('/api/health-records/measurements', { headers: { cookie } })
    const body = (await listed.json()) as {
      measurements: { kind: string; value: number; secondaryValue: number | null }[]
    }
    expect(body.measurements).toHaveLength(2)

    const bp = body.measurements.find((m) => m.kind === 'blood_pressure')!
    expect(bp.value).toBe(118)
    expect(bp.secondaryValue).toBe(74)
  })

  it('filters measurements by kind and windows them by time', async () => {
    const day = 86_400_000
    const measurements = Array.from({ length: 5 }, (_, i) => ({
      sourceUid: `hc-hrv-${i}`,
      kind: i < 3 ? 'hrv_rmssd' : 'weight',
      measuredAt: 1_753_600_000_000 + i * day,
      tzOffsetMinutes: 180,
      value: 40 + i,
      unit: i < 3 ? 'ms' : 'kg',
    }))
    await json('/api/health-records/measurements', { measurements }, { headers: auth })

    const byKind = await request('/api/health-records/measurements?kind=hrv_rmssd', {
      headers: { cookie },
    })
    expect(((await byKind.json()) as { measurements: unknown[] }).measurements).toHaveLength(3)

    const windowed = await request(
      `/api/health-records/measurements?from=${1_753_600_000_000 + 3 * day}`,
      { headers: { cookie } },
    )
    expect(((await windowed.json()) as { measurements: unknown[] }).measurements).toHaveLength(2)
  })

  it('pages measurements with a stable keyset cursor', async () => {
    const measurements = Array.from({ length: 5 }, (_, i) => ({
      sourceUid: `hc-page-${i}`,
      kind: 'resting_heart_rate',
      measuredAt: 1_753_600_000_000 + i * 86_400_000,
      tzOffsetMinutes: 0,
      value: 50 + i,
      unit: 'bpm',
    }))
    await json('/api/health-records/measurements', { measurements }, { headers: auth })

    const first = await request('/api/health-records/measurements?limit=2', {
      headers: { cookie },
    })
    const page = (await first.json()) as { measurements: { id: string }[]; nextCursor: string }
    expect(page.measurements).toHaveLength(2)

    const second = await request(
      `/api/health-records/measurements?limit=2&cursor=${page.nextCursor}`,
      { headers: { cookie } },
    )
    const next = (await second.json()) as { measurements: { id: string }[] }
    const seen = new Set(page.measurements.map((m) => m.id))
    expect(next.measurements.some((m) => seen.has(m.id))).toBe(false)
  })

  it('rejects a kind that is not a slug', async () => {
    const response = await json(
      '/api/health-records/measurements',
      { measurements: [sampleBadKind()] },
      { headers: auth },
    )
    expect(response.status).toBe(422)
  })

  it('stores a night with its stage totals and names it for the morning', async () => {
    const created = await json('/api/health-records/sleep', sampleNight('hc-sleep-1'), {
      headers: auth,
    })
    expect(created.status).toBe(201)

    const again = await json('/api/health-records/sleep', sampleNight('hc-sleep-1'), {
      headers: auth,
    })
    expect(again.status).toBe(200) // updated, not created again

    const listed = await request('/api/health-records/sleep', { headers: { cookie } })
    const body = (await listed.json()) as {
      sleeps: {
        id: string
        localDate: string
        totalSeconds: number
        stages: Record<string, number | null>
        hypnogram: { stored: boolean }
      }[]
    }
    expect(body.sleeps).toHaveLength(1)

    const night = body.sleeps[0]!
    // Asleep at 23:10 on the 26th, awake at 07:05 on the 27th: it is the 27th's night.
    expect(night.localDate).toBe('2025-07-27')
    expect(night.totalSeconds).toBe(28_500)
    expect(night.stages['deep']).toBe(6_000)
    // A stage the source never reported stays absent rather than becoming a zero.
    expect(night.stages['outOfBed']).toBeNull()
    expect(night.hypnogram.stored).toBe(false)
  })

  it('round-trips a hypnogram through R2 and refuses another athlete', async () => {
    const created = await json('/api/health-records/sleep', sampleNight('hc-sleep-r2'), {
      headers: auth,
    })
    const { sleep } = (await created.json()) as {
      sleep: { id: string; stagesUploadPath: string }
    }
    expect(sleep.stagesUploadPath).toBe(`/api/health-records/sleep/${sleep.id}/stages`)

    const missing = await request(sleep.stagesUploadPath, { headers: { cookie } })
    expect(missing.status).toBe(404)

    const hypnogram = JSON.stringify([
      { stage: 'light', startTime: 1_753_567_800_000, endTime: 1_753_570_000_000 },
      { stage: 'deep', startTime: 1_753_570_000_000, endTime: 1_753_576_000_000 },
    ])
    const put = await request(sleep.stagesUploadPath, {
      method: 'PUT',
      headers: { ...auth, 'content-type': 'application/json' },
      body: hypnogram,
    })
    expect(put.status).toBe(204)

    const fetched = await request(sleep.stagesUploadPath, { headers: { cookie } })
    expect(fetched.status).toBe(200)
    expect(await fetched.text()).toBe(hypnogram)

    const detail = await request(`/api/health-records/sleep/${sleep.id}`, { headers: { cookie } })
    const body = (await detail.json()) as { sleep: { hypnogram: { stored: boolean } } }
    expect(body.sleep.hypnogram.stored).toBe(true)

    // A different athlete gets 404, not 403 — a 403 would confirm the id exists.
    const stranger = await newAthlete(uniqueEmail())
    const denied = await request(sleep.stagesUploadPath, { headers: { cookie: stranger.cookie } })
    expect(denied.status).toBe(404)
  })

  it('refuses an upload authenticated with a browser session', async () => {
    const response = await json('/api/health-records/sleep', sampleNight('hc-sleep-session'), {
      headers: { cookie },
    })
    expect(response.status).toBe(403)
  })

  it('never shows one athlete another athlete’s health data', async () => {
    await json(
      '/api/health-records/measurements',
      {
        measurements: [
          {
            sourceUid: 'hc-private',
            kind: 'weight',
            measuredAt: 1_753_600_000_000,
            value: 71.4,
            unit: 'kg',
          },
        ],
      },
      { headers: auth },
    )
    await json('/api/health-records/sleep', sampleNight('hc-private-night'), { headers: auth })

    const mallory = await newAthlete(uniqueEmail())
    const measurements = await request('/api/health-records/measurements', {
      headers: { cookie: mallory.cookie },
    })
    const sleeps = await request('/api/health-records/sleep', {
      headers: { cookie: mallory.cookie },
    })

    expect(
      ((await measurements.json()) as { measurements: unknown[] }).measurements,
    ).toHaveLength(0)
    expect(((await sleeps.json()) as { sleeps: unknown[] }).sleeps).toHaveLength(0)
  })

  it('removes health rows and hypnograms when the account is deleted', async () => {
    const created = await json('/api/health-records/sleep', sampleNight('hc-sleep-delete'), {
      headers: auth,
    })
    const { sleep } = (await created.json()) as { sleep: { id: string; stagesUploadPath: string } }
    await request(sleep.stagesUploadPath, {
      method: 'PUT',
      headers: { ...auth, 'content-type': 'application/json' },
      body: '[]',
    })

    const deleted = await request('/api/auth/me', { method: 'DELETE', headers: { cookie } })
    expect(deleted.status).toBe(204)

    // The batch is explicit rather than trusting ON DELETE CASCADE, which D1 only honours
    // when the connection has PRAGMA foreign_keys = ON.
    const rows = await bindings.DB.prepare(
      'SELECT COUNT(*) AS n FROM sleep_sessions WHERE id = ?',
    )
      .bind(sleep.id)
      .first<{ n: number }>()
    expect(rows?.n).toBe(0)

    const objects = await bindings.BLOBS.list({ prefix: 'u/' })
    expect(objects.objects.some((o) => o.key.includes(sleep.id))).toBe(false)
  })
})

/** A kind the Worker must refuse structurally, without knowing what kinds mean. */
const sampleBadKind = () => ({
  sourceUid: 'hc-bad-kind',
  kind: 'Resting Heart Rate',
  measuredAt: 1_753_600_000_000,
  value: 48,
  unit: 'bpm',
})
