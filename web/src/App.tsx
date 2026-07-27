import { useEffect, useState } from 'react'
import { api, type User } from './core/api/client'
import { AuthScreen } from './features/auth/AuthScreen'
import { FeedScreen } from './features/feed/FeedScreen'

type Route = { name: 'feed' } | { name: 'activity'; id: string }

function routeFromLocation(): Route {
  const match = /^\/activities\/([^/]+)$/.exec(window.location.pathname)
  return match ? { name: 'activity', id: match[1] as string } : { name: 'feed' }
}

export function App() {
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

  function navigate(next: Route) {
    const path = next.name === 'feed' ? '/' : `/activities/${next.id}`
    window.history.pushState(null, '', path)
    setRoute(next)
  }

  if (checking) {
    return (
      <div className="m3-page">
        <p className="t-body-medium">Loading…</p>
      </div>
    )
  }

  if (!user) return <AuthScreen onSignedIn={setUser} />

  if (route.name === 'activity') {
    // The detail surface — map, charts, splits — lands with User Story 3.
    return (
      <div className="m3-page">
        <button className="m3-button m3-button--text" onClick={() => navigate({ name: 'feed' })}>
          Back to activities
        </button>
        <div className="m3-empty">
          <h2 className="t-title-large">Activity detail is on its way</h2>
          <p className="t-body-medium">
            The map, multi-series charts and splits for this activity arrive with the next
            slice of work.
          </p>
        </div>
      </div>
    )
  }

  return (
    <FeedScreen
      user={user}
      onOpenActivity={(id) => navigate({ name: 'activity', id })}
      onSignOut={async () => {
        await api.logout().catch(() => undefined)
        setUser(null)
      }}
    />
  )
}
