import { createContext, useContext } from 'react'

/**
 * The whole of the web client's localisation.
 *
 * Forty lines and no dependency, and both facts are deliberate. The entry chunk is a two-second
 * budget that is already 90% React (see `core/analytics/budget.test.ts` and the note in
 * AGENT-NOTES), and an i18n library is tens of kilobytes to solve problems this product does not
 * have — there is no pluralisation beyond a couple of counts, no date formatting that `Intl`
 * does not already do, and no translator workflow to feed.
 *
 * The shape that matters is where the *strings* live: each screen declares its own bundle
 * beside itself and reads it with {@link useMessages}. A screen below the feed is lazily
 * imported, so its strings land in its own chunk rather than in the entry — a single central
 * dictionary would have put every word of the archive, the sources screen and the analytics
 * console into the first thing the browser parses.
 *
 * The Android twin is `res/values/` and `res/values-ru/`, one set per feature module, for
 * exactly the same reason and with the same keys where the two screens say the same thing.
 */

export const LOCALES = ['en', 'ru'] as const
export type Locale = (typeof LOCALES)[number]

export const DEFAULT_LOCALE: Locale = 'en'

/**
 * The reader's language, from the browser.
 *
 * `navigator.languages` in order, first match wins, and the region is dropped — `ru-RU` and
 * `ru-BY` are the same translation, and matching on the full tag would have quietly served
 * English to everybody outside Russia. Anything unlisted falls back to English rather than to
 * a half-translated screen.
 */
export function detectLocale(
  candidates: readonly string[] = typeof navigator === 'undefined' ? [] : navigator.languages,
): Locale {
  for (const candidate of candidates) {
    const base = candidate.toLowerCase().split('-')[0]
    const match = LOCALES.find((locale) => locale === base)
    if (match) return match
  }
  return DEFAULT_LOCALE
}

export const LocaleContext = createContext<Locale>(DEFAULT_LOCALE)

export const useLocale = (): Locale => useContext(LocaleContext)

/** A screen's own strings, in every language it has been translated into. */
export type Bundle<T extends Record<string, string>> = Record<Locale, T>

/**
 * The strings for this composition's language.
 *
 * Falls back key by key rather than bundle by bundle: a translation that is missing one entry
 * should show that entry in English and everything else in the reader's language, not revert
 * the whole screen. `messages.test.ts` is what stops a key going missing silently.
 */
export function useMessages<T extends Record<string, string>>(bundle: Bundle<T>): T {
  const locale = useLocale()
  if (locale === DEFAULT_LOCALE) return bundle[DEFAULT_LOCALE]
  return { ...bundle[DEFAULT_LOCALE], ...bundle[locale] }
}

/**
 * `interpolate('%1$s of %2$s', a, b)`.
 *
 * The positional form Android uses, on purpose: the same sentence exists in `res/values-ru/`
 * with the same placeholders, and a translator moving from one file to the other should not
 * have to learn a second syntax. Positional rather than `{name}` because word order genuinely
 * differs between the two languages and an argument has to be able to move.
 */
export function interpolate(template: string, ...args: (string | number)[]): string {
  return template.replace(/%(\d+)\$s/g, (whole, index: string) => {
    const value = args[Number(index) - 1]
    return value === undefined ? whole : String(value)
  })
}
