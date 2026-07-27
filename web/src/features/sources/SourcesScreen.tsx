import { useCallback, useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { api, type Source, type User } from '../../core/api/client'
import { sourceLabel } from '../../core/format'
import { SourceRow } from './SourceRow'
import { move, orderChanged, stepTo } from './reorder'
import './sources.css'

/** Long enough that a run of arrow keys is one request, short enough to feel immediate. */
const SAVE_DELAY_MS = 400

type SaveState = 'idle' | 'saving' | 'saved' | 'failed'

/**
 * Which apps the athlete trusts, most trusted first.
 *
 * Health Connect is a hub, and the same ride arrives from the bike computer's own app, from
 * Strava and from Google Fit. The order stored here is what the phone applies on the next sync
 * when it decides which of those recordings represents the workout — this screen is the
 * athlete's answer to "my head unit measured the distance, my phone was in a pocket".
 *
 * Reordering works two ways deliberately. Pointer drag is the obvious one; the handle is also
 * a focusable button that lifts on space and moves on the arrow keys, with every move spoken
 * through a live region. Both paths call the same `move` from `reorder.ts`, so the keyboard
 * path is covered by the same tests as the mouse one.
 *
 * A disabled source is still ingested. Its recordings go to the archive and stay restorable —
 * turning an app off is a statement about whose numbers to believe, not about what to keep.
 */
export function SourcesScreen({ onBack }: { user: User; onBack: () => void }) {
  const [sources, setSources] = useState<Source[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saveState, setSaveState] = useState<SaveState>('idle')
  const [grabbedKey, setGrabbedKey] = useState<string | null>(null)
  const [liftedKey, setLiftedKey] = useState<string | null>(null)
  const [announcement, setAnnouncement] = useState('')

  // The order the server last accepted, and the order at the moment of a keyboard lift.
  const savedRef = useRef<Source[]>([])
  const beforeGrabRef = useRef<Source[]>([])
  const currentRef = useRef<Source[]>([])
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const handlesRef = useRef(new Map<string, HTMLButtonElement>())
  const dragFromRef = useRef<number | null>(null)

  // Mirrored on every render because a drag fires several reorders before React commits any
  // of them; a handler reading `sources` from its closure would move the wrong row.
  currentRef.current = sources

  useEffect(() => {
    api
      .sources()
      .then((result) => {
        setSources(result.sources)
        savedRef.current = result.sources
      })
      .catch(() => setLoadError('Could not load your sources.'))
      .finally(() => setLoading(false))
  }, [])

  const save = useCallback((next: Source[]) => {
    setSaveState('saving')
    api
      .saveSources(next.map((s) => ({ packageName: s.packageName, enabled: s.enabled })))
      .then(() => {
        savedRef.current = next
        setSaveState('saved')
      })
      .catch(() => {
        // Put the list back to what the server actually holds. A screen showing an order that
        // was never stored would send the athlete away believing a change they did not make.
        setSources(savedRef.current)
        setSaveState('failed')
      })
  }, [])

  const scheduleSave = useCallback(
    (next: Source[]) => {
      if (timerRef.current !== null) clearTimeout(timerRef.current)
      setSaveState('saving')
      timerRef.current = setTimeout(() => {
        timerRef.current = null
        save(next)
      }, SAVE_DELAY_MS)
    },
    [save],
  )

  // A pending save must not be lost to a back navigation mid-debounce.
  useEffect(
    () => () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        if (orderChanged(currentRef.current, savedRef.current, (s) => s.packageName)) {
          void api
            .saveSources(
              currentRef.current.map((s) => ({ packageName: s.packageName, enabled: s.enabled })),
            )
            .catch(() => undefined)
        }
      }
    },
    [],
  )

  /**
   * React reorders siblings with `insertBefore`, which drops focus off the moved node — so a
   * lifted handle has to be handed its focus back or the next arrow key goes nowhere.
   */
  useLayoutEffect(() => {
    if (!grabbedKey) return
    handlesRef.current.get(grabbedKey)?.focus()
  }, [grabbedKey, sources])

  const announce = useCallback((message: string) => setAnnouncement(message), [])

  const onHandleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
      const list = currentRef.current
      const source = list[index]
      if (!source) return
      const lifted = grabbedKey === source.packageName
      const name = sourceLabel(source.packageName, source.label)

      if (event.key === ' ' || event.key === 'Enter') {
        // Suppressing the default also suppresses the synthetic click, so a keyboard lift
        // never fires the pointer path as well.
        event.preventDefault()
        if (lifted) {
          setGrabbedKey(null)
          announce(`${name} dropped at position ${index + 1} of ${list.length}.`)
        } else {
          beforeGrabRef.current = list
          setGrabbedKey(source.packageName)
          announce(
            `${name} lifted, position ${index + 1} of ${list.length}. Arrow keys move it, space drops it, escape cancels.`,
          )
        }
        return
      }

      if (event.key === 'Escape' && lifted) {
        event.preventDefault()
        const restored = beforeGrabRef.current
        setSources(restored)
        setGrabbedKey(null)
        scheduleSave(restored)
        announce(`Move cancelled. ${name} is back where it was.`)
        return
      }

      if ((event.key === 'ArrowUp' || event.key === 'ArrowDown') && lifted) {
        event.preventDefault()
        const target = stepTo(index, event.key === 'ArrowUp' ? -1 : 1, list.length)
        if (target === index) {
          announce(`${name} is already at position ${index + 1} of ${list.length}.`)
          return
        }
        const next = move(list, index, target)
        setSources(next)
        scheduleSave(next)
        announce(`${name}, position ${target + 1} of ${next.length}.`)
      }
    },
    [grabbedKey, announce, scheduleSave],
  )

  const onToggle = useCallback(
    (index: number) => {
      const list = currentRef.current
      const source = list[index]
      if (!source) return
      const next = list.map((entry, i) =>
        i === index ? { ...entry, enabled: !entry.enabled } : entry,
      )
      setSources(next)
      scheduleSave(next)
      const name = sourceLabel(source.packageName, source.label)
      announce(
        source.enabled
          ? `${name} turned off. Its recordings move to the archive and stay restorable.`
          : `${name} turned on.`,
      )
    },
    [announce, scheduleSave],
  )

  const onDragEnter = useCallback(
    (index: number) => {
      const from = dragFromRef.current
      if (from === null || from === index) return
      const next = move(currentRef.current, from, index)
      dragFromRef.current = index
      setSources(next)
    },
    [],
  )

  const onDragEnd = useCallback(() => {
    dragFromRef.current = null
    setLiftedKey(null)
    const next = currentRef.current
    if (orderChanged(next, savedRef.current, (s) => s.packageName)) {
      scheduleSave(next)
      announce('Order updated.')
    }
  }, [announce, scheduleSave])

  // A press that never became a drag still left the row draggable; take that back.
  useEffect(() => {
    const clear = () => setLiftedKey(null)
    window.addEventListener('pointerup', clear)
    return () => window.removeEventListener('pointerup', clear)
  }, [])

  const statusText = () => {
    switch (saveState) {
      case 'saving':
        return 'Saving…'
      case 'saved':
        return 'Saved. The next sync will apply it.'
      case 'failed':
        return null
      default:
        return 'Your phone applies this order the next time it syncs.'
    }
  }

  return (
    <>
      <header className="m3-app-bar">
        <button className="m3-button m3-button--text" onClick={onBack}>
          ← Activities
        </button>
        <h1 className="t-title-large">Sources</h1>
      </header>

      <div className="m3-page">
        <section className="hh-sources-note" aria-label="How source priority works">
          <h2 className="t-title-medium">Whose numbers do you believe?</h2>
          <p className="t-body-medium">
            When several apps record the same workout, the one highest in this list represents
            it in your feed. The others wait in the archive with everything they measured, and
            you can restore any of them for a single workout at any time.
          </p>
          <p className="t-body-small">
            Drag a source to move it, or focus its handle and press space, then use the arrow
            keys.
          </p>
        </section>

        {loadError && (
          <p className="m3-error t-body-medium" role="alert">
            {loadError}
          </p>
        )}

        {saveState === 'failed' && (
          <p className="m3-error t-body-medium" role="alert">
            That change did not reach the server, so the list above is what is actually stored.
            Try again.
          </p>
        )}

        {loading ? (
          <p className="t-body-medium">Loading…</p>
        ) : sources.length === 0 ? (
          <div className="m3-empty">
            <h2 className="t-title-large">No sources yet</h2>
            <p className="t-body-medium">
              Apps appear here once your phone has seen them writing to Health Connect. Run a
              sync and come back.
            </p>
          </div>
        ) : (
          <>
            <ol className="hh-sources-list" aria-label="Sources, most trusted first">
              {sources.map((source, index) => (
                <SourceRow
                  key={source.packageName}
                  source={source}
                  index={index}
                  total={sources.length}
                  grabbed={grabbedKey === source.packageName}
                  draggable={liftedKey === source.packageName}
                  handleRef={(node) => {
                    if (node) handlesRef.current.set(source.packageName, node)
                    else handlesRef.current.delete(source.packageName)
                  }}
                  onHandleKeyDown={onHandleKeyDown}
                  onLiftStart={() => setLiftedKey(source.packageName)}
                  onToggle={onToggle}
                  onDragStart={(from) => {
                    dragFromRef.current = from
                  }}
                  onDragEnter={onDragEnter}
                  onDragEnd={onDragEnd}
                />
              ))}
            </ol>

            <div className="hh-sources-status">
              <span className="t-body-small">{statusText()}</span>
              <span className="t-body-small">
                A source you turn off is still ingested — its recordings go to the archive.
              </span>
            </div>
          </>
        )}

        {/* Every reorder is spoken here — the visual list moving is not an event a screen
            reader can observe on its own. */}
        <p className="hh-visually-hidden" role="status" aria-live="polite">
          {announcement}
        </p>
      </div>
    </>
  )
}
