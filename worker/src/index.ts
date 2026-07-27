import { Hono } from 'hono'
import type { AppEnv, Bindings } from './types'
import { compactArchive } from './archive/compaction'
import { notFound, onError } from './lib/errors'
import { archiveRoutes } from './routes/archive'
import { authRoutes } from './routes/auth'
import { activityRoutes } from './routes/activities'
import { deviceRoutes } from './routes/devices'
import { healthRecordRoutes } from './routes/health-records'
import { sourceRoutes } from './routes/sources'
import { syncRoutes } from './routes/sync'
import { telemetryRoutes } from './routes/telemetry'
import { themeRoutes } from './routes/theme'

/**
 * The entire server side of HealthHub.
 *
 * This Worker does three things: it authenticates, it enforces that an athlete only ever
 * touches their own data, and it moves bytes between the clients, D1 and R2. It deliberately
 * contains no analysis — no aggregation, no windowing, no per-sample transformation. Those
 * live on the phone and in the browser (Constitution Principle I), and a pull request that
 * adds them here is a defect regardless of how convenient it looks.
 *
 * The web SPA is served by this same script through the `assets` binding, so there is one
 * deployment and one origin.
 */
const app = new Hono<AppEnv>()

app.use('*', async (c, next) => {
  c.set('requestId', crypto.randomUUID())
  await next()
  c.header('x-request-id', c.get('requestId'))
})

// Defence in depth for the SPA and any API response rendered by a browser.
app.use('/api/*', async (c, next) => {
  await next()
  c.header('x-content-type-options', 'nosniff')
  c.header('referrer-policy', 'same-origin')
})

app.get('/api/health', (c) => c.json({ ok: true }))

app.route('/api/auth', authRoutes)
app.route('/api/devices', deviceRoutes)
app.route('/api/activities', activityRoutes)
app.route('/api/activities', telemetryRoutes)
app.route('/api/sync', syncRoutes)
app.route('/api/sources', sourceRoutes)
app.route('/api/theme', themeRoutes)
app.route('/api/health-records', healthRecordRoutes)
app.route('/api/archive', archiveRoutes)

app.notFound(notFound)
app.onError(onError)

/**
 * Rolls closed months into the analytical Parquet tier (R-013).
 *
 * Runs daily rather than monthly on purpose: a backfill can land activities in a month that
 * was already compacted, and re-running a month replaces its parts atomically, so a job that
 * mostly finds nothing to do is cheaper than a month-long window in which the archive is
 * wrong. Compaction is file assembly — it concatenates rows the phone already computed and
 * never aggregates, which is what keeps Principle I intact with a query engine in the picture.
 *
 * A run that finds nothing logs one line and costs one query; the interesting case is the
 * report, which is the only record of what the job did — nobody is watching at 04:00 UTC.
 */
async function scheduled(
  controller: ScheduledController,
  env: Bindings,
  _ctx: ExecutionContext,
): Promise<void> {
  const report = await compactArchive(env, { now: controller.scheduledTime })
  console.log('archive compaction', {
    cron: controller.cron,
    durationMs: report.finishedAt - report.startedAt,
    candidates: report.candidates,
    unchanged: report.unchanged,
    deferred: report.deferred,
    compacted: report.compacted,
    emptied: report.emptied,
    failures: report.failures,
  })
}

export default { fetch: app.fetch, scheduled }
