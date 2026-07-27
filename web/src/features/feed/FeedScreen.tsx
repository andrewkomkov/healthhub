import { type FeedActivity, type User } from '../../core/api/client'
import { distance, duration, elevation, localDate, paceOrSpeed, sportLabel } from '../../core/format'
import { useActivityPages } from '../../core/paging'
import { RouteThumbnail } from './RouteThumbnail'

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <span className="t-label-medium">{label}</span>
      <span className="t-title-medium numeric">{value}</span>
    </div>
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

      <div
        style={{
          padding: 'var(--hh-space-lg)',
          display: 'flex',
          flexDirection: 'column',
          gap: 'var(--hh-space-md)',
        }}
      >
        <header style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span className="t-label-medium">
            {sportLabel(activity.sport)} · {localDate(activity.startTime, activity.tzOffsetMinutes)}
          </span>
          <h2 className="t-title-large">{activity.title}</h2>
        </header>

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(84px, 1fr))',
            gap: 'var(--hh-space-lg)',
          }}
        >
          <Stat label="Distance" value={distance(activity.distanceM, units)} />
          <Stat
            label="Time"
            value={duration(activity.movingSeconds ?? activity.elapsedSeconds)}
          />
          <Stat
            label="Pace"
            value={paceOrSpeed(activity.avgSpeedMps, activity.sport, units)}
          />
          {activity.elevationGainM !== null && (
            <Stat label="Elevation" value={elevation(activity.elevationGainM, units)} />
          )}
          {activity.avgHrBpm !== null && (
            <Stat label="Avg HR" value={`${activity.avgHrBpm} bpm`} />
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
              Install the HealthHub app on your Android phone, sign in with this account, and
              grant it access to Health Connect. Your workouts will appear here.
            </p>
          </div>
        )}

        {loading && <p className="t-body-medium">Loading…</p>}
        <div ref={sentinel} aria-hidden="true" />
      </div>
    </>
  )
}
