import { Hono } from 'hono'
import type { AppEnv } from './types'
import { notFound, onError } from './lib/errors'
import { authRoutes } from './routes/auth'
import { activityRoutes } from './routes/activities'
import { deviceRoutes } from './routes/devices'
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

app.notFound(notFound)
app.onError(onError)

export default app
