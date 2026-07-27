import type { ArchivedReason } from '../../core/api/client'

/**
 * The words the archive uses.
 *
 * Kept out of the component because the phrasing is the feature: an athlete opening this
 * screen has to come away certain that nothing was thrown away, only set aside. Every string
 * below is written for that, and the accompanying test holds the whole feature to it.
 */

/**
 * "Also recorded by N other apps", or nothing at all.
 *
 * `sourceCount` counts every app that recorded the workout, this recording included, so the
 * interesting number is one less. A count of one means no other app saw it — there is nothing
 * to say, and saying "recorded by 0 other apps" would be noise on every card.
 */
export function alsoRecordedBy(sourceCount: number): string | null {
  const others = Math.max(0, Math.round(sourceCount) - 1)
  if (others === 0) return null
  return others === 1 ? 'Also recorded by 1 other app' : `Also recorded by ${others} other apps`
}

/** Why this recording is set aside, in the athlete's terms rather than the column's. */
export function archivedBecause(reason: ArchivedReason): string {
  switch (reason) {
    case 'duplicate':
      return 'Set aside automatically — a source you trust more recorded the same workout'
    case 'manual':
      return 'You set this one aside yourself'
    default:
      return 'Set aside'
  }
}

/**
 * Whether a locked decision is worth mentioning.
 *
 * Locked-and-archived is the athlete's own choice and already explained by `archivedBecause`.
 * Locked matters most on the way back: it is the promise that a restored workout stays
 * restored however many times the phone syncs afterwards.
 */
export function lockNote(locked: boolean): string | null {
  return locked ? 'Your decision, kept through every future sync' : null
}
