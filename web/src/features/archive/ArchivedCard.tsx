import { api, type FeedActivity, type User } from '../../core/api/client'
import { useMessages, type Bundle } from '../../core/i18n'
import {
  distance,
  duration,
  elevation,
  localDate,
  paceOrSpeed,
  sourceLabel,
  sportLabel,
} from '../../core/format'
import { alsoRecordedBy, archivedBecause, lockNote } from './labels'

const MESSAGES = {
  en: {
    restore: "Restore to feed",
    restoring: "Restoring…",
  },
  ru: {
    restore: "Вернуть в ленту",
    restoring: "Возвращаем…",
  },
} satisfies Bundle<Record<string, string>>


export type CardState = 'idle' | 'working' | 'restored' | 'failed'

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="hh-stat">
      <span className="t-label-medium">{label}</span>
      <span className="t-title-medium numeric">{value}</span>
    </div>
  )
}

/**
 * One recording that is not currently representing its workout.
 *
 * Restoring is a `PATCH` that flips visibility and locks it. The card then stays exactly
 * where it is, showing what happened and offering the way back, because a row that
 * disappeared would look like the thing the athlete was promised never happens.
 */
export function ArchivedCard({
  activity,
  units,
  state,
  onOpen,
  onStateChange,
}: {
  activity: FeedActivity
  units: User['unitSystem']
  state: CardState
  onOpen: (id: string) => void
  onStateChange: (id: string, state: CardState) => void
}) {
  const t = useMessages(MESSAGES)
  const restored = state === 'restored'
  const busy = state === 'working'
  const alsoBy = alsoRecordedBy(activity.sourceCount)
  const lock = lockNote(activity.visibilityLocked || restored)

  async function setVisibility(visibility: 'active' | 'archived') {
    onStateChange(activity.id, 'working')
    try {
      await api.setVisibility(activity.id, visibility)
      onStateChange(activity.id, visibility === 'active' ? 'restored' : 'idle')
    } catch {
      onStateChange(activity.id, 'failed')
    }
  }

  return (
    <article
      className={`m3-card hh-archive-card${restored ? ' hh-archive-card--restored' : ''}`}
      aria-label={`${activity.title}, ${sportLabel(activity.sport)}`}
    >
      <div className="hh-archive-card__head">
        <span className="t-label-medium">
          {sportLabel(activity.sport)} · {localDate(activity.startTime, activity.tzOffsetMinutes)}
        </span>
        <button className="hh-archive-card__title t-title-large" onClick={() => onOpen(activity.id)}>
          {activity.title}
        </button>
      </div>

      <div className="hh-archive-card__reason">
        <span className="hh-badge">{sourceLabel(activity.sourcePackage)}</span>
        {alsoBy && <span className="hh-badge hh-badge--accent">{alsoBy}</span>}
        <span className="t-body-small">{archivedBecause(activity.archivedReason)}</span>
      </div>

      <div className="hh-stats-grid">
        <Stat label="Distance" value={distance(activity.distanceM, units)} />
        <Stat label="Time" value={duration(activity.movingSeconds ?? activity.elapsedSeconds)} />
        <Stat
          label="Pace"
          value={paceOrSpeed(activity.avgSpeedMps, activity.sport, units)}
        />
        {activity.elevationGainM !== null && (
          <Stat label="Elevation" value={elevation(activity.elevationGainM, units)} />
        )}
        {activity.avgHrBpm !== null && <Stat label="Avg HR" value={`${activity.avgHrBpm} bpm`} />}
      </div>

      {state === 'failed' && (
        <p className="m3-error t-body-medium" role="alert">
          That did not reach the server. Your archive is unchanged — try again.
        </p>
      )}

      <div className="hh-archive-card__actions">
        <span className="hh-archive-card__status t-body-small">
          {restored ? 'Back in your feed. Later syncs will leave it there.' : lock}
        </span>

        {restored ? (
          <button
            className="m3-button m3-button--text"
            disabled={busy}
            onClick={() => void setVisibility('archived')}
          >
            Undo
          </button>
        ) : (
          <button
            className="m3-button m3-button--tonal"
            disabled={busy}
            onClick={() => void setVisibility('active')}
          >
            {busy ? t.restoring : t.restore}
          </button>
        )}
      </div>
    </article>
  )
}
