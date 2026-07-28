import { type FeedActivity, type User } from '../../core/api/client'
import {
  distance,
  duration,
  elevation,
  localDate,
  paceOrSpeed,
  sportLabel,
} from '../../core/format'
import { useActivityPages } from '../../core/paging'
import { Icon, type IconName } from '../../core/m3e/Icon'
import { RouteThumbnail } from './RouteThumbnail'

/** A figure with its icon. No chip container: a feed of them would be a wall of outlines. */
function Metric({ icon, value }: { icon: IconName; value: string }) {
  return (
    <span className="hh-metric">
      <Icon name={icon} />
      <span className="t-label-large numeric">{value}</span>
    </span>
  )
}

function ActivityCard({
  activity,
  units,
  onOpen,
}: {
  activity: FeedActivity
  units: User['unitSystem']
  onOpen: (id: string) => void
}) {
  return (
    <article
      className="m3-card m3-card--interactive"
      onClick={() => onOpen(activity.id)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onOpen(activity.id)
        }
      }}
      tabIndex={0}
      role="button"
      aria-label={`${activity.title}, ${sportLabel(activity.sport)}`}
    >
      {activity.routePolyline && <RouteThumbnail polyline={activity.routePolyline} />}

      {/* The card leads with the figure, not the label. Five columns of "Distance / Time / Pace"
          with the values underneath gave every number the same weight and made the card a small
          table; an athlete scrolling a month of walks reads the distance and the date, and reads
          the rest only when one of them looks unusual. Same hierarchy as the phone's card. */}
      <div className="hh-feed-card">
        <header className="hh-feed-card__head">
          <span className="t-title-large-emphasized numeric">
            {distance(activity.distanceM, units)}
          </span>
          <span className="t-label-medium hh-feed-card__when">
            {localDate(activity.startTime, activity.tzOffsetMinutes)}
          </span>
          {activity.hasGps && (
            <span className="hh-feed-card__mark" title="Has a route">
              <Icon name="map" />
            </span>
          )}
        </header>

        {/* Health Connect writes no title of its own, so the sport is stored as one and the line
            read "Walking · Walking". The title is only worth its own words when somebody — the
            athlete, or the app that recorded it — actually wrote one. */}
        <p className="t-body-medium hh-feed-card__title">
          {activity.title.toLowerCase() === activity.sport.toLowerCase()
            ? sportLabel(activity.sport)
            : `${sportLabel(activity.sport)} · ${activity.title}`}
        </p>

        <div className="hh-metrics">
          <Metric
            icon="timer"
            value={duration(activity.movingSeconds ?? activity.elapsedSeconds)}
          />
          <Metric icon="speed" value={paceOrSpeed(activity.avgSpeedMps, activity.sport, units)} />
          {activity.avgHrBpm !== null && <Metric icon="heart" value={`${activity.avgHrBpm} bpm`} />}
          {activity.elevationGainM !== null && activity.elevationGainM >= 1 && (
            <Metric icon="terrain" value={`+${elevation(activity.elevationGainM, units)}`} />
          )}
        </div>
      </div>
    </article>
  )
}

export function FeedScreen({
  user,
  onOpenActivity,
  onOpenArchive,
  onOpenSources,
  onOpenHealth,
  onSignOut,
}: {
  user: User
  onOpenActivity: (id: string) => void
  onOpenArchive: () => void
  onOpenSources: () => void
  onOpenHealth: () => void
  onSignOut: () => void
}) {
  const { activities, loading, error, sentinel, retry } = useActivityPages({
    errorMessage: 'Could not load your activities.',
  })

  return (
    <>
      <header className="m3-app-bar">
        <h1 className="t-title-large">Activities</h1>
        {/* Plain text buttons until session 2 gives these screens a real navigation surface;
            a route nothing links to may as well not exist. */}
        <nav style={{ display: 'flex', gap: 'var(--hh-space-xs)' }}>
          <button className="m3-button m3-button--text" onClick={onOpenHealth}>
            Health
          </button>
          <button className="m3-button m3-button--text" onClick={onOpenArchive}>
            Archive
          </button>
          <button className="m3-button m3-button--text" onClick={onOpenSources}>
            Sources
          </button>
          <button className="m3-button m3-button--text" onClick={onSignOut}>
            Sign out
          </button>
        </nav>
      </header>

      <div className="m3-page">
        {error && (
          <div className="m3-error m3-error--actionable" role="alert">
            <p className="t-body-medium">{error}</p>
            <button className="m3-button m3-button--text" onClick={retry}>
              Try again
            </button>
          </div>
        )}

        {activities.map((activity) => (
          <ActivityCard
            key={activity.id}
            activity={activity}
            units={user.unitSystem}
            onOpen={onOpenActivity}
          />
        ))}

        {/* A failed first page is not an empty history, and saying so would be a small lie
            told to someone whose connection dropped. */}
        {!loading && !error && activities.length === 0 && (
          <div className="m3-empty">
            <h2 className="t-title-large">No activities yet</h2>
            <p className="t-body-medium">
              Install the HealthHub app on your Android phone, sign in with this account, and grant
              it access to Health Connect. Your workouts will appear here.
            </p>
          </div>
        )}

        {loading && <p className="t-body-medium">Loading…</p>}
        <div ref={sentinel} aria-hidden="true" />
      </div>
    </>
  )
}
