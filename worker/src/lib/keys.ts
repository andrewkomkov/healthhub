/**
 * R2 key construction.
 *
 * Keys are partitioned by the activity's *local* start date so a month's prefix matches what
 * the athlete would call that month, prefixes prune for batch jobs, and lifecycle rules stay
 * expressible (R-013). Every key begins with the owning user, which makes account deletion a
 * prefix sweep and makes an orphaned key impossible to misattribute.
 */

export type TelemetryVariant = 'full' | 'preview'

function localParts(startTime: number, tzOffsetMinutes: number): { year: string; month: string } {
  const local = new Date(startTime + tzOffsetMinutes * 60_000)
  return {
    year: String(local.getUTCFullYear()),
    month: String(local.getUTCMonth() + 1).padStart(2, '0'),
  }
}

export function activityPrefix(
  userId: string,
  activityId: string,
  startTime: number,
  tzOffsetMinutes: number,
): string {
  const { year, month } = localParts(startTime, tzOffsetMinutes)
  return `u/${userId}/activities/year=${year}/month=${month}/${activityId}`
}

export function telemetryKey(
  userId: string,
  activityId: string,
  startTime: number,
  tzOffsetMinutes: number,
  variant: TelemetryVariant,
): string {
  return `${activityPrefix(userId, activityId, startTime, tzOffsetMinutes)}/${variant}.hht`
}

export function userPrefix(userId: string): string {
  return `u/${userId}/`
}
