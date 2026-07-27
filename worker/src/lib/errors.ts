import type { Context } from 'hono'
import { HTTPException } from 'hono/http-exception'

export type ErrorCode =
  | 'unauthenticated'
  | 'forbidden'
  | 'not_found'
  | 'conflict'
  | 'validation_failed'
  | 'rate_limited'
  | 'internal'

const STATUS: Record<ErrorCode, number> = {
  unauthenticated: 401,
  forbidden: 403,
  not_found: 404,
  conflict: 409,
  validation_failed: 422,
  rate_limited: 429,
  internal: 500,
}

/**
 * Throw the documented error envelope. `headers` carries things like Retry-After.
 *
 * Note that resource routes deliberately raise `not_found` rather than `forbidden` when the
 * caller does not own the resource — a 403 would confirm that the identifier exists, which
 * would let an attacker enumerate other athletes' activities.
 */
export function fail(code: ErrorCode, message: string, headers?: Record<string, string>): never {
  throw new HTTPException(STATUS[code] as 401, {
    res: new Response(JSON.stringify({ error: { code, message } }), {
      status: STATUS[code],
      headers: { 'content-type': 'application/json', ...headers },
    }),
  })
}

/** Terminal error handler: known envelopes pass through, anything else becomes a 500. */
export function onError(err: Error, c: Context): Response {
  if (err instanceof HTTPException && err.res) return err.res

  console.error('unhandled', {
    requestId: c.get('requestId'),
    message: err.message,
    stack: err.stack,
  })

  return c.json(
    { error: { code: 'internal' satisfies ErrorCode, message: 'Something went wrong.' } },
    500,
  )
}

export function notFound(c: Context): Response {
  return c.json(
    { error: { code: 'not_found' satisfies ErrorCode, message: 'No such endpoint.' } },
    404,
  )
}
