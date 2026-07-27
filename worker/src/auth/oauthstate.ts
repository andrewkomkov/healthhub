/**
 * The short-lived state carried across an OAuth round trip.
 *
 * It rides in an HttpOnly cookie signed with the session pepper rather than living in KV or a
 * D1 row: the flow lasts seconds, the payload is tiny, and a signed cookie needs no storage,
 * no cleanup and no read on the callback path.
 */

const COOKIE = 'hh_oauth'
const MAX_AGE_SECONDS = 600

export interface OAuthState {
  state: string
  nonce: string
  /** 'web' finishes with a redirect to the SPA; 'device' finishes with a deep link. */
  mode: 'web' | 'device'
  deviceName?: string
  returnTo?: string
  issuedAt: number
}

const encoder = new TextEncoder()

function toBase64Url(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/')
  const binary = atob(padded.padEnd(Math.ceil(padded.length / 4) * 4, '='))
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return bytes
}

async function signingKey(pepper: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    'raw',
    encoder.encode(pepper || 'healthhub-unsalted'),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign', 'verify'],
  )
}

export async function sealState(state: OAuthState, pepper: string): Promise<string> {
  const payload = toBase64Url(encoder.encode(JSON.stringify(state)))
  const key = await signingKey(pepper)
  const signature = await crypto.subtle.sign('HMAC', key, encoder.encode(payload))
  return `${payload}.${toBase64Url(new Uint8Array(signature))}`
}

export async function openState(sealed: string, pepper: string): Promise<OAuthState | null> {
  const [payload, signature] = sealed.split('.')
  if (!payload || !signature) return null

  const key = await signingKey(pepper)
  const valid = await crypto.subtle.verify(
    'HMAC',
    key,
    fromBase64Url(signature) as BufferSource,
    encoder.encode(payload) as BufferSource,
  )
  if (!valid) return null

  const parsed = JSON.parse(new TextDecoder().decode(fromBase64Url(payload))) as OAuthState
  if (Date.now() - parsed.issuedAt > MAX_AGE_SECONDS * 1000) return null
  return parsed
}

export function stateCookie(sealed: string): string {
  // SameSite=Lax so the cookie survives the provider's top-level redirect back to us.
  return `${COOKIE}=${sealed}; Path=/api/auth; HttpOnly; Secure; SameSite=Lax; Max-Age=${MAX_AGE_SECONDS}`
}

export function clearedStateCookie(): string {
  return `${COOKIE}=; Path=/api/auth; HttpOnly; Secure; SameSite=Lax; Max-Age=0`
}

export const STATE_COOKIE = COOKIE
