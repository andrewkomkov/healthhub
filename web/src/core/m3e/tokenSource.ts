import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

/**
 * Reads the design-token source and both generated artefacts from disk.
 *
 * Test-only, and it uses `node:fs`, so nothing in the app may import it — the browser gets
 * its tokens the way it always has, through `generated-tokens.css`. The point of reading the
 * files rather than importing the JSON is that the *generated* output is what the clients
 * actually wear; a parity test that compared tokens.json with itself would prove nothing.
 */

export interface TypeToken {
  size: number
  lineHeight: number
  weight: number
  tracking: number
}

export interface Tokens {
  color: Record<'light' | 'dark', Record<string, string>>
  chart: {
    series: Record<'light' | 'dark', string[]>
    channelSlots: Record<string, number>
    sequential: { steps: string[]; ordinalFloorLight: number; ordinalCeilingDark: number }
    diverging: {
      negative: string
      positive: string
      midpoint: Record<'light' | 'dark', string>
    }
    status: Record<'light' | 'dark', Record<string, string>>
    chrome: Record<'light' | 'dark', Record<string, string>>
    marks: Record<string, number | string[]>
  }
  shape: Record<string, number>
  typography: { fontFamily: string; scale: Record<string, TypeToken> }
  motion: Record<string, Record<string, unknown>>
  spacing: Record<string, number>
  elevation: Record<string, number>
}

/**
 * Walks up from the working directory rather than resolving from `import.meta.url`: under
 * jsdom that is an http URL, and which workspace invoked vitest decides the cwd.
 */
export function repoFile(...segments: string[]): string {
  const relative = join(...segments)
  let directory = process.cwd()
  while (!existsSync(join(directory, relative))) {
    const parent = dirname(directory)
    if (parent === directory) throw new Error(`Could not find ${relative} above ${process.cwd()}`)
    directory = parent
  }
  return readFileSync(join(directory, relative), 'utf8')
}

export const tokens = JSON.parse(repoFile('packages', 'design-tokens', 'tokens.json')) as Tokens

export const generatedCss = repoFile('web', 'src', 'core', 'm3e', 'generated-tokens.css')

export const generatedKotlin = repoFile(
  'android',
  'core',
  'designsystem',
  'src',
  'main',
  'kotlin',
  'dev',
  'healthhub',
  'core',
  'designsystem',
  'GeneratedTokens.kt',
)

/** Every `--custom-property: value` in one CSS block, in source order. */
export function declarations(css: string): Map<string, string> {
  const out = new Map<string, string>()
  for (const [, name, value] of css.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)) {
    out.set(name as string, (value as string).trim())
  }
  return out
}

/**
 * The custom properties in effect for one appearance.
 *
 * Light is `:root` plus nothing; dark is `:root` overridden by the explicit
 * `[data-theme="dark"]` block, which is the same set the media query applies. Later
 * declarations win, exactly as the cascade would resolve them at equal specificity.
 */
export function propertiesFor(mode: 'light' | 'dark'): Map<string, string> {
  const root = /:root \{([\s\S]*?)\n\}/.exec(generatedCss)?.[1]
  if (!root) throw new Error('generated-tokens.css has no :root block')
  const resolved = declarations(root)
  if (mode === 'dark') {
    const dark = /:root\[data-theme="dark"\] \{([\s\S]*?)\n\}/.exec(generatedCss)?.[1]
    if (!dark) throw new Error('generated-tokens.css has no [data-theme="dark"] block')
    for (const [name, value] of declarations(dark)) resolved.set(name, value)
  }
  return resolved
}
