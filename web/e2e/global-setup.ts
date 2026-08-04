import { execFileSync } from 'node:child_process'
import { dirname, join } from 'node:path'

/**
 * Applies the D1 migrations to the *local* database the run's Worker will open.
 *
 * `wrangler dev` creates an empty SQLite file if there is none, so a fresh checkout answers
 * every request with `D1_ERROR: no such table: rate_limits` — and the symptom is eleven browser
 * tests failing on a sign-up that returned 500, which reads like a broken app rather than a
 * missing schema. It passed on a developer's machine only because their local database had been
 * migrated by hand at some point, which is exactly the kind of state a suite must not depend on.
 *
 * Here rather than as a workflow step so that `npm run test:e2e` behaves identically wherever
 * it is run: a green suite on a laptop and a red one in CI is worth less than no suite at all.
 */
export default function globalSetup(): void {
  const worker = join(dirname(new URL(import.meta.url).pathname), '..', '..', 'worker')

  execFileSync(
    'npx',
    ['wrangler', 'd1', 'migrations', 'apply', 'healthhub', '--local'],
    { cwd: worker, stdio: 'inherit' },
  )
}
