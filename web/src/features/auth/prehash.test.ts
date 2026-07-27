import { webcrypto } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { PASSWORD_SCHEME, passwordProof, passwordSalt } from './prehash'

/**
 * The pre-hash exists twice — here and in Kotlin — and the two halves never meet at runtime:
 * an athlete registers on one client and signs in on the other. `fixtures/auth/prehash-v1.json`
 * is the only thing that fails when they drift.
 *
 * The Worker cannot generate these vectors, which is worth stating plainly: 600,000 iterations
 * is six times the ceiling workerd enforces, and that ceiling is the reason the client-side
 * pass exists at all.
 */

// jsdom implements `crypto.getRandomValues` and nothing else, so `crypto.subtle` is missing in
// this environment and present in every browser that will run the module under test.
if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, 'crypto', { value: webcrypto, configurable: true })
}

interface Vector {
  why: string
  email: string
  password: string
  saltBase64: string
  value: string
}

interface Fixture {
  scheme: string
  iterations: number
  keyBytes: number
  saltPrefix: string
  vectors: Vector[]
}

/** Walked up rather than resolved from `import.meta.url`: under jsdom that is an http URL. */
function fixture(): Fixture {
  const relative = join('fixtures', 'auth', 'prehash-v1.json')
  let directory = process.cwd()
  while (!existsSync(join(directory, relative))) {
    const parent = dirname(directory)
    if (parent === directory) throw new Error(`Could not find ${relative} above ${process.cwd()}`)
    directory = parent
  }
  return JSON.parse(readFileSync(join(directory, relative), 'utf8')) as Fixture
}

const toBase64 = (bytes: Uint8Array) => Buffer.from(bytes).toString('base64')

describe('the password pre-hash', () => {
  const vectors = fixture()

  it('is the scheme the fixture names', () => {
    expect(PASSWORD_SCHEME).toBe(vectors.scheme)
  })

  it.each(vectors.vectors)('derives the salt for $why', async (vector) => {
    expect(toBase64(await passwordSalt(vector.email))).toBe(vector.saltBase64)
  })

  it.each(vectors.vectors)('derives the proof for $why', async (vector) => {
    const proof = await passwordProof(vector.email, vector.password)
    expect(proof.scheme).toBe(vectors.scheme)
    expect(proof.value).toBe(vector.value)
  })

  it('gives two accounts different proofs for the same password', async () => {
    const one = await passwordProof('one@example.com', 'correct-horse-battery')
    const two = await passwordProof('two@example.com', 'correct-horse-battery')
    expect(one.value).not.toBe(two.value)
  })

  it('produces a proof of exactly the width the Worker accepts', async () => {
    const proof = await passwordProof('a@example.com', 'correct-horse-battery')
    expect(Buffer.from(proof.value, 'base64')).toHaveLength(vectors.keyBytes)
  })
})
