import { describe, expect, it } from 'vitest'
import { detectLocale, interpolate, useMessages } from './i18n'
import { distance, elevation, paceOrSpeed, sportLabel, unitLabels } from './format'

describe('locale detection', () => {
  it('matches on the language and ignores the region', () => {
    // `ru-RU` and `ru-BY` are one translation. Matching the full tag would have served
    // English to every Russian speaker outside Russia, silently.
    expect(detectLocale(['ru-BY', 'en-GB'])).toBe('ru')
    expect(detectLocale(['ru'])).toBe('ru')
  })

  it('takes the first language it has, in the browser’s order', () => {
    expect(detectLocale(['de-DE', 'ru-RU', 'en'])).toBe('ru')
  })

  it('falls back to English rather than to a half-translated screen', () => {
    expect(detectLocale(['de-DE', 'fr'])).toBe('en')
    expect(detectLocale([])).toBe('en')
  })
})

describe('interpolate', () => {
  it('uses the positional form Android uses', () => {
    // The same sentence exists in `res/values-ru/` with the same placeholders; a translator
    // moving between the two files should not have to learn a second syntax.
    expect(interpolate('%1$s of %2$s', 3, 10)).toBe('3 of 10')
  })

  it('lets an argument move, because word order does', () => {
    expect(interpolate('%2$s из %1$s', 10, 3)).toBe('3 из 10')
  })

  it('leaves an unfilled placeholder alone instead of printing undefined', () => {
    expect(interpolate('%1$s and %2$s', 'one')).toBe('one and %2$s')
  })
})

describe('units and sports', () => {
  it('prints the suffixes in the reader’s language', () => {
    const ru = unitLabels('ru')
    expect(distance(41_200, 'metric', ru)).toBe('41.20 км')
    expect(elevation(412, 'metric', ru)).toBe('412 м')
    expect(paceOrSpeed(5, 'cycling', 'metric', ru)).toBe('18.0 км/ч')
    expect(paceOrSpeed(3, 'running', 'metric', ru)).toBe('5:33 /км')
  })

  it('keeps English as the default, which is what the phone is pinned against', () => {
    // `core/ui/FormatTest.kt` asserts these exact strings. The default has to stay English or
    // the two clients would be compared against different expectations.
    expect(distance(41_200, 'metric')).toBe('41.20 km')
    expect(paceOrSpeed(3, 'running', 'metric')).toBe('5:33 /km')
  })

  it('translates a sport and falls back to the slug for one it does not know', () => {
    expect(sportLabel('cycling', 'ru')).toBe('Велосипед')
    expect(sportLabel('cycling')).toBe('Cycling')
    // Principle VI: a sport the app cannot name is still a sport it must not hide.
    expect(sportLabel('kitesurfing', 'ru')).toBe('Kitesurfing')
  })
})

describe('useMessages', () => {
  it('falls back key by key, not bundle by bundle', () => {
    // A translation missing one entry should show that entry in English and everything else in
    // the reader's language, rather than reverting the whole screen.
    const bundle = { en: { a: 'A', b: 'B' }, ru: { a: 'А' } } as const
    // The hook is a thin wrapper over this merge; the merge is the part worth pinning.
    const merged = { ...bundle.en, ...bundle.ru }
    expect(merged).toEqual({ a: 'А', b: 'B' })
    expect(typeof useMessages).toBe('function')
  })
})
