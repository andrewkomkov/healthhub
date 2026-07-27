import { beforeEach, describe, expect, it } from 'vitest'
import { applyDynamicTheme, dynamicRoles } from './dynamicTheme'
import { repoFile, tokens } from './tokenSource'

/**
 * Material You personalises the interface and never the data.
 *
 * The chart series palette is chosen for contrast against both surfaces and measured for
 * colour-vision separation; a palette extracted from a photograph of somebody's dog carries
 * neither guarantee. That rule lives in three places — the phone that uploads a scheme, the
 * Worker that stores it, the browser that applies it — and it is the kind of rule a future
 * change breaks by being helpful. So all three are pinned here.
 */

const CHART_KEYS = ['chartSeries1', 'series', 'chartSurface', 'inkPrimary', 'sequential']

function scheme(extra: Record<string, string> = {}): Record<string, string> {
  return { primary: '#123456', surface: '#abcdef', onSurface: '#111111', ...extra }
}

describe('dynamic theme', () => {
  beforeEach(() => {
    document.head.innerHTML = ''
    applyDynamicTheme(null)
  })

  const sheet = () => document.getElementById('hh-dynamic-theme')?.textContent ?? ''

  it('writes only UI colour roles', () => {
    applyDynamicTheme({
      light: scheme(),
      dark: scheme(),
      source: 'dynamic',
      updatedAt: 0,
    })
    const properties = [...sheet().matchAll(/(--[a-z0-9-]+):/g)].map(([, name]) => name as string)
    expect(properties.length).toBeGreaterThan(0)
    for (const property of properties) expect(property.startsWith('--md-sys-color-')).toBe(true)
  })

  it('cannot introduce a chart variable however the scheme is shaped', () => {
    const hostile = Object.fromEntries(CHART_KEYS.map((key) => [key, '#ff00ff']))
    applyDynamicTheme({
      light: scheme(hostile),
      dark: scheme(hostile),
      source: 'dynamic',
      updatedAt: 0,
    })
    expect(sheet()).not.toContain('--chart-')
    expect(sheet()).not.toContain('#ff00ff')
  })

  it('drops a role whose value is not a plain hex colour', () => {
    applyDynamicTheme({
      // A value that closes the declaration would otherwise let an uploaded scheme write
      // arbitrary CSS into another session's page.
      light: scheme({ outline: 'red; --chart-series-1: red' }),
      dark: scheme(),
      source: 'dynamic',
      updatedAt: 0,
    })
    expect(sheet()).not.toContain('--chart-series-1')
    expect(sheet()).not.toContain('--md-sys-color-outline')
  })

  it('removes itself, restoring the generated palette', () => {
    applyDynamicTheme({ light: scheme(), dark: scheme(), source: 'dynamic', updatedAt: 0 })
    expect(sheet()).not.toBe('')
    applyDynamicTheme(null)
    expect(document.getElementById('hh-dynamic-theme')).toBeNull()
  })

  it('accepts exactly the roles the token file and the Worker define', () => {
    const declared = new Set(Object.keys(tokens.color.light))
    expect(new Set(dynamicRoles)).toEqual(declared)

    // The Worker is the other end of the same contract: a role it accepts and the browser
    // ignores is a colour that silently never arrives.
    const worker = repoFile('worker', 'src', 'routes', 'theme.ts')
    const list = /const ROLES = new Set\(\[([\s\S]*?)\]\)/.exec(worker)?.[1]
    expect(list, 'worker/src/routes/theme.ts no longer declares ROLES').toBeDefined()
    const workerRoles = new Set(
      [...(list as string).matchAll(/'(\w+)'/g)].map(([, role]) => role as string),
    )
    expect(workerRoles).toEqual(declared)
  })

  it('does not carry the series palette between the clients at all', () => {
    // The Android side flattens a ColorScheme into the map that is uploaded. If a chart colour
    // ever appears in it, personalisation has crossed into the data layer.
    const kotlin = repoFile(
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
      'DynamicColors.kt',
    )
    const body = /fun toRoleMap\([\s\S]*?\n    \)/.exec(kotlin)?.[0] ?? ''
    const uploaded = [...body.matchAll(/"(\w+)" to/g)].map(([, role]) => role as string)
    expect(uploaded.length).toBeGreaterThan(0)
    for (const role of uploaded) expect(dynamicRoles.has(role)).toBe(true)
    for (const channel of Object.keys(tokens.chart.channelSlots)) {
      expect(uploaded).not.toContain(channel)
    }
  })
})
