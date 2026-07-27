/**
 * Password hashing over WebCrypto — no dependency, nothing to keep up to date.
 *
 * PBKDF2-SHA-256 was chosen over Argon2id/scrypt deliberately: the stronger algorithms need
 * a WASM or pure-JS dependency and more CPU than a Worker's per-request budget reliably
 * allows. See research.md R-006.
 *
 * The KDF is split across two machines, and neither half is sufficient alone. The athlete's
 * device runs the expensive pass and sends the result; the Worker salts that result with a
 * per-user random salt and runs its own pass at the only iteration count the runtime permits.
 * Total work is the sum; the Worker's share is capped and the client's is not.
 */

/**
 * 100,000 is not a preference — it is the ceiling the Workers runtime enforces:
 * `Pbkdf2 failed: iteration counts above 100000 are not supported`. Anything higher throws at
 * runtime rather than merely running slowly, and the local `workerd` dev runtime does not
 * enforce it, so raising this passes every local check and fails in production.
 */
const SERVER_ITERATIONS = 100_000
const KEY_LENGTH_BITS = 256
const SALT_BYTES = 16

/** A client proof is 256 bits, the output width of the client-side KDF. */
const PROOF_BYTES = 32

/**
 * The scheme name for "the client sent the password itself".
 *
 * It never appears on the wire — the raw password has its own field — and it is never chosen
 * for a new record while any stronger proof is available. It exists so that records written
 * before the amendment have a name, which is what makes them migratable rather than invalid.
 */
export const PLAIN_SCHEME = 'plain'

/**
 * What an up-to-date client computes, encoded so the parameters are impossible to mistake:
 * PBKDF2-HMAC-SHA-256, 600,000 iterations, salt derivation v1. The 600,000 is the figure
 * R-006 originally specified and the platform then refused — it is now spent where there is
 * no ceiling.
 *
 * Changing any parameter means a new scheme name, never a new meaning for this one. The
 * client cannot ask which scheme an account uses without that question becoming an account
 * enumeration oracle, so it offers what it has and the record decides.
 */
export const CURRENT_CLIENT_SCHEME = 'pbkdf2-sha256/600000/v1'

/**
 * Every scheme a stored record may name, most preferred first.
 *
 * A retired scheme stays in this list for as long as one account still uses it: dropping it
 * does not invalidate the password, it locks the athlete out. `PLAIN_SCHEME` is last, so any
 * successful sign-in that also carried a proof rewrites the record under the stronger one.
 */
const SCHEMES: readonly string[] = [CURRENT_CLIENT_SCHEME, PLAIN_SCHEME]

/** Client schemes a request may name. `plain` is deliberately absent — it is not a proof. */
export const CLIENT_SCHEMES: readonly string[] = [CURRENT_CLIENT_SCHEME]

/**
 * One credential the client offered.
 *
 * `value` is the base64 proof for a client scheme, and the password itself for `plain`. The
 * Worker treats both as opaque input to its own PBKDF2 pass and never inspects them.
 */
export interface Proof {
  scheme: string
  value: string
}

export interface VerifyResult {
  ok: boolean
  /**
   * A replacement record to store, set when a sign-in succeeded against a weaker scheme than
   * the client can now prove. This is the whole migration path: an account created before the
   * amendment upgrades on the athlete's next sign-in, without anyone resetting anything.
   */
  upgraded: string | null
}

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

/** True when `value` is base64 for exactly the width a client proof has. */
export function isProofValue(value: string): boolean {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return false
  try {
    return fromBase64(value).length === PROOF_BYTES
  } catch {
    return false
  }
}

async function derive(material: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey('raw', encoder.encode(material), 'PBKDF2', false, [
    'deriveBits',
  ])
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations },
    key,
    KEY_LENGTH_BITS,
  )
  return new Uint8Array(bits)
}

interface StoredRecord {
  iterations: number
  salt: Uint8Array
  hash: Uint8Array
  /** Which client scheme produced the material this record was built from. */
  scheme: string
}

/**
 * Reads `pbkdf2-sha256$<iterations>$<salt_b64>$<hash_b64>[$<client_scheme>]`.
 *
 * The trailing segment is what makes a pre-amendment row identifiable rather than merely
 * wrong: four segments means the Worker hashed the password itself, which is exactly what it
 * used to do. Returns `null` for anything unparseable, including the `external` sentinel an
 * Auth0-only account carries.
 */
function parseRecord(encoded: string): StoredRecord | null {
  const parts = encoded.split('$')
  if (parts.length !== 4 && parts.length !== 5) return null
  if (parts[0] !== 'pbkdf2-sha256') return null

  const iterations = Number(parts[1])
  if (!Number.isInteger(iterations) || iterations < 1) return null

  const scheme = parts.length === 5 ? (parts[4] as string) : PLAIN_SCHEME
  if (!SCHEMES.includes(scheme)) return null

  try {
    return {
      iterations,
      salt: fromBase64(parts[2] as string),
      hash: fromBase64(parts[3] as string),
      scheme,
    }
  } catch {
    return null
  }
}

/** The strongest proof the client offered, or `null` when it offered none. */
export function preferredProof(proofs: readonly Proof[]): Proof | null {
  for (const scheme of SCHEMES) {
    const match = proofs.find((proof) => proof.scheme === scheme)
    if (match) return match
  }
  return null
}

async function encodeRecord(proof: Proof): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES))
  const hash = await derive(proof.value, salt, SERVER_ITERATIONS)
  const base = `pbkdf2-sha256$${SERVER_ITERATIONS}$${toBase64(salt)}$${toBase64(hash)}`
  // A `plain` record keeps the four-segment shape it has always had, so nothing already in
  // the database has to be rewritten for the new parser to read it.
  return proof.scheme === PLAIN_SCHEME ? base : `${base}$${proof.scheme}`
}

/** Builds the stored record for a new account from the strongest proof on offer. */
export async function hashCredential(proofs: readonly Proof[]): Promise<string> {
  const proof = preferredProof(proofs)
  if (!proof) throw new Error('hashCredential needs at least one proof')
  return encodeRecord(proof)
}

/**
 * A throwaway record for an email that has no account, built under the same scheme the
 * request offered so that verifying it costs exactly one derivation — the same one a real
 * account costs. Without this, an unknown email is measurably cheaper than a wrong password.
 */
export function decoyRecord(proofs: readonly Proof[]): Promise<string> {
  const scheme = preferredProof(proofs)?.scheme ?? PLAIN_SCHEME
  return encodeRecord({ scheme, value: crypto.randomUUID() })
}

/** Constant-time comparison so verification does not leak the hash by timing. */
function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i += 1) diff |= (a[i] as number) ^ (b[i] as number)
  return diff === 0
}

/**
 * Verifies whichever credential the stored record asks for, and reports when the account can
 * be moved to a stronger one.
 *
 * The record chooses, not the client: offering a proof for a scheme the record was not built
 * from proves nothing, and is not accepted as though it did.
 */
export async function verifyCredential(
  proofs: readonly Proof[],
  encoded: string,
): Promise<VerifyResult> {
  const record = parseRecord(encoded)
  if (!record) return { ok: false, upgraded: null }

  const proof = proofs.find((candidate) => candidate.scheme === record.scheme)
  if (!proof) return { ok: false, upgraded: null }

  const actual = await derive(proof.value, record.salt, record.iterations)
  if (!timingSafeEqual(actual, record.hash)) return { ok: false, upgraded: null }

  const best = preferredProof(proofs)
  const stale = best !== null && best.scheme !== record.scheme
  const outdated = record.iterations !== SERVER_ITERATIONS
  const upgraded = best && (stale || outdated) ? await encodeRecord(best) : null
  return { ok: true, upgraded }
}
