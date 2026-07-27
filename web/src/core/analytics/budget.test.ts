import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * The feed's first screen is a two-second budget, and the three heaviest things in this app —
 * DuckDB, MapLibre and µPlot — belong to screens most sessions never open. Nothing enforces
 * that in a bundler warning: a static import from the wrong file simply folds the screen into
 * the entry chunk, and the only symptom is a number in the build output nobody was looking at.
 *
 * So it is asserted here instead. Every route below the feed is reached through a dynamic
 * `import()`, and nothing on the cold-start path names a module that would drag one in.
 */

// Walked up rather than resolved from `import.meta.url`, which is an http URL under jsdom.
function repoRoot(): string {
  const marker = join('web', 'src', 'core', 'analytics')
  let root = process.cwd()
  while (!existsSync(join(root, marker))) {
    const parent = dirname(root)
    if (parent === root) throw new Error(`Could not find ${marker} above ${process.cwd()}`)
    root = parent
  }
  return root
}

const ROOT = repoRoot()
const SRC = join(ROOT, 'web', 'src')

function sourcesUnder(directory: string): string[] {
  if (!existsSync(directory)) return []
  if (statSync(directory).isFile()) return [directory]
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name)
    if (statSync(path).isDirectory()) return sourcesUnder(path)
    return /\.tsx?$/.test(name) && !name.endsWith('.test.ts') && !name.endsWith('.test.tsx')
      ? [path]
      : []
  })
}

/** Everything the browser must parse before the feed can paint. */
const COLD_START = [
  join(SRC, 'main.tsx'),
  join(SRC, 'App.tsx'),
  join(SRC, 'features', 'feed'),
  join(SRC, 'features', 'auth'),
  join(SRC, 'core', 'api'),
  join(SRC, 'core', 'm3e'),
  join(SRC, 'core', 'format.ts'),
]

/**
 * Modules that exist only to serve a screen below the feed, each of them large enough that
 * folding it into the entry chunk is the whole regression. A static import of any of these
 * from a cold-start file is the mistake; the key is what the failure message will say.
 */
const OFF_THE_COLD_PATH: Record<string, RegExp> = {
  DuckDB: /from\s+'@duckdb\/duckdb-wasm'/,
  'the analytics core': /from\s+'[^']*core\/analytics/,
  MapLibre: /from\s+'maplibre-gl/,
  'the map layer': /from\s+'[^']*core\/map/,
  'µPlot': /from\s+'uplot/,
  'the chart layer': /from\s+'[^']*core\/charts/,
  'the telemetry codec': /from\s+'[^']*core\/telemetry/,
}

describe('the first-screen budget', () => {
  const files = COLD_START.flatMap(sourcesUnder)

  it('has files to check', () => {
    expect(files.length).toBeGreaterThan(4)
  })

  for (const file of files) {
    for (const [what, pattern] of Object.entries(OFF_THE_COLD_PATH)) {
      it(`${relative(SRC, file)} does not pull in ${what}`, () => {
        expect(readFileSync(file, 'utf8')).not.toMatch(pattern)
      })
    }
  }
})

/**
 * The router is the one file that names every screen, so it is the one file where a static
 * import quietly undoes the splitting. `App.tsx` may reach the feed and the sign-in form
 * directly — they *are* the cold start — and everything else only through `lazy(import())`.
 */
describe('the router', () => {
  const app = readFileSync(join(SRC, 'App.tsx'), 'utf8')
  const EAGER = new Set(['feed', 'auth'])

  const staticFeatureImports = [...app.matchAll(/^import[^\n]*?from\s+'\.\/features\/([^/']+)/gm)]
    .map((match) => match[1] as string)
    .filter((feature) => !EAGER.has(feature))

  const lazyFeatureImports = new Set(
    [...app.matchAll(/import\(\s*'\.\/features\/([^/']+)/g)].map((match) => match[1] as string),
  )

  it('imports no screen below the feed statically', () => {
    expect(staticFeatureImports).toEqual([])
  })

  it('code-splits every screen below the feed', () => {
    // Named rather than counted: a route added and not split should fail here, and a route
    // legitimately removed should not.
    expect([...lazyFeatureImports].sort()).toEqual(['activity', 'archive', 'health', 'sources'])
  })
})

describe('the engine itself', () => {
  const session = readFileSync(join(SRC, 'core', 'analytics', 'session.ts'), 'utf8')

  it('is only ever reached through a dynamic import', () => {
    expect(session).toContain("await import('@duckdb/duckdb-wasm')")
    // A static import would be hoisted into whatever chunk this file lands in.
    expect(session).not.toMatch(/^import .*'@duckdb\/duckdb-wasm'/m)
  })

  it('does not bundle the Wasm binary, which is larger than a Workers asset may be', () => {
    const analytics = sourcesUnder(join(SRC, 'core', 'analytics')).map((file) =>
      readFileSync(file, 'utf8'),
    )
    for (const text of analytics) {
      expect(text).not.toMatch(/duckdb-(mvp|eh)\.wasm\?url/)
      expect(text).not.toMatch(/\.worker\.js\?/)
    }
  })
})
