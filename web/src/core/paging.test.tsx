import { StrictMode, act, createElement, type ReactNode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useActivityPages, type ActivityPages } from './paging'

/**
 * The two hazards this hook exists to hold shut, driven rather than reasoned about.
 *
 * Neither reproduces by hand: the double load only happens under StrictMode, which is
 * development-only, and the retry loop only happens when a request fails while the sentinel is
 * on screen — which is precisely the state a failed *first* page leaves the screen in.
 */

vi.mock('./api/client', () => ({ api: { feed: vi.fn() } }))
const { api } = await import('./api/client')
const feed = vi.mocked(api.feed)

/** Every observer built during a test, so a scroll can be delivered on demand. */
let observers: { callback: IntersectionObserverCallback; disconnected: boolean }[] = []

class StubIntersectionObserver {
  private readonly entry: { callback: IntersectionObserverCallback; disconnected: boolean }
  constructor(callback: IntersectionObserverCallback) {
    this.entry = { callback, disconnected: false }
    observers.push(this.entry)
  }
  // Observing an element already in the viewport delivers an initial notification, which is
  // what makes a re-armed observer fire without anybody scrolling. That is the loop.
  observe() {
    void this.entry
  }
  unobserve() {}
  disconnect() {
    this.entry.disconnected = true
  }
  takeRecords(): IntersectionObserverEntry[] {
    return []
  }
}

/** Delivers "the sentinel is in view" to every observer still connected. */
async function scrollSentinelIntoView(): Promise<void> {
  const live = observers.filter((entry) => !entry.disconnected)
  await act(async () => {
    for (const entry of live) {
      entry.callback(
        [{ isIntersecting: true } as IntersectionObserverEntry],
        null as unknown as IntersectionObserver,
      )
    }
  })
}

function page(ids: string[], nextCursor: string | null) {
  return {
    activities: ids.map((id) => ({ id }) as never),
    nextCursor,
  }
}

let container: HTMLDivElement
let root: Root
let latest: ActivityPages

function Probe() {
  latest = useActivityPages({ errorMessage: 'Could not load your activities.' })
  return createElement('div', { ref: latest.sentinel })
}

async function mount(wrap: (node: ReactNode) => ReactNode = (node) => node): Promise<void> {
  await act(async () => {
    root.render(wrap(createElement(Probe)))
  })
}

beforeEach(() => {
  observers = []
  feed.mockReset()
  vi.stubGlobal('IntersectionObserver', StubIntersectionObserver)
  ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
  vi.unstubAllGlobals()
})

describe('a failed page', () => {
  it('does not re-arm the sentinel, however far it is scrolled', async () => {
    feed.mockRejectedValue(new Error('offline'))
    await mount()

    expect(latest.error).toBe('Could not load your activities.')
    expect(feed).toHaveBeenCalledTimes(1)

    // The sentinel is still in an empty viewport. Before the latch, every one of these
    // delivered another request, and the screen never changed to say so.
    for (let i = 0; i < 5; i += 1) await scrollSentinelIntoView()

    expect(feed).toHaveBeenCalledTimes(1)
  })

  it('asks again exactly once when the athlete does', async () => {
    feed.mockRejectedValue(new Error('offline'))
    await mount()

    feed.mockResolvedValue(page(['a'], null))
    await act(async () => latest.retry())

    expect(feed).toHaveBeenCalledTimes(2)
    expect(latest.error).toBeNull()
    expect(latest.activities).toHaveLength(1)
  })
})

describe('the first page', () => {
  it('is requested once under StrictMode, not twice', async () => {
    feed.mockResolvedValue(page(['a', 'b'], null))
    await mount((node) => createElement(StrictMode, null, node))

    // The defect this guards showed every archived workout twice: both mount effects read the
    // same pre-update state, so neither saw the other's request.
    expect(feed).toHaveBeenCalledTimes(1)
    expect(latest.activities.map((a) => a.id)).toEqual(['a', 'b'])
  })
})

describe('paging', () => {
  it('follows the cursor and stops when the server runs out', async () => {
    feed.mockResolvedValueOnce(page(['a'], 'cursor-1'))
    feed.mockResolvedValueOnce(page(['b'], null))
    await mount()

    expect(latest.activities.map((a) => a.id)).toEqual(['a'])

    await scrollSentinelIntoView()
    expect(feed).toHaveBeenLastCalledWith({ cursor: 'cursor-1', view: undefined })
    expect(latest.activities.map((a) => a.id)).toEqual(['a', 'b'])
    expect(latest.exhausted).toBe(true)

    await scrollSentinelIntoView()
    expect(feed).toHaveBeenCalledTimes(2)
  })

  it('passes the view straight through, so the archive is the same list', async () => {
    feed.mockResolvedValue(page([], null))
    await act(async () => {
      root.render(
        createElement(function ArchiveProbe() {
          latest = useActivityPages({ view: 'archive', errorMessage: 'nope' })
          return createElement('div', { ref: latest.sentinel })
        }),
      )
    })

    expect(feed).toHaveBeenCalledWith({ cursor: null, view: 'archive' })
  })
})
