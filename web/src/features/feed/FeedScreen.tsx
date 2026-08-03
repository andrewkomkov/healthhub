import { type FeedActivity, type User } from '../../core/api/client'
import {
  distance,
  duration,
  elevation,
  heartRate,
  localDate,
  paceOrSpeed,
  sportLabel,
} from '../../core/format'
import { interpolate } from '../../core/i18n'
import { useMessages, useLocale, type Bundle } from '../../core/i18n'
import { unitLabels } from '../../core/format'
import { useActivityPages } from '../../core/paging'

/**
 * On the cold-start path: the feed is what opens on a cold cache, so these strings are in the
 * entry chunk by construction. Every screen below it declares its own bundle and gets its own.
 */
const MESSAGES = {
  en: {
    title: 'Activities',
    signOut: 'Sign out',
    tryAgain: 'Try again',
    loading: 'Loading…',
    emptyTitle: 'No activities yet',
    emptyBody:
      'Install the HealthHub app on your Android phone, sign in with this account, and grant it access to Health Connect. Your workouts will appear here.',
    hasRoute: 'Has a route',
    cardDescription: '%1$s, %2$s. %3$s.',
  },
  ru: {
    title: 'Тренировки',
    signOut: 'Выйти',
    tryAgain: 'Повторить',
    loading: 'Загружаем…',
    emptyTitle: 'Тренировок пока нет',
    emptyBody:
      'Установите приложение HealthHub на телефон с Android, войдите в этот аккаунт и выдайте ему доступ к Health Connect. Ваши тренировки появятся здесь.',
    hasRoute: 'Есть трек',
    cardDescription: '%1$s, %2$s. %3$s.',
  },
} satisfies Bundle<Record<string, string>>

/** The paging hook takes a sentence; it is the same one on the feed and in the archive. */
const ERRORS = {
  en: { feed: 'Could not load your activities.', archive: 'Could not load your archive.' },
  ru: { feed: 'Не удалось загрузить тренировки.', archive: 'Не удалось загрузить архив.' },
} satisfies Bundle<Record<string, string>>

export { ERRORS as PAGING_ERRORS }
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
  const t = useMessages(MESSAGES)
  const locale = useLocale()
  // Resolved once per card rather than per figure, the same as the phone's card: this runs for
  // every row of a list somebody scrolls a month of.
  const labels = unitLabels(locale)

  const sport = sportLabel(activity.sport, locale)
  const heading =
    activity.title.toLowerCase() === activity.sport.toLowerCase()
      ? sport
      : `${sport} · ${activity.title}`

  return (
    /*
     * A real `button`, not an `article` wearing `role="button"`.
     *
     * The old form re-implemented three things the element already does — focusability, the
     * Enter/Space handler, and the role — and the role it declared contradicted the element it
     * was on. The label now carries the figures a sighted reader takes from the card at a
     * glance, in the same order and the same words as the phone's card, rather than only the
     * title and the sport.
     */
    <button
      type="button"
      className="m3-card m3-card--interactive m3-card--button"
      onClick={() => onOpen(activity.id)}
      aria-label={interpolate(
        t.cardDescription,
        heading,
        localDate(activity.startTime, activity.tzOffsetMinutes),
        distance(activity.distanceM, units, labels),
      )}
    >
      {activity.routePolyline && <RouteThumbnail polyline={activity.routePolyline} />}

      {/* The card leads with the figure, not the label. Five columns of "Distance / Time / Pace"
          with the values underneath gave every number the same weight and made the card a small
          table; an athlete scrolling a month of walks reads the distance and the date, and reads
          the rest only when one of them looks unusual. Same hierarchy as the phone's card. */}
      <div className="hh-feed-card">
        <header className="hh-feed-card__head">
          <span className="t-title-large-emphasized numeric">
            {distance(activity.distanceM, units, labels)}
          </span>
          <span className="t-label-medium hh-feed-card__when">
            {localDate(activity.startTime, activity.tzOffsetMinutes)}
          </span>
          {activity.hasGps && (
            <span className="hh-feed-card__mark" title={t.hasRoute}>
              <Icon name="map" />
            </span>
          )}
        </header>

        {/* Health Connect writes no title of its own, so the sport is stored as one and the line
            read "Walking · Walking". The title is only worth its own words when somebody — the
            athlete, or the app that recorded it — actually wrote one. */}
        <p className="t-body-medium hh-feed-card__title">{heading}</p>

        <div className="hh-metrics">
          <Metric
            icon="timer"
            value={duration(activity.movingSeconds ?? activity.elapsedSeconds)}
          />
          <Metric
            icon="speed"
            value={paceOrSpeed(activity.avgSpeedMps, activity.sport, units, labels)}
          />
          {activity.avgHrBpm !== null && (
            <Metric icon="heart" value={heartRate(activity.avgHrBpm, labels)} />
          )}
          {activity.elevationGainM !== null && activity.elevationGainM >= 1 && (
            <Metric icon="terrain" value={`+${elevation(activity.elevationGainM, units, labels)}`} />
          )}
        </div>
      </div>
    </button>
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
  const t = useMessages(MESSAGES)
  const { activities, loading, error, sentinel, retry } = useActivityPages({
    errorMessage: useMessages(ERRORS).feed,
  })

  return (
    <>
      <header className="m3-app-bar">
        <h1 className="t-title-large-emphasized">{t.title}</h1>
        {/* Health, Archive and Sources used to be text buttons here, because there was no
            navigation surface and a route nothing links to may as well not exist. There is one
            now — `AppShell` — and it is on every top-level screen rather than only on this one.
            Sign out stays: it belongs to the account, not to a destination. */}
        <button className="m3-button m3-button--text" onClick={onSignOut}>
          {t.signOut}
        </button>
      </header>

      <div className="m3-page">
        {error && (
          <div className="m3-error m3-error--actionable" role="alert">
            <p className="t-body-medium">{error}</p>
            <button className="m3-button m3-button--text" onClick={retry}>
              {t.tryAgain}
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
            <h2 className="t-title-large">{t.emptyTitle}</h2>
            <p className="t-body-medium">{t.emptyBody}</p>
          </div>
        )}

        {loading && <p className="t-body-medium">{t.loading}</p>}
        <div ref={sentinel} aria-hidden="true" />
      </div>
    </>
  )
}
