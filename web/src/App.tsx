import { lazy, Suspense, useEffect, useState, type ReactNode } from 'react'
import { api, type User } from './core/api/client'
import { AppShell } from './core/m3e/AppShell'
import { detectLocale, LocaleContext } from './core/i18n'
import { applyDynamicTheme } from './core/m3e/dynamicTheme'
import { AuthScreen } from './features/auth/AuthScreen'

/**
 * The detail screen carries MapLibre and µPlot — around three quarters of the bundle. Loading
 * it lazily keeps the feed's first screen small, which is the budget that actually matters:
 * the feed is what opens on a cold cache, and most sessions never leave it.
 */
const ActivityScreen = lazy(() =>
  import('./features/activity/ActivityScreen').then((module) => ({
    default: module.ActivityScreen,
  })),
)

/**
 * Every screen that is not the feed loads the same way, for the same reason. These three are
 * placeholders today, but each is destined to carry something heavy — the archive its own
 * list, sources a drag-and-drop implementation, health a chart stack — and the feed's first
 * screen must not grow when they land.
 */
const ArchiveScreen = lazy(() =>
  import('./features/archive/ArchiveScreen').then((module) => ({
    default: module.ArchiveScreen,
  })),
)

const SourcesScreen = lazy(() =>
  import('./features/sources/SourcesScreen').then((module) => ({
    default: module.SourcesScreen,
  })),
)

const HealthScreen = lazy(() =>
  import('./features/health/HealthScreen').then((module) => ({
    default: module.HealthScreen,
  })),
)
import { FeedScreen } from './features/feed/FeedScreen'

type Route =
  | { name: 'feed' }
  | { name: 'activity'; id: string }
  | { name: 'archive' }
  | { name: 'sources' }
  | { name: 'health' }

const STATIC_ROUTES: Record<string, Route> = {
  '/archive': { name: 'archive' },
  '/sources': { name: 'sources' },
  '/health': { name: 'health' },
}

function routeFromLocation(): Route {
  const path = window.location.pathname
  const match = /^\/activities\/([^/]+)$/.exec(path)
  if (match) return { name: 'activity', id: match[1] as string }
  return STATIC_ROUTES[path] ?? { name: 'feed' }
}

function pathForRoute(route: Route): string {
  switch (route.name) {
    case 'feed':
      return '/'
    case 'activity':
      return `/activities/${route.id}`
    default:
      return `/${route.name}`
  }
}

function Loading() {
  return (
    <div className="m3-page">
      <p className="t-body-medium">Loading…</p>
    </div>
  )
}

function Lazily({ children }: { children: ReactNode }) {
  return <Suspense fallback={<Loading />}>{children}</Suspense>
}

export function App() {
  // Read once, from the browser. There is deliberately no in-app language switch: the phone
  // has none either — it follows the system — and a second place to set a language is a second
  // place for the two clients to disagree about which one an athlete chose.
  const [locale] = useState(detectLocale)
  const [user, setUser] = useState<User | null>(null)
  const [checking, setChecking] = useState(true)
  const [route, setRoute] = useState<Route>(routeFromLocation)

  useEffect(() => {
    api
      .me()
      .then((result) => setUser(result.user))
      .catch(() => setUser(null))
      .finally(() => setChecking(false))
  }, [])

  useEffect(() => {
    const onPop = () => setRoute(routeFromLocation())
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  // Wear the Material You palette the athlete's phone extracted from their wallpaper.
  // Failure is silent by design: the built-in palette is a perfectly good fallback and a
  // missing personalisation is not worth an error message.
  useEffect(() => {
    if (!user) {
      applyDynamicTheme(null)
      return
    }
    api
      .theme()
      .then((result) => applyDynamicTheme(result.theme))
      .catch(() => undefined)
  }, [user])

  function navigate(next: Route) {
    window.history.pushState(null, '', pathForRoute(next))
    setRoute(next)
  }

  if (checking) {
    return (
      <LocaleContext.Provider value={locale}>
        <Loading />
      </LocaleContext.Provider>
    )
  }

  if (!user) {
    return (
      <LocaleContext.Provider value={locale}>
        <AuthScreen onSignedIn={setUser} />
      </LocaleContext.Provider>
    )
  }

  const toFeed = () => navigate({ name: 'feed' })

  // Null on anything below the top level, which is what tells the shell to draw no navigation:
  // an activity has its own way back, and four sideways moves over it are chrome.
  const shellDestination = route.name === 'activity' ? null : route.name

  return (
    <LocaleContext.Provider value={locale}>
      <AppShell
        current={shellDestination}
        onNavigate={(id) => navigate({ name: id } as Route)}
      >
        {screenFor()}
      </AppShell>
    </LocaleContext.Provider>
  )

  function screenFor() {
    switch (route.name) {
      case 'activity':
        return (
          <Lazily>
            <ActivityScreen id={route.id} user={user!} onBack={toFeed} />
          </Lazily>
        )
      case 'archive':
        return (
          <Lazily>
            <ArchiveScreen
              user={user!}
              onOpenActivity={(id) => navigate({ name: 'activity', id })}
            />
          </Lazily>
        )
      case 'sources':
        return (
          <Lazily>
            <SourcesScreen user={user!} />
          </Lazily>
        )
      case 'health':
        return (
          <Lazily>
            <HealthScreen user={user!} />
          </Lazily>
        )
      default:
        return (
          <FeedScreen
            user={user!}
            onOpenActivity={(id) => navigate({ name: 'activity', id })}
            onSignOut={async () => {
              await api.logout().catch(() => undefined)
              setUser(null)
            }}
          />
        )
    }
  }
}
