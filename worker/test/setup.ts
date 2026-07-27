import { applyD1Migrations, env } from 'cloudflare:test'
import { beforeAll } from 'vitest'
import type { Bindings } from '../src/types'

/** What the pool injects on top of the Worker's own bindings. */
type TestEnv = Bindings & { TEST_MIGRATIONS: Parameters<typeof applyD1Migrations>[1] }

beforeAll(async () => {
  // The migrations are read from disk by vitest.config.ts and handed over as a binding, so
  // the suites run against exactly the schema that ships.
  const testEnv = env as unknown as TestEnv
  await applyD1Migrations(testEnv.DB, testEnv.TEST_MIGRATIONS)
})
