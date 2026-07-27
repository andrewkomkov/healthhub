import { createExecutionContext, env, waitOnExecutionContext } from 'cloudflare:test'
import { beforeEach, describe, expect, it } from 'vitest'
import worker from '../src/index'

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

    const all = await request('/api/activities?includeDuplicates=1', { headers: { cookie } })
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
