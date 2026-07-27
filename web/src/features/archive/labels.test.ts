import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { alsoRecordedBy, archivedBecause, lockNote } from './labels'

describe('alsoRecordedBy', () => {
  it('says nothing when this is the only recording of the workout', () => {
    expect(alsoRecordedBy(1)).toBeNull()
    expect(alsoRecordedBy(0)).toBeNull()
  })

  it('counts the other apps, not this one', () => {
    expect(alsoRecordedBy(2)).toBe('Also recorded by 1 other app')
    expect(alsoRecordedBy(3)).toBe('Also recorded by 2 other apps')
  })
})

describe('archivedBecause', () => {
  it('distinguishes the automatic choice from the athlete’s own', () => {
    expect(archivedBecause('duplicate')).toContain('automatically')
    expect(archivedBecause('manual')).toContain('yourself')
    expect(archivedBecause(null)).toBe('Set aside')
  })
})

describe('lockNote', () => {
  it('promises a manual decision survives the next sync', () => {
    expect(lockNote(true)).toContain('every future sync')
    expect(lockNote(false)).toBeNull()
  })
})

/**
 * The archive screen is the athlete's proof that nothing is ever destroyed, so the vocabulary
 * is part of the contract rather than a matter of taste. This asserts it against the source
 * itself: a future card that grows a destructive verb fails here before anyone ships it.
 */
describe('the archive vocabulary', () => {
  const forbidden = [/\bdelete/i, /\bdeleted\b/i, /\bdeleting\b/i, /\bremove/i, /\bdiscard/i]

  // Walked up rather than resolved from `import.meta.url`, which is an http URL under jsdom.
  const relative = join('web', 'src', 'features', 'archive')
  let root = process.cwd()
  while (!existsSync(join(root, relative))) {
    const parent = dirname(root)
    if (parent === root) throw new Error(`Could not find ${relative} above ${process.cwd()}`)
    root = parent
  }
  const here = join(root, relative)

  const sources = readdirSync(here)
    .filter((name) => /\.(ts|tsx|css)$/.test(name) && !name.endsWith('.test.ts'))
    .map((name) => [name, readFileSync(join(here, name), 'utf8')] as const)

  it('reads every file in the feature', () => {
    expect(sources.length).toBeGreaterThan(3)
  })

  for (const [name, text] of sources) {
    it(`never speaks of destroying anything in ${name}`, () => {
      for (const pattern of forbidden) {
        expect(text, `${name} matches ${pattern}`).not.toMatch(pattern)
      }
    })
  }
})
