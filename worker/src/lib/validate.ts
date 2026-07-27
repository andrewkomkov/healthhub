import { fail } from './errors'

/**
 * Small hand-written validators. The Worker validates structure only — it never recomputes
 * or cross-checks derived values, because analysis belongs on the athlete's device
 * (Constitution Principle I).
 */

type Json = Record<string, unknown>

export async function jsonBody(request: Request): Promise<Json> {
  let parsed: unknown
  try {
    parsed = await request.json()
  } catch {
    fail('validation_failed', 'Body must be valid JSON.')
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    fail('validation_failed', 'Body must be a JSON object.')
  }
  return parsed as Json
}

export function str(body: Json, key: string, opts: { max?: number; min?: number } = {}): string {
  const value = body[key]
  if (typeof value !== 'string') fail('validation_failed', `"${key}" must be a string.`)
  const { min = 1, max = 512 } = opts
  if (value.length < min) fail('validation_failed', `"${key}" must be at least ${min} characters.`)
  if (value.length > max) fail('validation_failed', `"${key}" must be at most ${max} characters.`)
  return value
}

export function optionalStr(
  body: Json,
  key: string,
  opts: { max?: number } = {},
): string | null {
  const value = body[key]
  if (value === undefined || value === null) return null
  return str(body, key, { min: 0, ...opts })
}

export function num(body: Json, key: string): number {
  const value = body[key]
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    fail('validation_failed', `"${key}" must be a finite number.`)
  }
  return value
}

export function optionalNum(body: Json, key: string): number | null {
  const value = body[key]
  if (value === undefined || value === null) return null
  return num(body, key)
}

export function int(body: Json, key: string): number {
  const value = num(body, key)
  if (!Number.isInteger(value)) fail('validation_failed', `"${key}" must be an integer.`)
  return value
}

export function optionalInt(body: Json, key: string): number | null {
  const value = body[key]
  if (value === undefined || value === null) return null
  return int(body, key)
}

export function bool(body: Json, key: string, fallback = false): boolean {
  const value = body[key]
  if (value === undefined || value === null) return fallback
  if (typeof value !== 'boolean') fail('validation_failed', `"${key}" must be a boolean.`)
  return value
}

export function arr(body: Json, key: string, max = 10_000): unknown[] {
  const value = body[key]
  if (value === undefined || value === null) return []
  if (!Array.isArray(value)) fail('validation_failed', `"${key}" must be an array.`)
  if (value.length > max) fail('validation_failed', `"${key}" may hold at most ${max} entries.`)
  return value
}

export function oneOf<T extends string>(
  body: Json,
  key: string,
  allowed: readonly T[],
  fallback?: T,
): T {
  const value = body[key]
  if ((value === undefined || value === null) && fallback !== undefined) return fallback
  if (typeof value !== 'string' || !allowed.includes(value as T)) {
    fail('validation_failed', `"${key}" must be one of: ${allowed.join(', ')}.`)
  }
  return value as T
}

/** Cheap structural email check — delivery is the real validator, not a regex. */
export function email(body: Json, key = 'email'): string {
  const value = str(body, key, { max: 320 })
  if (!/^[^@\s]+@[^@\s.]+\.[^@\s]+$/.test(value)) {
    fail('validation_failed', 'That does not look like an email address.')
  }
  return value
}

export function normalizeEmail(value: string): string {
  return value.trim().toLowerCase()
}
