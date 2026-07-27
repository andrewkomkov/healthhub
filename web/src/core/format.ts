/** Presentation helpers. Nothing here computes a metric — it only renders one. */

export type UnitSystem = 'metric' | 'imperial'

export function distance(metres: number | null, units: UnitSystem): string {
  if (metres === null) return '—'
  if (units === 'imperial') return `${(metres / 1609.344).toFixed(2)} mi`
  return `${(metres / 1000).toFixed(2)} km`
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
): string {
  if (!metresPerSecond || metresPerSecond <= 0) return '—'

  const speedSports = new Set(['cycling', 'ebiking', 'rowing', 'swimming', 'skiing', 'skating'])
  if (speedSports.has(sport)) {
    const value = units === 'imperial' ? metresPerSecond * 2.236936 : metresPerSecond * 3.6
    return `${value.toFixed(1)} ${units === 'imperial' ? 'mph' : 'km/h'}`
  }

  const perUnit = units === 'imperial' ? 1609.344 : 1000
  const secondsPerUnit = perUnit / metresPerSecond
  const m = Math.floor(secondsPerUnit / 60)
  const s = Math.round(secondsPerUnit % 60)
  const carry = s === 60
  return `${carry ? m + 1 : m}:${String(carry ? 0 : s).padStart(2, '0')} /${units === 'imperial' ? 'mi' : 'km'}`
}

export function elevation(metres: number | null, units: UnitSystem): string {
  if (metres === null) return '—'
  return units === 'imperial' ? `${Math.round(metres * 3.28084)} ft` : `${Math.round(metres)} m`
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

export function sportLabel(sport: string): string {
  return sport.charAt(0).toUpperCase() + sport.slice(1).replace(/[_-]/g, ' ')
}
