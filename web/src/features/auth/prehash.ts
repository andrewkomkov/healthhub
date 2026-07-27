/**
 * The browser's half of the password KDF (research.md R-006 amendment).
 *
 * The Workers runtime refuses PBKDF2 above 100,000 iterations, so the work that makes a
 * stolen password database expensive to attack cannot all happen at the edge. It happens
 * here instead: the browser derives 600,000 iterations' worth and sends the result, and the
 * Worker salts and hashes *that* at the only count it is allowed. Neither half is sufficient
 * alone, which is why this file and `worker/src/auth/password.ts` change together.
 *
 * The same derivation exists in Kotlin — `core:network/PasswordProofs.kt`. Both are pinned to
 * `fixtures/auth/prehash-v1.json`; if they disagree, an athlete who signed up on their phone
 * cannot sign in in a browser, and nothing else would say so.
 */

import type { PasswordProof } from '../../core/api/client'

/**
 * The scheme name is the parameters, spelled out. Changing an iteration count or the salt
 * derivation means a new name — a record stores the name it was built from, and a proof under
 * a name the record does not carry proves nothing.
 */
export const PASSWORD_SCHEME = 'pbkdf2-sha256/600000/v1'

const ITERATIONS = 600_000
const KEY_LENGTH_BITS = 256

/**
 * The salt is derived from the address rather than random, because login has to reproduce it
 * before the server has said anything — asking the server for an account's salt is an account
 * enumeration oracle. Deriving it from the address still keeps two athletes who chose the same
 * password from producing the same proof, and the Worker adds a random per-user salt of its
 * own on top.
 *
 * The consequence to remember: an account's email is part of its password derivation, so
 * changing an address would invalidate the proof. `PATCH /api/auth/me` does not accept one.
 */
const SALT_PREFIX = 'healthhub/password/v1\n'

const encoder = new TextEncoder()

/** Must match `normalizeEmail` in the Worker and `PasswordProofs.normalizeEmail` in Kotlin. */
function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

function toBase64(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

export async function passwordSalt(email: string): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest(
    'SHA-256',
    encoder.encode(SALT_PREFIX + normalizeEmail(email)),
  )
  return new Uint8Array(digest)
}

/**
 * Derives the proof the Worker stores a hash of.
 *
 * Roughly a third of a second of main-thread work on a laptop. That is deliberate — it is the
 * cost an attacker pays per guess — and it is why the sign-in button has a busy state.
 */
export async function passwordProof(email: string, password: string): Promise<PasswordProof> {
  const salt = await passwordSalt(email)
  const key = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, [
    'deriveBits',
  ])
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations: ITERATIONS },
    key,
    KEY_LENGTH_BITS,
  )
  return { scheme: PASSWORD_SCHEME, value: toBase64(new Uint8Array(bits)) }
}
