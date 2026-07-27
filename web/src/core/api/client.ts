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

export type Visibility = 'active' | 'archived'

/** Why a recording is in the archive. `null` while it is the representative one. */
export type ArchivedReason = 'duplicate' | 'manual' | null

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
  /** The app that wrote this recording, as a package name. */
  sourcePackage: string | null
  /** How many apps recorded this same workout, this one included. Never below 1. */
  sourceCount: number
  visibility: Visibility
  archivedReason: ArchivedReason
  /** Set once the athlete overrode the automatic choice; sync then leaves this row alone. */
  visibilityLocked: boolean
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

export interface Providers {
  password: boolean
  auth0: boolean
}

/**
 * One credential the client can prove, produced by `features/auth/prehash.ts`.
 *
 * `scheme` names the KDF and its parameters; `value` is 32 base64-encoded bytes. The Worker
 * hashes it with a per-user salt and stores that — it never sees the password these were
 * derived from (R-006 amendment).
 */
export interface PasswordProof {
  scheme: string
  value: string
}

/**
 * An app the phone has seen writing to Health Connect.
 *
 * Sources are discovered, never configured: the athlete only ever orders and enables them.
 * `priority` sorts ascending — index 0 is the most trusted.
 */
export interface Source {
  packageName: string
  priority: number
  enabled: boolean
  label: string | null
  firstSeenAt: number
  lastSeenAt: number | null
  activityCount: number
}

/**
 * One compacted month of the analytical tier — Parquet under `u/{user_id}/archive/`.
 *
 * Nothing here is a health figure: it is bookkeeping about objects, so that a browser can name
 * the files it wants to read. The Worker never opens one of them (R-014).
 */
export interface ArchiveMonth {
  /** `activities` today. `samples` is reserved and deliberately not produced — see 0007. */
  dataset: string
  year: number
  month: number
  generation: number
  partCount: number
  rowCount: number
  bytes: number
  compactedAt: number
  /** The live part keys. Absent when the response only counted them; the layout is specified. */
  parts?: string[]
}

export interface ArchiveManifest {
  bucket: string
  /**
   * `<account>.r2.cloudflarestorage.com`. Null where the deployment holds no R2 API
   * credentials of its own — the bucket name is still served, because an athlete with keys of
   * their own still needs to know where to look.
   */
  endpoint: string | null
  prefix: string
  /** False when this deployment cannot mint credentials at all, rather than merely has not. */
  credentialsAvailable: boolean
  months: ArchiveMonth[]
}

/**
 * Scoped, read-only, time-limited R2 credentials for the athlete's own archive prefix.
 *
 * Returned once and stored nowhere — not by the Worker, which keeps only the access key id and
 * the expiry so the athlete can see what they issued, and not by this client, which holds them
 * in the tab that asked for them.
 */
export interface ArchiveCredentials {
  accessKeyId: string
  secretAccessKey: string
  sessionToken: string | null
  bucket: string
  endpoint: string
  prefix: string
  expiresAt: number
}

export interface DynamicThemePayload {
  light: Record<string, string>
  dark: Record<string, string>
  source: 'dynamic' | 'default'
  updatedAt: number
}

export const api = {
  /** Which sign-in methods this deployment actually has configured. */
  providers: () => request<Providers>('/auth/providers'),

  /** The Material You palette the athlete's phone extracted from their wallpaper. */
  theme: () => request<{ theme: DynamicThemePayload | null }>('/theme'),

  clearTheme: () => request<void>('/theme', { method: 'DELETE' }),

  /**
   * Creates an account. Note what is missing: the password itself. A new account is born
   * under the pre-hashed scheme, so the browser has nothing to send that a breach of the
   * transport would reveal.
   */
  register: (email: string, displayName: string, proofs: PasswordProof[]) =>
    request<{ user: User }>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, displayName, passwordProofs: proofs }),
    }),

  /**
   * Signs in with both credentials, because the account decides which one counts.
   *
   * An account created before the pre-hash amendment stored a hash of the password itself, and
   * no proof can verify it; sending the password alongside the proof is what lets the Worker
   * verify the old record and rewrite it under the new scheme in the same request. Once every
   * account has signed in once, `password` comes out of this call — see R-006.
   */
  login: (email: string, password: string, proofs: PasswordProof[]) =>
    request<{ user: User }>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password, passwordProofs: proofs }),
    }),

  logout: () => request<void>('/auth/logout', { method: 'POST' }),

  me: () => request<{ user: User }>('/auth/me'),

  feed: (
    params: {
      cursor?: string | null
      limit?: number
      sport?: string
      /** Defaults to `active` at the Worker: the representative recording of each workout. */
      view?: 'active' | 'archive' | 'all'
    } = {},
  ) => {
    const query = new URLSearchParams()
    if (params.cursor) query.set('cursor', params.cursor)
    if (params.limit) query.set('limit', String(params.limit))
    if (params.sport) query.set('sport', params.sport)
    if (params.view) query.set('view', params.view)
    const suffix = query.toString() ? `?${query}` : ''
    return request<{ activities: FeedActivity[]; nextCursor: string | null }>(
      `/activities${suffix}`,
    )
  },

  activity: (id: string) => request<{ activity: ActivityDetail }>(`/activities/${id}`),

  /**
   * Moves a recording between the feed and the archive.
   *
   * The Worker sets `visibility_locked` on the way through, so this decision outranks every
   * later sync. Nothing is removed either way — the row and its telemetry stay exactly where
   * they were.
   */
  setVisibility: (id: string, visibility: Visibility) =>
    request<{ activity: FeedActivity & { description: string | null } }>(`/activities/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ visibility }),
    }),

  sources: () => request<{ sources: Source[] }>('/sources'),

  /**
   * Stores the athlete's trust order. The array order *is* the priority, so the whole list
   * goes up together — a partial write would leave two sources claiming the same rank.
   */
  saveSources: (sources: { packageName: string; enabled: boolean }[]) =>
    request<void>('/sources', {
      method: 'PUT',
      body: JSON.stringify({
        sources: sources.map((source, index) => ({ ...source, priority: index })),
      }),
    }),

  /**
   * Which months of the analytical tier exist, and where the bucket is.
   *
   * An athlete with nothing compacted gets a `200` with an empty `months` — only closed months
   * are rolled in, so a new account legitimately has none. A `404` means something else: a
   * deployment old enough to predate the route. The screen distinguishes the two, because
   * "wait for the nightly job" and "this server cannot do it" are different sentences.
   */
  archive: () => request<ArchiveManifest>('/archive'),

  /**
   * Mints read-only S3 credentials for `u/{user_id}/archive/` and returns them once.
   *
   * The TTL is bounded because Cloudflare's temporary access credentials cannot be revoked
   * before they expire; a short life is the only revocation there is.
   */
  archiveCredentials: (ttlSeconds?: number) =>
    request<{ credentials: ArchiveCredentials }>('/archive/credentials', {
      method: 'POST',
      body: JSON.stringify({ ttlSeconds: ttlSeconds ?? 3600 }),
    }),

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
