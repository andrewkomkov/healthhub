import { useCallback, useState } from 'react'
import { useMessages, type Bundle } from '../../core/i18n'
import { type User } from '../../core/api/client'
import { useActivityPages } from '../../core/paging'
import { ArchivedCard, type CardState } from './ArchivedCard'
import './archive.css'

const MESSAGES = {
  en: {
    title: "Archive",
    noteTitle: "Nothing here was thrown away",
    restore: "Restore to feed",
    tryAgain: "Try again",
  },
  ru: {
    title: "Архив",
    noteTitle: "Отсюда ничего не выбрасывалось",
    restore: "Вернуть в ленту",
    tryAgain: "Повторить",
  },
} satisfies Bundle<Record<string, string>>


/**
 * Everything Health Connect handed over that is not currently representing its workout.
 *
 * Health Connect is a hub: a single ride arrives from the bike computer's own app, from
 * Strava, and from Google Fit, each with its own idea of the distance. The phone picks one
 * using the athlete's source order and sets the rest aside. This screen is where those live,
 * and where the athlete overrules the choice for one specific workout.
 *
 * There is no destructive action on this screen and there is no route to one, because the
 * model has none: a recording is either representing its workout or waiting here.
 */
export function ArchiveScreen({
  user,
  onOpenActivity,
}: {
  user: User
  onOpenActivity: (id: string) => void
}) {
  const t = useMessages(MESSAGES)
  const [states, setStates] = useState<Record<string, CardState>>({})
  const { activities, loading, error, sentinel, retry } = useActivityPages({
    view: 'archive',
    errorMessage: 'Could not load your archive.',
  })

  const onStateChange = useCallback((id: string, state: CardState) => {
    setStates((prev) => ({ ...prev, [id]: state }))
  }, [])

  return (
    <>
      {/* No back button: this is one of the shell's own destinations now, and a back control
          on a top-level screen points at whichever screen happened to precede it. */}
      <header className="m3-app-bar">
        <h1 className="t-title-large-emphasized">{t.title}</h1>
      </header>

      <div className="m3-page">
        <section className="hh-archive-note" aria-label="What the archive is">
          <h2 className="t-title-medium">{t.noteTitle}</h2>
          <p className="t-body-medium">
            When several apps record the same workout, one of them represents it in your feed
            and the others wait here with everything they measured. Restore any of them and
            that choice sticks — later syncs will not undo it.
          </p>
        </section>

        {error && (
          <div className="m3-error m3-error--actionable" role="alert">
            <p className="t-body-medium">{error}</p>
            <button className="m3-button m3-button--text" onClick={retry}>
              {t.tryAgain}
            </button>
          </div>
        )}

        {activities.map((activity) => (
          <ArchivedCard
            key={activity.id}
            activity={activity}
            units={user.unitSystem}
            state={states[activity.id] ?? 'idle'}
            onOpen={onOpenActivity}
            onStateChange={onStateChange}
          />
        ))}

        {!loading && activities.length === 0 && !error && (
          <div className="m3-empty">
            <h2 className="t-title-large">Your archive is empty</h2>
            <p className="t-body-medium">
              Every workout you have is representing itself in the feed. Recordings appear here
              when two apps report the same session, or when you set one aside by hand.
            </p>
          </div>
        )}

        {loading && <p className="t-body-medium">Loading…</p>}
        <div ref={sentinel} aria-hidden="true" />
      </div>
    </>
  )
}
