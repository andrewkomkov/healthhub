import { useEffect, useState } from 'react'
import { api, type ActivityDetail } from '../../core/api/client'
import { decompressIfNeeded, readHht, type HhtObject } from '../../core/telemetry/hht'

export type Resolution = 'none' | 'preview' | 'full'

export interface TelemetryState {
  activity: ActivityDetail | null
  telemetry: HhtObject | null
  resolution: Resolution
  loading: boolean
  error: string | null
}

/**
 * Loads one activity: the summary first, then telemetry at whatever resolution arrives first.
 *
 * The preview is roughly two thousand points and lands in a single small response, so the
 * charts and the route are on screen while the full object is still transferring; when it
 * arrives it is swapped in underneath. That ordering is the whole reason the phone writes two
 * objects, and it is what makes the three-second budget in SC-004 achievable on a slow
 * connection rather than merely on a fast one.
 *
 * A late preview is dropped rather than allowed to overwrite the full resolution — on a fast
 * link the two responses can land in either order.
 */
export function useActivityTelemetry(id: string): TelemetryState {
  const [state, setState] = useState<TelemetryState>({
    activity: null,
    telemetry: null,
    resolution: 'none',
    loading: true,
    error: null,
  })

  useEffect(() => {
    let cancelled = false
    let best: Resolution = 'none'

    const accept = (object: HhtObject, resolution: Resolution) => {
      if (cancelled) return
      if (best === 'full' && resolution === 'preview') return
      best = resolution
      setState((previous) => ({ ...previous, telemetry: object, resolution }))
    }

    setState({ activity: null, telemetry: null, resolution: 'none', loading: true, error: null })

    api
      .activity(id)
      .then(({ activity }) => {
        if (cancelled) return
        setState((previous) => ({ ...previous, activity, loading: false }))

        if (activity.telemetry.preview) {
          api
            .telemetry(id, 'preview')
            .then(decompressIfNeeded)
            .then((buffer) => accept(readHht(buffer), 'preview'))
            .catch(() => undefined)
        }

        if (activity.telemetry.full) {
          api
            .telemetry(id, 'full')
            .then(decompressIfNeeded)
            .then((buffer) => accept(readHht(buffer), 'full'))
            .catch(() => undefined)
        }
      })
      .catch(() => {
        if (cancelled) return
        setState((previous) => ({
          ...previous,
          loading: false,
          error: 'Could not load this activity.',
        }))
      })

    return () => {
      cancelled = true
    }
  }, [id])

  return state
}
