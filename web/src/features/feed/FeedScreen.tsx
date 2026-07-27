import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type FeedActivity, type User } from '../../core/api/client'
import { distance, duration, elevation, localDate, paceOrSpeed, sportLabel } from '../../core/format'
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
  onSignOut,
}: {
  user: User
  onOpenActivity: (id: string) => void
  onSignOut: () => void
}) {
  const [activities, setActivities] = useState<FeedActivity[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [exhausted, setExhausted] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const sentinel = useRef<HTMLDivElement>(null)

  const loadMore = useCallback(async () => {
    if (exhausted) return
    setLoading(true)
    try {
      const page = await api.feed({ cursor })
      setActivities((prev) => [...prev, ...page.activities])
      setCursor(page.nextCursor)
      if (!page.nextCursor) setExhausted(true)
      setError(null)
    } catch {
      setError('Could not load your activities.')
    } finally {
      setLoading(false)
    }
  }, [cursor, exhausted])

  useEffect(() => {
    void loadMore()
    // Intentionally runs once: subsequent pages are driven by the scroll sentinel.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const node = sentinel.current
    if (!node || exhausted) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && !loading) void loadMore()
      },
      { rootMargin: '400px' },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [loadMore, loading, exhausted])

  return (
    <>
      <header className="m3-app-bar">
        <h1 className="t-title-large">Activities</h1>
        <button className="m3-button m3-button--text" onClick={onSignOut}>
          Sign out
        </button>
      </header>

      <div className="m3-page">
        {error && (
          <p className="m3-error t-body-medium" role="alert">
            {error}
          </p>
        )}

        {activities.map((activity) => (
          <ActivityCard
            key={activity.id}
            activity={activity}
            units={user.unitSystem}
            onOpen={onOpenActivity}
          />
        ))}

        {!loading && activities.length === 0 && (
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
