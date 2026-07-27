/**
 * Typed client for the Worker contract (specs/001-workout-sync-feed/contracts/api.md).
 *
 * Same-origin throughout: the Worker serves this SPA, so there is no base URL to configure
 * and no CORS to negotiate. Session cookies ride along automatically.
 */

export interface ApiError {
  code: string
  message: string
}

export class ApiFailure extends Error {
  constructor(
    readonly status: number,
    readonly error: ApiError,
  ) {
    super(error.message)
    this.name = 'ApiFailure'
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api${path}`, {
    credentials: 'same-origin',
    ...init,
    headers: {
      ...(init.body ? { 'content-type': 'application/json' } : {}),
      ...init.headers,
    },
  })

  if (response.status === 204) return undefined as T

  const body = (await response.json().catch(() => null)) as
    | { error?: ApiError }
    | T
    | null

  if (!response.ok) {
    const error = (body as { error?: ApiError } | null)?.error ?? {
      code: 'internal',
      message: 'Request failed.',
    }
    throw new ApiFailure(response.status, error)
  }

  return body as T
}

/* ---------------------------------------------------------------------- models */

export interface User {
  id: string
  email: string
  displayName: string
  unitSystem: 'metric' | 'imperial'
}

export interface FeedActivity {
  id: string
  sport: string
  title: string
  startTime: number
  tzOffsetMinutes: number
  elapsedSeconds: number
  movingSeconds: number | null
  distanceM: number | null
  elevationGainM: number | null
  avgSpeedMps: number | null
  avgHrBpm: number | null
  hasGps: boolean
  routePolyline: string | null
  bounds: number[] | null
}

export interface Split {
  idx: number
  unit: 'km' | 'mi'
  distanceM: number
  elapsedSeconds: number
  movingSeconds: number | null
  avgSpeedMps: number | null
  elevationGainM: number | null
  elevationLossM: number | null
  avgHrBpm: number | null
  avgPowerW: number | null
}

export interface Zone {
  kind: 'hr' | 'power'
  zoneIndex: number
  lowerBound: number
  upperBound: number | null
  seconds: number
}

export interface ActivityDetail extends FeedActivity {
  description: string | null
  endTime: number
  elevationLossM: number | null
  caloriesKcal: number | null
  maxSpeedMps: number | null
  maxHrBpm: number | null
  avgCadenceRpm: number | null
  avgPowerW: number | null
  maxPowerW: number | null
  sampleCount: number
  channels: string[]
  telemetry: { full: boolean; preview: boolean; bytes: number | null }
  splits: Split[]
  zones: Zone[]
}

/* ------------------------------------------------------------------------- api */

export const api = {
  register: (email: string, password: string, displayName: string) =>
    request<{ user: User }>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password, displayName }),
    }),

  login: (email: string, password: string) =>
    request<{ user: User }>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),

  logout: () => request<void>('/auth/logout', { method: 'POST' }),

  me: () => request<{ user: User }>('/auth/me'),

  feed: (params: { cursor?: string | null; limit?: number; sport?: string } = {}) => {
    const query = new URLSearchParams()
    if (params.cursor) query.set('cursor', params.cursor)
    if (params.limit) query.set('limit', String(params.limit))
    if (params.sport) query.set('sport', params.sport)
    const suffix = query.toString() ? `?${query}` : ''
    return request<{ activities: FeedActivity[]; nextCursor: string | null }>(
      `/activities${suffix}`,
    )
  },

  activity: (id: string) => request<{ activity: ActivityDetail }>(`/activities/${id}`),

  /** Telemetry comes back as raw bytes; the codec turns it into typed arrays. */
  telemetry: async (id: string, variant: 'preview' | 'full'): Promise<ArrayBuffer> => {
    const response = await fetch(`/api/activities/${id}/telemetry?variant=${variant}`, {
      credentials: 'same-origin',
    })
    if (!response.ok) {
      throw new ApiFailure(response.status, { code: 'not_found', message: 'No telemetry yet.' })
    }
    return response.arrayBuffer()
  },
}
