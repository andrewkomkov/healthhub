/**
 * Opaque tokens, stored as hashes.
 *
 * Web sessions and device authorisations are both random 32-byte tokens. Only their SHA-256
 * is ever written to D1, so a database leak does not hand over live credentials. Statelessness
 * was rejected because device tokens must be individually revocable (FR-028) — see R-006.
 */

const TOKEN_BYTES = 32

export const SESSION_COOKIE = 'hh_session'
export const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000

const encoder = new TextEncoder()

function toBase64Url(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function generateToken(): string {
  return toBase64Url(crypto.getRandomValues(new Uint8Array(TOKEN_BYTES)))
}

/**
 * The pepper is a Worker secret, so a stolen D1 snapshot alone cannot be used to
 * pre-compute token hashes.
 */
export async function hashToken(token: string, pepper = ''): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(`${pepper}:${token}`))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

export function sessionCookie(token: string, maxAgeMs = SESSION_TTL_MS): string {
  const attrs = [
    `${SESSION_COOKIE}=${token}`,
    'Path=/',
    'HttpOnly',
    'Secure',
    'SameSite=Lax',
    `Max-Age=${Math.floor(maxAgeMs / 1000)}`,
  ]
  return attrs.join('; ')
}

export function clearedSessionCookie(): string {
  return `${SESSION_COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0`
}

export function readCookie(header: string | null | undefined, name: string): string | null {
  if (!header) return null
  for (const part of header.split(';')) {
    const [key, ...rest] = part.trim().split('=')
    if (key === name) return rest.join('=') || null
  }
  return null
}

export function bearerToken(header: string | null | undefined): string | null {
  if (!header) return null
  const match = /^Bearer\s+(.+)$/i.exec(header.trim())
  return match ? (match[1] as string) : null
}
