import type { Locale } from './i18n'

/** Presentation helpers. Nothing here computes a metric — it only renders one. */

export type UnitSystem = 'metric' | 'imperial'

/**
 * The unit suffixes, in the reader's language.
 *
 * The Kotlin twin is `core/ui/Format.kt`'s `UnitLabels`, field for field and default for
 * default. A default parameter rather than a required one for the same reason it is one there:
 * these functions are pinned by tests against the phone's output, and a required argument would
 * have put a language into every one of those assertions. A screen passes {@link unitLabels}
 * for its locale; a test leaves it alone and keeps asserting on "41.20 km".
 */
export interface UnitLabels {
  kilometres: string
  miles: string
  kilometresPerHour: string
  milesPerHour: string
  perKilometre: string
  perMile: string
  metres: string
  feet: string
  beatsPerMinute: string
  watts: string
  kilocalories: string
}

const EN: UnitLabels = {
  kilometres: 'km',
  miles: 'mi',
  kilometresPerHour: 'km/h',
  milesPerHour: 'mph',
  perKilometre: '/km',
  perMile: '/mi',
  metres: 'm',
  feet: 'ft',
  beatsPerMinute: 'bpm',
  watts: 'W',
  kilocalories: 'kcal',
}

const RU: UnitLabels = {
  kilometres: 'км',
  miles: 'миль',
  kilometresPerHour: 'км/ч',
  milesPerHour: 'миль/ч',
  perKilometre: '/км',
  perMile: '/милю',
  metres: 'м',
  feet: 'фт',
  beatsPerMinute: 'уд/мин',
  watts: 'Вт',
  kilocalories: 'ккал',
}

/** What the tests assert against, and what an untranslated locale falls back to. */
export const ENGLISH_UNITS = EN

export const unitLabels = (locale: Locale): UnitLabels => (locale === 'ru' ? RU : EN)

export function distance(
  metres: number | null,
  units: UnitSystem,
  labels: UnitLabels = EN,
): string {
  if (metres === null) return '—'
  if (units === 'imperial') return `${(metres / 1609.344).toFixed(2)} ${labels.miles}`
  return `${(metres / 1000).toFixed(2)} ${labels.kilometres}`
}

export function duration(seconds: number | null): string {
  if (seconds === null) return '—'
  const total = Math.round(seconds)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

/**
 * Cycling reads as speed, running and walking as pace — showing a runner 12.4 km/h instead
 * of 4:50 /km is technically correct and practically useless.
 */
export function paceOrSpeed(
  metresPerSecond: number | null,
  sport: string,
  units: UnitSystem,
  labels: UnitLabels = EN,
): string {
  if (!metresPerSecond || metresPerSecond <= 0) return '—'

  const speedSports = new Set(['cycling', 'ebiking', 'rowing', 'swimming', 'skiing', 'skating'])
  if (speedSports.has(sport)) {
    const value = units === 'imperial' ? metresPerSecond * 2.236936 : metresPerSecond * 3.6
    return `${value.toFixed(1)} ${units === 'imperial' ? labels.milesPerHour : labels.kilometresPerHour}`
  }

  const perUnit = units === 'imperial' ? 1609.344 : 1000
  const secondsPerUnit = perUnit / metresPerSecond
  const m = Math.floor(secondsPerUnit / 60)
  const s = Math.round(secondsPerUnit % 60)
  const carry = s === 60
  const suffix = units === 'imperial' ? labels.perMile : labels.perKilometre
  return `${carry ? m + 1 : m}:${String(carry ? 0 : s).padStart(2, '0')} ${suffix}`
}

export function elevation(
  metres: number | null,
  units: UnitSystem,
  labels: UnitLabels = EN,
): string {
  if (metres === null) return '—'
  return units === 'imperial'
    ? `${Math.round(metres * 3.28084)} ${labels.feet}`
    : `${Math.round(metres)} ${labels.metres}`
}

export function heartRate(bpm: number | null, labels: UnitLabels = EN): string {
  return bpm === null ? '—' : `${Math.round(bpm)} ${labels.beatsPerMinute}`
}

/** Renders in the timezone the activity was recorded in, not the viewer's. */
export function localDate(startTime: number, tzOffsetMinutes: number): string {
  const local = new Date(startTime + tzOffsetMinutes * 60_000)
  return local.toLocaleString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'UTC',
  })
}

/**
 * What a sport is called, in the reader's language.
 *
 * The slug capitalised is what this used to be, which produced "Cycling" next to a date the
 * browser localises — the same mixed-language card the phone had. An unlisted slug falls back
 * to the capitalised slug rather than to a generic word: Principle VI, a type the app cannot
 * name is still a type it must not hide.
 */
const SPORTS: Record<Locale, Record<string, string>> = {
  en: {},
  ru: {
    running: 'Бег',
    trail_running: 'Трейл',
    walking: 'Ходьба',
    hiking: 'Поход',
    cycling: 'Велосипед',
    mountain_biking: 'Горный велосипед',
    ebiking: 'Электровелосипед',
    swimming: 'Плавание',
    rowing: 'Гребля',
    skiing: 'Лыжи',
    snowboarding: 'Сноуборд',
    skating: 'Коньки',
    strength: 'Силовая',
    yoga: 'Йога',
    other: 'Другое',
  },
}

export function sportLabel(sport: string, locale: Locale = 'en'): string {
  const translated = SPORTS[locale]?.[sport]
  if (translated) return translated
  return sport.charAt(0).toUpperCase() + sport.slice(1).replace(/[_-]/g, ' ')
}

/**
 * A human name for the app that wrote a recording.
 *
 * The phone sends a label when Android could give it one, and a package name always. Falling
 * back to the last meaningful segment beats showing `com.google.android.apps.fitness` in a
 * list the athlete is meant to reason about — but the package stays visible next to it,
 * because two apps can share a pretty name and only one of them wrote this workout.
 */
export function sourceLabel(packageName: string | null, label?: string | null): string {
  if (label) return label
  if (!packageName) return 'Unknown app'

  const segments = packageName.split('.').filter(Boolean)
  // Trailing segments like `.app` or `.android` name the platform, not the product.
  const generic = new Set(['app', 'apps', 'android', 'mobile', 'client'])
  const meaningful = [...segments].reverse().find((segment) => !generic.has(segment))
  const name = meaningful ?? segments[segments.length - 1] ?? packageName
  return name.charAt(0).toUpperCase() + name.slice(1)
}
