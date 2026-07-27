/**
 * Password hashing over WebCrypto — no dependency, nothing to keep up to date.
 *
 * PBKDF2-SHA-256 was chosen over Argon2id/scrypt deliberately: the stronger algorithms need
 * a WASM or pure-JS dependency and more CPU than a Worker's per-request budget reliably
 * allows. See research.md R-006.
 */

/**
 * 100,000 is not a preference — it is the ceiling the Workers runtime enforces:
 * `Pbkdf2 failed: iteration counts above 100000 are not supported`. Anything higher
 * throws at runtime rather than merely running slowly.
 *
 * The encoded hash carries its own iteration count, so raising this later — or moving to a
 * client-side pre-hash to buy more work without spending Worker CPU — re-verifies existing
 * passwords unchanged.
 */
const ITERATIONS = 100_000
const KEY_LENGTH_BITS = 256
const SALT_BYTES = 16

const encoder = new TextEncoder()

function toBase64(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

function fromBase64(value: string): Uint8Array {
  const binary = atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return bytes
}

async function derive(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, [
    'deriveBits',
  ])
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations },
    key,
    KEY_LENGTH_BITS,
  )
  return new Uint8Array(bits)
}

/** Encodes as `pbkdf2-sha256$<iterations>$<salt_b64>$<hash_b64>` so parameters can evolve. */
export async function hashPassword(password: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES))
  const hash = await derive(password, salt, ITERATIONS)
  return `pbkdf2-sha256$${ITERATIONS}$${toBase64(salt)}$${toBase64(hash)}`
}

/** Constant-time comparison so verification does not leak the hash by timing. */
function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i += 1) diff |= (a[i] as number) ^ (b[i] as number)
  return diff === 0
}

export async function verifyPassword(password: string, encoded: string): Promise<boolean> {
  const parts = encoded.split('$')
  if (parts.length !== 4 || parts[0] !== 'pbkdf2-sha256') return false

  const iterations = Number(parts[1])
  if (!Number.isInteger(iterations) || iterations < 1) return false

  const salt = fromBase64(parts[2] as string)
  const expected = fromBase64(parts[3] as string)
  const actual = await derive(password, salt, iterations)
  return timingSafeEqual(actual, expected)
}
