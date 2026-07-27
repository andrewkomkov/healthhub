import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import { api, type FeedActivity } from './api/client'

/**
 * Cursor paging over `GET /api/activities`, driven by a scroll sentinel.
 *
 * The feed and the archive are the same list read through different `view` values, and they
 * were written twice — once each, by different sessions, with the second copied from the
 * first. Both copies carried the same two hazards, which is the argument for there being one
 * of this rather than a shared shape everyone reimplements:
 *
 * **A ref guards concurrency, not `loading`.** StrictMode double-invokes a mount effect, both
 * calls read the same pre-update state, and both append their page — the archive showed every
 * workout twice. Production is unaffected, which is why it survives review. A ref updates
 * synchronously, which is the entire reason to reach for one.
 *
 * **A failure has to stop the sentinel, not merely report itself.** The sentinel sits below
 * the last card, so a first page that fails leaves it squarely in an empty viewport. Re-arming
 * the observer on the next render then turns one dropped connection into a request loop that
 * runs as fast as the requests can fail — silently, because the screen is already showing its
 * error and nothing about it changes. Paging therefore latches off until the athlete asks
 * again, and the screen owes them a button that does.
 */

export type FeedView = 'active' | 'archive' | 'all'

export interface ActivityPages {
  activities: FeedActivity[]
  /** True while a page is in flight, the first one included. */
  loading: boolean
  /** Set by a failed page. Paging stays suspended until `retry` is called. */
  error: string | null
  /** The server has no further pages. */
  exhausted: boolean
  /** Put on an element below the last card; scrolling it into view fetches the next page. */
  sentinel: RefObject<HTMLDivElement | null>
  /** Clears the error and asks for the same page again. No-op unless one failed. */
  retry: () => void
}

/** How far below the fold a page starts loading. */
const PREFETCH_MARGIN = '400px'

export function useActivityPages(options: {
  /** Omitted means the Worker's default, `active` — the representative of each workout. */
  view?: FeedView
  /** What the screen says when a page fails. The list does not know what it is a list of. */
  errorMessage: string
}): ActivityPages {
  const { view, errorMessage } = options

  const [activities, setActivities] = useState<FeedActivity[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [exhausted, setExhausted] = useState(false)

  const sentinel = useRef<HTMLDivElement | null>(null)
  const cursor = useRef<string | null>(null)
  const inFlight = useRef(false)
  /** Latched by exhaustion or by a failure. Only a failure can be un-latched. */
  const halted = useRef(false)

  const load = useCallback(async () => {
    if (inFlight.current || halted.current) return
    inFlight.current = true
    setLoading(true)
    try {
      const page = await api.feed({ cursor: cursor.current, view })
      cursor.current = page.nextCursor
      setActivities((previous) => [...previous, ...page.activities])
      if (!page.nextCursor) {
        halted.current = true
        setExhausted(true)
      }
      setError(null)
    } catch {
      halted.current = true
      setError(errorMessage)
    } finally {
      inFlight.current = false
      setLoading(false)
    }
  }, [errorMessage, view])

  const started = useRef(false)
  useEffect(() => {
    if (started.current) return
    started.current = true
    void load()
  }, [load])

  useEffect(() => {
    const node = sentinel.current
    // `loading` is in the dependencies rather than the callback so that the observer is rebuilt
    // once a page has landed: a short page leaves the sentinel on screen, and the fresh
    // observer's initial notification is what asks for the next one.
    if (!node || exhausted || error !== null || loading) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) void load()
      },
      { rootMargin: PREFETCH_MARGIN },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [error, exhausted, load, loading])

  const retry = useCallback(() => {
    if (inFlight.current || exhausted) return
    halted.current = false
    setError(null)
    void load()
  }, [exhausted, load])

  return { activities, loading, error, exhausted, sentinel, retry }
}
