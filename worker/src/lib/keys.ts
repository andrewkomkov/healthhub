/**
 * R2 key construction.
 *
 * Keys are partitioned by the activity's *local* start date so a month's prefix matches what
 * the athlete would call that month, prefixes prune for batch jobs, and lifecycle rules stay
 * expressible (R-013). Every key begins with the owning user, which makes account deletion a
 * prefix sweep and makes an orphaned key impossible to misattribute.
 */

export type TelemetryVariant = 'full' | 'preview'

function localParts(
  startTime: number,
  tzOffsetMinutes: number,
): {
  year: string
  month: string
  day: string
} {
  const local = new Date(startTime + tzOffsetMinutes * 60_000)
  return {
    year: String(local.getUTCFullYear()),
    month: String(local.getUTCMonth() + 1).padStart(2, '0'),
    day: String(local.getUTCDate()).padStart(2, '0'),
  }
}

/**
 * The athlete's local calendar day as `YYYY-MM-DD`.
 *
 * Same arithmetic as the partition above, exposed because daily-grain rows store the day they
 * belong to. Shifting an instant by a recorded offset is date bucketing, not analysis — the
 * Worker still derives no health figure from it.
 */
export function localDate(atMs: number, tzOffsetMinutes: number): string {
  const { year, month, day } = localParts(atMs, tzOffsetMinutes)
  return `${year}-${month}-${day}`
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

/**
 * A night's hypnogram — the stage interval list.
 *
 * Partitioned by the *wake* instant so the object sits in the same month the night is named
 * for. One object per night; the night's summary and stage totals stay in D1.
 */
export function sleepStagesKey(
  userId: string,
  sleepId: string,
  endTime: number,
  tzOffsetMinutes: number,
): string {
  const { year, month } = localParts(endTime, tzOffsetMinutes)
  return `u/${userId}/health/sleep/year=${year}/month=${month}/${sleepId}.json`
}

export function userPrefix(userId: string): string {
  return `u/${userId}/`
}

/** Datasets in the analytical tier. `samples` is reserved — see migration 0007. */
export type ArchiveDataset = 'activities' | 'samples'

/**
 * The root of the analytical tier, and the only prefix an athlete's scoped R2 credentials
 * are ever issued against.
 */
export function archivePrefix(userId: string): string {
  return `u/${userId}/archive/`
}

/**
 * A month's Hive-partitioned prefix: `year=` and `month=` are the partition columns a query
 * engine reads straight out of the path, so `WHERE year = 2026` prunes without opening a file.
 */
export function archiveMonthPrefix(
  userId: string,
  dataset: ArchiveDataset,
  year: number,
  month: number,
): string {
  const mm = String(month).padStart(2, '0')
  return `${archivePrefix(userId)}${dataset}/year=${year}/month=${mm}/`
}

/**
 * Part names are stable and dense — `part-0001` upwards, no generation in the name — so the
 * documented glob keeps working and a rebuild overwrites part for part instead of piling a
 * second copy of the month next to the first.
 */
export function archivePartKey(monthPrefix: string, partIndex: number): string {
  return `${monthPrefix}part-${String(partIndex).padStart(4, '0')}.parquet`
}
