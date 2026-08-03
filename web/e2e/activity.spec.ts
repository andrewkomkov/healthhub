import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

/**
 * The activity detail screen, with a workout in it, in a browser.
 *
 * `shell.spec.ts` proves the app opens and the navigation reaches every screen, but it signs up
 * a fresh athlete and a fresh athlete has nothing — so the screen the whole product exists for
 * has still never been rendered outside a unit test. This seeds one workout through the API the
 * phone uses and then opens it the way a person would: from the feed, by clicking the card.
 *
 * Seeded through the **real upload route** rather than by writing to D1, because the shape of
 * that request is part of what is being tested: the Worker stores what the phone computed and
 * recomputes nothing, so a screen showing the wrong figure would mean the contract drifted.
 */

const WORKOUT = {
  sourceUid: 'e2e-ride-1',
  sport: 'cycling',
  title: 'Evening Ride',
  startTime: 1_753_600_000_000,
  endTime: 1_753_618_000_000,
  tzOffsetMinutes: 180,
  elapsedSeconds: 18_000,
  movingSeconds: 16_200,
  distanceM: 92_310.4,
  elevationGainM: 512,
  avgSpeedMps: 5.7,
  maxSpeedMps: 12.4,
  avgHrBpm: 138,
  maxHrBpm: 176,
  caloriesKcal: 1712,
  hasGps: true,
  // A real encoded polyline: the map draws from this, and a malformed one would render an
  // empty container that looks exactly like a broken map.
  routePolyline: '_p~iF~ps|U_ulLnnqC_mqNvxq`@',
  // Matches `fixtures/hht/golden-v1.hht`, which is uploaded as this workout's telemetry: the
  // screen decides whether to ask for it from these two fields.
  sampleCount: 12,
  channels: ['t', 'lat', 'lon', 'elevation', 'hr', 'speed', 'cadence', 'power'],
  splits: [
    { unit: 'km', idx: 0, distanceM: 1000, elapsedSeconds: 186.2, avgHrBpm: 131 },
    { unit: 'km', idx: 1, distanceM: 1000, elapsedSeconds: 174.9, avgHrBpm: 141 },
  ],
  zones: [{ kind: 'hr', zoneIndex: 1, lowerBound: 0, upperBound: 120, seconds: 1840 }],
}

/**
 * The codec's own golden file, the one the Kotlin writer and the TypeScript reader are both
 * pinned to. Using it here rather than generating bytes means this test cannot pass against a
 * reader that has drifted from the writer — and it carries every channel, so the map and all
 * five chart panels have something to draw.
 */
const GOLDEN_HHT = readFileSync(
  join(dirname(new URL(import.meta.url).pathname), '..', '..', 'fixtures', 'hht', 'golden-v1.hht'),
)

let addresses = 0

async function signUpWithWorkout(page: Page, request: APIRequestContext) {
  addresses += 1
  // Ten sign-ups per IP per fifteen minutes, and every test comes from 127.0.0.1. Same trap,
  // same fix as `worker/test` — see AGENT-NOTES.
  const ip = { 'cf-connecting-ip': `10.2.${addresses % 250}.7` }
  const email = `e2e-detail-${Date.now()}-${addresses}@example.test`

  const registered = await request.post('http://127.0.0.1:8787/api/auth/register', {
    headers: ip,
    data: { email, password: 'correct-horse-battery-staple', displayName: 'E2E Athlete' },
  })
  expect(registered.ok()).toBeTruthy()
  const cookie = registered.headers()['set-cookie']!.split(';')[0]!

  // The phone's own path: a session buys a device token, and the token uploads.
  const device = await request.post('http://127.0.0.1:8787/api/devices', {
    headers: { cookie },
    data: { name: 'E2E Pixel', platform: 'android' },
  })
  const { token } = (await device.json()) as { token: string }

  const uploaded = await request.post('http://127.0.0.1:8787/api/activities', {
    headers: { authorization: `Bearer ${token}` },
    data: WORKOUT,
  })
  expect(uploaded.status()).toBe(201)
  const { activity } = (await uploaded.json()) as { activity: { id: string } }

  // The second step of the same two-step the phone takes: the summary as JSON, the samples as
  // bytes. Uploaded uncompressed — the route accepts either, and gzipping here would only test
  // the test's own compressor.
  const telemetry = await request.put(
    `http://127.0.0.1:8787/api/activities/${activity.id}/telemetry?variant=full`,
    {
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': 'application/octet-stream',
      },
      data: GOLDEN_HHT,
    },
  )
  expect(telemetry.ok()).toBeTruthy()

  // Hand the browser the session the API just issued, so the page opens signed in.
  const [name, value] = cookie.split('=')
  await page.context().addCookies([
    { name: name!, value: value!, domain: '127.0.0.1', path: '/' },
  ])
  await page.context().setExtraHTTPHeaders(ip)
}

test.describe('the activity detail screen', () => {
  test('opens a workout from the feed and draws what the phone computed', async ({
    page,
    request,
  }) => {
    await signUpWithWorkout(page, request)
    await page.goto('/')

    // The card, with the figure leading rather than the label — the hierarchy both clients use.
    const card = page.getByRole('button', { name: /Evening Ride/ })
    await expect(card).toBeVisible()
    await expect(card).toContainText('92.31 km')

    await card.click()

    // Every figure below is stored, not derived: the Worker recomputes nothing (Principle I),
    // so these assertions are the contract between the phone and the browser.
    const summary = page.getByRole('region', { name: 'Summary' })
    await expect(summary).toContainText('92.31 km')
    await expect(summary).toContainText('4:30:00') // 16,200 s of moving time
    await expect(summary).toContainText('5:00:00') // 18,000 s elapsed
    await expect(summary).toContainText('20.5 km/h') // cycling reads as speed, not as pace
    await expect(summary).toContainText('512 m')
    await expect(summary).toContainText('138 bpm')
    await expect(summary).toContainText('1712 kcal')
  })

  test('shows the splits the phone computed, and the zones', async ({ page, request }) => {
    await signUpWithWorkout(page, request)
    await page.goto('/')
    await page.getByRole('button', { name: /Evening Ride/ }).click()

    const table = page.getByRole('table')
    await expect(table).toBeVisible()
    // Two kilometre splits, stored verbatim. The browser never recomputes one.
    await expect(table.getByRole('row')).toHaveCount(3) // header plus two splits
  })

  test('decodes the telemetry and draws a chart for every channel recorded', async ({
    page,
    request,
  }) => {
    await signUpWithWorkout(page, request)
    await page.goto('/')
    await page.getByRole('button', { name: /Evening Ride/ }).click()

    const telemetry = page.getByRole('region', { name: 'Telemetry' })
    await expect(telemetry).toBeVisible()

    // The axis switch only offers Distance when there is a distance axis to switch to, which
    // means the codec produced positions and `cumulativeDistance` accepted them.
    const axis = page.getByRole('group', { name: 'Chart axis' })
    await expect(axis.getByRole('button', { name: 'Time' })).toBeVisible()
    await expect(axis.getByRole('button', { name: 'Distance' })).toBeEnabled()

    // Twelve samples is fewer than the chart has columns, and that used to draw nothing at all
    // — the defect `ChartSeriesTest` pinned on the phone. This is the browser half of it.
    for (const channel of ['Elevation', 'Speed', 'Heart rate', 'Cadence', 'Power']) {
      await expect(telemetry.getByRole('button', { name: channel })).toBeVisible()
    }
    await expect(page.getByRole('heading', { name: 'No per-second data for this workout' }))
      .toHaveCount(0)
  })

  test('draws the route rather than the empty state', async ({ page, request }) => {
    await signUpWithWorkout(page, request)
    await page.goto('/')
    await page.getByRole('button', { name: /Evening Ride/ }).click()

    // The map itself is a WebGL canvas and asserting pixels in a headless GL context buys
    // nothing; what matters is which of the two branches the screen chose. `Route.geometry`
    // decides that from the decoded positions, and choosing wrong is the failure that once
    // showed neither a map nor the card explaining its absence — a gap between two cards.
    await expect(page.getByRole('region', { name: 'Route' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'No route to draw' })).toHaveCount(0)
  })

  test('draws no navigation bar over a detail screen', async ({ page, request }) => {
    await signUpWithWorkout(page, request)
    await page.goto('/')
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()

    await page.getByRole('button', { name: /Evening Ride/ }).click()
    // Below the top level the screen's own back control is the way out, and four sideways
    // moves over the thing the reader came for are chrome.
    await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)
  })
})
