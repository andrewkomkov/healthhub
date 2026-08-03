import { expect, test, type Page } from '@playwright/test'

/**
 * The web client, in a browser, against a real Worker.
 *
 * Everything else in this repository is a unit test or a device pass. This is the only thing
 * that opens the actual page — and the roadmap has carried "the new web surfaces have never
 * been seen in a browser under a signed-in account" as an open item since session 1.
 *
 * What it asserts is deliberately the seams, not the arithmetic: the arithmetic is pinned by
 * `analysis.test.ts` and `recovery.test.ts` against the phone's own figures, and re-checking it
 * through a browser would only test the browser. What a browser can tell you and nothing else
 * can is whether the page renders at all, whether the session survives a navigation, and
 * whether the navigation surface reaches the screens it claims to.
 */

/**
 * Every test signs up from its own address.
 *
 * The auth rate limiter allows ten sign-ups per IP per fifteen minutes, and a browser suite
 * comes from 127.0.0.1 every time — so the sixth test in a run is throttled and fails with a
 * screen that simply never became the feed. `worker/test` hit this first and solved it the same
 * way; the note is in AGENT-NOTES under "the auth rate limiter will throttle your test suite".
 *
 * The header is only trusted by the Worker running locally: in production Cloudflare sets
 * `cf-connecting-ip` itself and an inbound one is overwritten.
 */
let addresses = 0
test.beforeEach(async ({ context }) => {
  addresses += 1
  await context.setExtraHTTPHeaders({ 'cf-connecting-ip': `10.1.${addresses % 250}.7` })
})

/** A fresh athlete per run: the suite must never depend on what a previous one left behind. */
const athlete = () => ({
  email: `e2e-${Date.now()}-${Math.floor(Math.random() * 10_000)}@example.test`,
  password: 'correct-horse-battery-staple',
  displayName: 'E2E Athlete',
})

async function signUp(page: Page) {
  const who = athlete()
  await page.goto('/')

  await page.getByRole('button', { name: /create an account/i }).click()
  await page.getByLabel('Name').fill(who.displayName)
  await page.getByLabel('Email').fill(who.email)
  await page.getByLabel('Password').fill(who.password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()

  // The feed's app bar is the first thing that only exists behind a session. `level` and
  // `exact` both matter: "Activities" is also a substring of the empty state's "No activities
  // yet", and a loose locator resolves to two elements and fails in strict mode.
  await expect(page.getByRole('heading', { level: 1, name: 'Activities', exact: true }))
    .toBeVisible()
  return who
}

test.describe('the signed-in shell', () => {
  test('signs up and lands on the feed', async ({ page }) => {
    await signUp(page)

    // A new account has no workouts, and the empty state has to say so rather than look broken.
    await expect(page.getByRole('heading', { name: 'No activities yet' })).toBeVisible()
  })

  test('the navigation bar reaches every destination it offers', async ({ page }) => {
    await signUp(page)

    const nav = page.getByRole('navigation', { name: 'Main' })
    await expect(nav).toBeVisible()

    // The bar's own claim, checked against the screens: a destination that draws nothing is
    // exactly the failure the menu registry was built to make unrepresentable, and this is the
    // only test that can see it.
    for (const [label, heading] of [
      ['Health', 'Health'],
      ['Sources', 'Sources'],
      ['Archive', 'Archive'],
      ['Activities', 'Activities'],
    ] as const) {
      await nav.getByRole('button', { name: label }).click()
      await expect(page.getByRole('heading', { level: 1, name: heading, exact: true }))
        .toBeVisible()
    }
  })

  test('marks the destination you are on, for a screen reader as well as an eye', async ({
    page,
  }) => {
    await signUp(page)
    const nav = page.getByRole('navigation', { name: 'Main' })

    await expect(nav.getByRole('button', { name: 'Activities' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    await nav.getByRole('button', { name: 'Health' }).click()
    await expect(nav.getByRole('button', { name: 'Health' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    await expect(nav.getByRole('button', { name: 'Activities' })).not.toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  test('hides the bar on a screen below the top level', async ({ page }) => {
    await signUp(page)

    // Nothing to open on a fresh account, so this is checked the other way round: the bar is
    // present on every top-level screen, which is the claim `AppShell` makes.
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()
    await page.goto('/activities/does-not-exist')
    await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)
  })

  test('the session survives a reload', async ({ page }) => {
    await signUp(page)
    await page.reload()
    // Back on the feed rather than at the sign-in form: the cookie is the thing being tested.
    await expect(page.getByRole('heading', { level: 1, name: 'Activities', exact: true }))
      .toBeVisible()
  })

  test('signs out back to the sign-in form', async ({ page }) => {
    await signUp(page)
    await page.getByRole('button', { name: 'Sign out' }).click()
    // The sign-in form, not the Auth0 button: `/api/auth/providers` decides which methods the
    // screen offers, and a deployment without Auth0 configured correctly renders none. Testing
    // for it would have asserted this deployment's configuration rather than the sign-out.
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: 'HealthHub' })).toBeVisible()
  })
})
