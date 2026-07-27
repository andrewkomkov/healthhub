import { Hono } from 'hono'
import type { AppEnv } from '../types'
import { fail } from '../lib/errors'
import { authenticate, ownedActivity, requireDevice } from '../lib/guard'
import { telemetryKey, type TelemetryVariant } from '../lib/keys'

/**
 * Telemetry transfer.
 *
 * Both directions are pure byte routing: the Worker checks ownership and streams. It never
 * decodes a .hht object, and there is deliberately no code path here that could — analysis
 * belongs on the athlete's device (Constitution Principle I).
 *
 * R2 is reached through this proxy rather than through presigned URLs, so that authorisation
 * and delivery stay in the same place and a revoked device loses access immediately (R-004).
 */

function variantOf(raw: string | undefined): TelemetryVariant {
  if (raw === 'full' || raw === undefined) return 'full'
  if (raw === 'preview') return 'preview'
  fail('validation_failed', '"variant" must be "full" or "preview".')
}

interface ActivityKeyRow {
  start_time: number
  tz_offset_minutes: number
  telemetry_key: string | null
  preview_key: string | null
}

export const telemetryRoutes = new Hono<AppEnv>()

  .use('*', authenticate)

  .put('/:id/telemetry', requireDevice, async (c) => {
    const principal = c.get('principal')
    const id = c.req.param('id')
    const variant = variantOf(c.req.query('variant'))

    const row = await ownedActivity<ActivityKeyRow>(
      c.env.DB,
      principal.userId,
      id,
      'start_time, tz_offset_minutes, telemetry_key, preview_key',
    )

    if (!c.req.raw.body) fail('validation_failed', 'Body must be the .hht object.')

    const key = telemetryKey(principal.userId, id, row.start_time, row.tz_offset_minutes, variant)

    // The phone sends the object gzipped; store it that way so it can be served back
    // with Content-Encoding: gzip and decompressed natively by the browser.
    const object = await c.env.BLOBS.put(key, c.req.raw.body, {
      httpMetadata: {
        contentType: 'application/octet-stream',
        contentEncoding: c.req.header('content-encoding') ?? undefined,
      },
    })

    const column = variant === 'full' ? 'telemetry_key' : 'preview_key'
    if (variant === 'full') {
      await c.env.DB.prepare(
        `UPDATE activities SET telemetry_key = ?, telemetry_bytes = ?, updated_at = ? WHERE id = ?`,
      )
        .bind(key, object?.size ?? null, Date.now(), id)
        .run()
    } else {
      await c.env.DB.prepare(`UPDATE activities SET ${column} = ?, updated_at = ? WHERE id = ?`)
        .bind(key, Date.now(), id)
        .run()
    }

    return c.body(null, 204)
  })

  .get('/:id/telemetry', async (c) => {
    const principal = c.get('principal')
    const id = c.req.param('id')
    const variant = variantOf(c.req.query('variant'))

    const row = await ownedActivity<ActivityKeyRow>(
      c.env.DB,
      principal.userId,
      id,
      'start_time, tz_offset_minutes, telemetry_key, preview_key',
    )

    const stored = variant === 'full' ? row.telemetry_key : row.preview_key
    // A missing variant is "not synced yet", not an error the client should surface loudly.
    if (!stored) fail('not_found', `No ${variant} telemetry for this activity yet.`)

    // Range requests are honoured so a large object can be resumed mid-download.
    const range = c.req.header('range')
    const object = await c.env.BLOBS.get(stored, {
      range: range ? c.req.raw.headers : undefined,
      onlyIf: c.req.raw.headers,
    })

    if (!object) fail('not_found', 'Telemetry object is missing.')
    if (!('body' in object) || object.body === null) {
      // onlyIf matched: the client's cached copy is still current.
      return c.body(null, 304)
    }

    const headers = new Headers()
    object.writeHttpMetadata(headers)
    headers.set('etag', object.httpEtag)
    // Telemetry used to be immutable, and this header said so for a year at a time. It is not
    // any more: importing a GPS route re-ingests the workout and rewrites both objects at the
    // same key (R-015), and a client holding an immutable copy would never see the route it
    // just imported, with nothing anywhere to say why. Revalidation is cheap — the conditional
    // request above answers 304 with no body and no R2 egress when nothing has changed — and
    // it is the same bargain the hypnogram already makes for the same reason.
    headers.set('cache-control', 'private, max-age=0, must-revalidate')
    headers.set('content-type', 'application/octet-stream')

    return new Response(object.body, { status: range ? 206 : 200, headers })
  })
