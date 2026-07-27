import { cloudflareTest, readD1Migrations } from '@cloudflare/vitest-pool-workers'
import path from 'node:path'
import { defineConfig } from 'vitest/config'

/*
 * Tests run against real D1 and R2 inside workerd rather than against mocks — what is being
 * tested is the SQL, the ownership checks and the bindings, and a mock would only assert
 * that the mock works.
 *
 * Note the shape: in @cloudflare/vitest-pool-workers 0.18 (for Vitest 4) the integration is
 * a Vite plugin, `cloudflareTest`. The older `defineWorkersConfig` helper from
 * `@cloudflare/vitest-pool-workers/config` no longer exists — that subpath is not exported.
 */
const migrations = await readD1Migrations(path.join(import.meta.dirname, 'migrations'))

export default defineConfig({
  plugins: [
    cloudflareTest({
      singleWorker: true,
      wrangler: { configPath: './wrangler.jsonc' },
      miniflare: {
        // Read by the setup file, which applies them before the suites run.
        bindings: { TEST_MIGRATIONS: migrations, SESSION_PEPPER: 'test-pepper' },
      },
    }),
  ],
  test: {
    setupFiles: ['./test/setup.ts'],
  },
})
