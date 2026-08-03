import { useMessages, type Bundle } from '../i18n'
import { Icon, type IconName } from './Icon'

/** On the cold-start path, so these four words live in the entry chunk. There are four. */
const MESSAGES = {
  en: { nav: 'Main', feed: 'Activities', health: 'Health', sources: 'Sources', archive: 'Archive' },
  ru: {
    nav: 'Основная навигация',
    feed: 'Тренировки',
    health: 'Здоровье',
    sources: 'Источники',
    archive: 'Архив',
  },
} satisfies Bundle<Record<string, string>>

/**
 * The app's navigation surface.
 *
 * The web client had none. Every screen below the feed was reached from four text buttons in
 * the feed's own app bar, and once you were on one of them the only way anywhere else was the
 * browser's back button — the feed's comment said as much: "plain text buttons until session 2
 * gives these screens a real navigation surface". This is that surface, and it is deliberately
 * the same information architecture as the phone's navigation bar so the two clients are one
 * product rather than two.
 *
 * One component, two shapes, decided in CSS rather than in JavaScript:
 *
 * - **a bar along the bottom** on a narrow viewport, where the reach is at the bottom of the
 *   screen and the horizontal room is gone;
 * - **a rail down the side** on a wide one, because a bottom bar on a desktop puts the
 *   navigation as far from the content as the window allows.
 *
 * Deciding it in a media query rather than from a measured width means there is no first frame
 * in the wrong shape, and no resize listener to leak. The breakpoint is in `rem` on purpose: a
 * breakpoint in `px` does not trip when the reader's *text* grows, which is exactly when the
 * horizontal room runs out — see the note in AGENT-NOTES about type being published in `rem`.
 *
 * Nothing heavy is imported here. This file is on the cold-start path and `budget.test.ts`
 * checks that every file on it stays clear of MapLibre, µPlot, DuckDB and the telemetry codec.
 */

export interface ShellDestination {
  id: keyof (typeof MESSAGES)['en']
  icon: IconName
}

/** The four top-level places, in the order the phone's bar puts them. */
export const SHELL_DESTINATIONS: ShellDestination[] = [
  { id: 'feed', icon: 'activities' },
  { id: 'health', icon: 'health' },
  { id: 'sources', icon: 'sources' },
  { id: 'archive', icon: 'archive' },
]

export function AppShell({
  current,
  onNavigate,
  children,
}: {
  /** The destination id currently on screen, or null on a screen below the top level. */
  current: string | null
  onNavigate: (id: string) => void
  children: React.ReactNode
}) {
  // Below the top level — an activity, say — the screen's own back control is the way out, and
  // a bar offering four sideways moves is chrome over the thing the reader came for.
  const t = useMessages(MESSAGES)

  if (current === null) return <>{children}</>

  return (
    <div className="hh-shell">
      <nav className="hh-nav" aria-label={t.nav}>
        {SHELL_DESTINATIONS.map((destination) => {
          const selected = destination.id === current
          return (
            <button
              key={destination.id}
              type="button"
              className={`hh-nav__item${selected ? ' hh-nav__item--selected' : ''}`}
              // `aria-current`, not `aria-selected`: these are links to pages, and this is the
              // attribute that says "the one you are on" for a page rather than for a tab in a
              // tab list. A screen reader announces it without the label having to say it.
              aria-current={selected ? 'page' : undefined}
              onClick={() => onNavigate(destination.id)}
            >
              <span className="hh-nav__mark">
                <Icon name={destination.icon} size={24} />
              </span>
              <span className="t-label-medium">{t[destination.id]}</span>
            </button>
          )
        })}
      </nav>

      <div className="hh-shell__content">{children}</div>
    </div>
  )
}
