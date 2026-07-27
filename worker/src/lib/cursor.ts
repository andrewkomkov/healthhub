import { fail } from './errors'

/**
 * Keyset pagination cursors.
 *
 * Every list on this API pages on `(timestamp DESC, id DESC)` rather than by offset, so a page
 * boundary stays stable while a sync inserts rows at the head. The cursor is opaque to clients
 * but is only `timestamp:id` — there is nothing in it worth signing, since it can address
 * nothing the caller's own query would not already reach.
 */

export function encodeCursor(ts: number, id: string): string {
  return btoa(`${ts}:${id}`).replace(/=+$/, '')
}

export function decodeCursor(cursor: string): { ts: number; id: string } {
  let decoded: string
  try {
    decoded = atob(cursor)
  } catch {
    fail('validation_failed', 'Malformed cursor.')
  }
  const sep = decoded.indexOf(':')
  const ts = Number(decoded.slice(0, sep))
  const id = decoded.slice(sep + 1)
  if (!Number.isFinite(ts) || !id) fail('validation_failed', 'Malformed cursor.')
  return { ts, id }
}
