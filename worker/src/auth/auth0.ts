/**
 * Auth0 sign-in over the OIDC authorization code flow.
 *
 * The client secret lives in a Worker secret and never reaches a browser or a phone, which is
 * why this is the confidential-client code flow rather than PKCE in the SPA.
 *
 * The provider is **optional**. HealthHub is meant to be cloned and self-hosted, so requiring
 * everyone who deploys it to first register an Auth0 tenant would be a poor trade. When the
 * three secrets are absent the routes disappear and local password accounts are the only way
 * in; when they are present, Auth0 becomes the recommended path.
 */

import type { Bindings } from '../types'
import { fail } from '../lib/errors'

export interface Auth0Config {
  domain: string
  clientId: string
  clientSecret: string
}

export function auth0Config(env: Bindings): Auth0Config | null {
  const { AUTH0_DOMAIN, AUTH0_CLIENT_ID, AUTH0_CLIENT_SECRET } = env
  if (!AUTH0_DOMAIN || !AUTH0_CLIENT_ID || !AUTH0_CLIENT_SECRET) return null
  return { domain: AUTH0_DOMAIN, clientId: AUTH0_CLIENT_ID, clientSecret: AUTH0_CLIENT_SECRET }
}

export function redirectUri(request: Request): string {
  return `${new URL(request.url).origin}/api/auth/auth0/callback`
}

export function authorizeUrl(
  config: Auth0Config,
  params: { state: string; nonce: string; redirectUri: string },
): string {
  const url = new URL(`https://${config.domain}/authorize`)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('client_id', config.clientId)
  url.searchParams.set('redirect_uri', params.redirectUri)
  url.searchParams.set('scope', 'openid profile email')
  url.searchParams.set('state', params.state)
  url.searchParams.set('nonce', params.nonce)
  return url.toString()
}

interface TokenResponse {
  id_token?: string
  access_token?: string
  error?: string
  error_description?: string
}

export async function exchangeCode(
  config: Auth0Config,
  code: string,
  callbackUri: string,
): Promise<string> {
  const response = await fetch(`https://${config.domain}/oauth/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      grant_type: 'authorization_code',
      client_id: config.clientId,
      client_secret: config.clientSecret,
      code,
      redirect_uri: callbackUri,
    }),
  })

  const body = (await response.json().catch(() => null)) as TokenResponse | null
  if (!response.ok || !body?.id_token) {
    console.error('auth0 token exchange failed', {
      status: response.status,
      error: body?.error,
      description: body?.error_description,
    })
    fail('unauthenticated', 'Sign-in with Auth0 failed.')
  }
  return body.id_token
}

/* ---------------------------------------------------------------- id_token */

export interface IdTokenClaims {
  sub: string
  email?: string
  email_verified?: boolean
  name?: string
  nickname?: string
  nonce?: string
  iss: string
  aud: string | string[]
  exp: number
  iat: number
}

function base64UrlDecode(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/')
  const binary = atob(padded.padEnd(Math.ceil(padded.length / 4) * 4, '='))
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return bytes
}

interface Jwk {
  kid: string
  kty: string
  alg?: string
  use?: string
  n: string
  e: string
}

// A Worker isolate is short-lived, so this cache is opportunistic rather than a real store.
const jwksCache = new Map<string, { keys: Jwk[]; fetchedAt: number }>()
const JWKS_TTL_MS = 10 * 60 * 1000

async function fetchJwks(domain: string): Promise<Jwk[]> {
  const cached = jwksCache.get(domain)
  if (cached && Date.now() - cached.fetchedAt < JWKS_TTL_MS) return cached.keys

  const response = await fetch(`https://${domain}/.well-known/jwks.json`)
  if (!response.ok) fail('unauthenticated', 'Could not reach the identity provider.')
  const body = (await response.json()) as { keys: Jwk[] }
  jwksCache.set(domain, { keys: body.keys, fetchedAt: Date.now() })
  return body.keys
}

/**
 * Verifies the id_token: RS256 signature against the tenant's JWKS, then issuer, audience,
 * expiry and nonce.
 *
 * The token arrived over a back-channel TLS call, which OIDC says makes signature checking
 * optional — but the cost here is one cached fetch and a WebCrypto verify, and skipping it
 * would mean the only thing standing between an attacker and an account is that they could
 * not reach the token endpoint.
 */
export async function verifyIdToken(
  config: Auth0Config,
  token: string,
  expectedNonce: string,
): Promise<IdTokenClaims> {
  const parts = token.split('.')
  if (parts.length !== 3) fail('unauthenticated', 'Malformed identity token.')

  const [headerB64, payloadB64, signatureB64] = parts as [string, string, string]
  const header = JSON.parse(new TextDecoder().decode(base64UrlDecode(headerB64))) as {
    alg: string
    kid?: string
  }
  if (header.alg !== 'RS256') fail('unauthenticated', 'Unsupported token algorithm.')

  const keys = await fetchJwks(config.domain)
  const jwk = keys.find((k) => k.kid === header.kid) ?? keys[0]
  if (!jwk) fail('unauthenticated', 'Identity provider key not found.')

  const key = await crypto.subtle.importKey(
    'jwk',
    { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: 'RS256', ext: true },
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['verify'],
  )

  const signed = new TextEncoder().encode(`${headerB64}.${payloadB64}`)
  const valid = await crypto.subtle.verify(
    'RSASSA-PKCS1-v1_5',
    key,
    base64UrlDecode(signatureB64) as BufferSource,
    signed as BufferSource,
  )
  if (!valid) fail('unauthenticated', 'Identity token signature is invalid.')

  const claims = JSON.parse(
    new TextDecoder().decode(base64UrlDecode(payloadB64)),
  ) as IdTokenClaims

  const now = Math.floor(Date.now() / 1000)
  const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud]

  if (claims.iss !== `https://${config.domain}/`) {
    fail('unauthenticated', 'Identity token issuer mismatch.')
  }
  if (!audiences.includes(config.clientId)) {
    fail('unauthenticated', 'Identity token audience mismatch.')
  }
  if (claims.exp <= now) fail('unauthenticated', 'Identity token has expired.')
  // The nonce is what ties this token to the sign-in we started, defeating replay.
  if (claims.nonce !== expectedNonce) fail('unauthenticated', 'Identity token nonce mismatch.')

  return claims
}
