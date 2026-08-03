import { defineConfig, devices } from '@playwright/test'

/**
 * The end-to-end run. `npm run test:e2e` has existed since the first commit with nothing to run.
 *
 * It boots the real Worker and the real Vite dev server rather than mocking the API, because
 * what these tests are for is the seam the unit tests cannot reach: the SPA and the API behaving
 * as one origin, a session cookie surviving a navigation, and a screen rendering what the
 * database actually returned. A mocked API would pass with the Worker switched off.
 *
 * Two things are deliberate about the servers:
 *
 * - **`reuseExistingServer` is off in CI and on locally.** A developer with `npm run dev`
 *   already running should not have two Workers fighting over port 8787, and CI should never
 *   silently test against something it did not start;
 * - **the Worker runs against local D1 and R2**, which `wrangler dev` gives for free. The suite
 *   creates its own athlete per run, so it never touches a deployment and never needs a secret.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  // One worker: the tests share a local D1 file, and parallel sign-ups would race the auth
  // rate limiter — ten per IP per fifteen minutes, and every one of them comes from 127.0.0.1.
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['list']] : [['list']],
  timeout: 60_000,

  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  webServer: [
    {
      command: 'npx wrangler dev --port 8787',
      cwd: '../worker',
      url: 'http://127.0.0.1:8787/api/auth/providers',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
    {
      // `--host 127.0.0.1` is load-bearing: Vite otherwise binds `[::1]` only, Playwright polls
      // the IPv4 `baseURL`, and the readiness check times out after two minutes with nothing in
      // the log to say why — the server is up, on an address nobody is asking about.
      command: 'npx vite --port 5173 --strictPort --host 127.0.0.1',
      url: 'http://127.0.0.1:5173',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
  ],
})
