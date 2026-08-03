/**
 * Readiness and trends, in the browser.
 *
 * The Kotlin twins are `feature/health/Readiness.kt` and `feature/health/Trends.kt`, and this
 * file is deliberately line-for-line with them — the fifth entry in AGENT-NOTES' "rules that
 * exist twice", and the same reasoning as `core/telemetry/analysis.ts`: SC-008 says the two
 * clients report the same numbers for the same athlete, and the only way to keep that true when
 * the logic exists twice is for both copies to be obviously the same thing.
 *
 * Why it exists twice at all, when almost nothing else in this product does: readiness has no
 * field anywhere on the API and must not have one. It is derived from rows the edge stored
 * verbatim, and deriving it at the edge would put arithmetic on a server, which is the one
 * thing Constitution Principle I forbids. So each client that has a screen derives it for its
 * own screen, from identical inputs, with identical constants.
 */

import type { Measurement } from '../../core/api/client'

/** One local calendar day's value for one kind. Mirrors `Trends.DayValue`. */
export interface DayValue {
  date: string
  value: number
}

/* ------------------------------------------------------------------------------- trends */

export function median(values: number[]): number | null {
  if (values.length === 0) return null
  const sorted = [...values].sort((a, b) => a - b)
  const middle = sorted.length >> 1
  return sorted.length % 2 === 1 ? sorted[middle]! : (sorted[middle - 1]! + sorted[middle]!) / 2
}

/**
 * One point per local day, oldest first.
 *
 * A day is one value. Health Connect is a hub, so a watch and a phone app both report this
 * morning's resting heart rate and both rows are stored; they are collapsed by taking the day's
 * **median** rather than by summing. It is the same rule as the workout one against summing
 * across sources, and for the same reason: a chart that added two apps' readings would draw a
 * resting pulse of 96 for someone whose pulse is 48.
 *
 * The edge already computed `localDate` from the reading's own offset, so a trip across two
 * timezones does not fold two mornings into one column.
 */
export function daily(measurements: Measurement[]): DayValue[] {
  const byDate = new Map<string, number[]>()
  for (const m of measurements) {
    if (!m.localDate) continue
    const bucket = byDate.get(m.localDate)
    if (bucket) bucket.push(m.value)
    else byDate.set(m.localDate, [m.value])
  }

  const out: DayValue[] = []
  for (const [date, values] of byDate) {
    const value = median(values)
    if (value !== null) out.push({ date, value })
  }
  return out.sort((a, b) => a.date.localeCompare(b.date))
}

export const latest = (days: DayValue[]): DayValue | null => days[days.length - 1] ?? null

/**
 * The typical value over `window` days, ignoring the most recent `excludingLast`.
 *
 * A baseline is a median, not a mean: one bad night's HRV reading is exactly the outlier a mean
 * drags the baseline towards, which then makes the *next* day look fine. And today is excluded
 * from its own baseline — comparing a reading against a window containing it pulls the
 * comparison towards zero difference precisely when the difference is what is being measured.
 */
export function baseline(days: DayValue[], window: number, excludingLast = 1): number | null {
  const usable = days.slice(0, days.length - excludingLast)
  if (usable.length < MIN_BASELINE_DAYS) return null
  return median(usable.slice(-window).map((d) => d.value))
}

/** How far today sits from the baseline, as a percentage. Null when either is missing. */
export function deviationPercent(today: number | null, base: number | null): number | null {
  if (today === null || base === null || base === 0) return null
  return ((today - base) / base) * 100
}

/**
 * Fewer than this many days is not a baseline, it is a coincidence.
 * Mirrors `Trends.MIN_BASELINE_DAYS`.
 */
export const MIN_BASELINE_DAYS = 7

/* ---------------------------------------------------------------------------- readiness */

export interface ReadinessInput {
  hrvToday?: number | null
  hrvBaseline?: number | null
  restingHrToday?: number | null
  restingHrBaseline?: number | null
  sleptSeconds?: number | null
  sleepNeedSeconds?: number
}

export interface ReadinessComponent {
  label: string
  /** 0–100. */
  score: number
  weight: number
  detail: string
}

export interface ReadinessScore {
  /** 0–100, or null when there is not enough history to compare against. */
  value: number | null
  components: ReadinessComponent[]
  note: string
}

/**
 * How last night compares with this athlete's own recent normal.
 *
 * Three deliberate limits, because a number with a colour attached is very easy to over-trust:
 * it is a **comparison, not a measurement** (every component is a ratio against this athlete's
 * own median, never a population value — there is no healthy HRV); it **refuses to guess**
 * below {@link MIN_BASELINE_DAYS} days of history, returning null and saying what is missing
 * rather than showing 50 out of 100 and looking like an answer; and it is **not a medical
 * claim**, which the screen says in words next to it.
 */
export function readiness(input: ReadinessInput): ReadinessScore {
  const components: ReadinessComponent[] = []

  // Heart-rate variability carries the most weight: it is the earliest of the three to move,
  // which is the only reason a morning number is worth reading at all.
  const hrv = component(
    'Heart-rate variability',
    input.hrvToday ?? null,
    input.hrvBaseline ?? null,
    HRV_WEIGHT,
    (ratio) => ramp(ratio, HRV_LOW, HRV_HIGH),
    (today, base) => `${Math.round(today)} ms against a normal of ${Math.round(base)} ms`,
  )
  if (hrv) components.push(hrv)

  const restingHr = component(
    'Resting heart rate',
    input.restingHrToday ?? null,
    input.restingHrBaseline ?? null,
    RESTING_HR_WEIGHT,
    // Inverted on purpose: a resting pulse *above* normal is the tired direction.
    (ratio) => ramp(ratio, RESTING_HR_HIGH, RESTING_HR_LOW),
    (today, base) => `${Math.round(today)} bpm against a normal of ${Math.round(base)} bpm`,
  )
  if (restingHr) components.push(restingHr)

  const need = input.sleepNeedSeconds ?? DEFAULT_SLEEP_NEED_SECONDS
  if (input.sleptSeconds != null && need > 0) {
    const ratio = input.sleptSeconds / need
    components.push({
      label: 'Sleep',
      score: ramp(ratio, SLEEP_LOW, SLEEP_FULL) * 100,
      weight: SLEEP_WEIGHT,
      detail: `${hoursAndMinutes(input.sleptSeconds)} of ${hoursAndMinutes(need)}`,
    })
  }

  if (components.length === 0) {
    return {
      value: null,
      components: [],
      note:
        'Readiness needs a week of heart-rate variability, resting heart rate or sleep before ' +
        'it can compare today with your normal.',
    }
  }

  // Weighted over whatever is present rather than penalising what is absent: an athlete whose
  // watch reports sleep but no HRV should still get a sleep-shaped answer instead of a third
  // of one.
  const totalWeight = components.reduce((sum, c) => sum + c.weight, 0)
  const value = components.reduce((sum, c) => sum + c.score * c.weight, 0) / totalWeight

  return {
    value: Math.min(100, Math.max(0, Math.round(value))),
    components,
    note: missingNote(components),
  }
}

function component(
  label: string,
  today: number | null,
  base: number | null,
  weight: number,
  score: (ratio: number) => number,
  detail: (today: number, base: number) => string,
): ReadinessComponent | null {
  if (today === null || base === null || base <= 0) return null
  return { label, score: score(today / base) * 100, weight, detail: detail(today, base) }
}

function missingNote(components: ReadinessComponent[]): string {
  const present = new Set(components.map((c) => c.label))
  const missing = ALL_LABELS.filter((label) => !present.has(label))
  if (missing.length === 0) {
    return 'Compared with your own median over the last few weeks. Not a medical assessment.'
  }
  const have = [...present].map((l) => l.toLowerCase()).join(' and ')
  const lack = missing.map((l) => l.toLowerCase()).join(' or ')
  return `Based on ${have}. No baseline yet for ${lack}. Not a medical assessment.`
}

/**
 * A linear ramp between "as bad as this gets" and "as good as this gets", clamped.
 *
 * Works in both directions: pass `at100` below `at0` for a quantity where lower is better,
 * which is what resting heart rate needs.
 */
function ramp(value: number, at0: number, at100: number): number {
  return Math.min(1, Math.max(0, (value - at0) / (at100 - at0)))
}

export function hoursAndMinutes(seconds: number): string {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`
}

/**
 * "4% above normal" — direction in words, because a signed percentage means the opposite thing
 * for heart-rate variability and for resting heart rate, and a reader should not have to
 * remember which. Mirrors `feature/health/Format.deviation`.
 */
export function deviationLabel(percent: number | null): string | null {
  if (percent === null) return null
  const rounded = Math.round(percent)
  if (Math.abs(rounded) < 1) return 'at your normal'
  return rounded > 0 ? `${rounded}% above normal` : `${Math.abs(rounded)}% below normal`
}

/*
 * The bands. Chosen so that "exactly your normal" lands around two thirds rather than at the
 * top: a day that matches the baseline is an ordinary day, not a personal best, and a scale
 * whose midpoint is unreachable is a scale nobody trusts twice.
 */
const HRV_LOW = 0.8
const HRV_HIGH = 1.1
const RESTING_HR_HIGH = 1.06
const RESTING_HR_LOW = 0.94
const SLEEP_LOW = 0.5
const SLEEP_FULL = 1.0

const HRV_WEIGHT = 0.4
const RESTING_HR_WEIGHT = 0.3
const SLEEP_WEIGHT = 0.3

/** Until the athlete can set their own. Mirrors `Readiness.DEFAULT_SLEEP_NEED_SECONDS`. */
export const DEFAULT_SLEEP_NEED_SECONDS = 8 * 3600

const ALL_LABELS = ['Heart-rate variability', 'Resting heart rate', 'Sleep']

/**
 * The kinds this screen reads.
 *
 * The registry that produces them is the phone's — `HealthRecordRegistry.Kind` — and the edge
 * deliberately does not allow-list the slugs, so these two strings are the whole agreement
 * between the uploader and this screen. Getting one wrong shows an empty trend rather than an
 * error, which is why they are named here rather than spelled out at the call site.
 */
export const HRV_KIND = 'hrv_rmssd'
export const RESTING_HR_KIND = 'resting_heart_rate'
