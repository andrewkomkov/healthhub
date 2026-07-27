# Worker HTTP Contract

Base: `https://healthhub.<subdomain>.workers.dev`

Everything under `/api/*` is handled by the Worker; every other path is served from static
assets, with unmatched paths falling back to `index.html` for client-side routing.

## Conventions

- Request and response bodies are JSON (`application/json`) unless stated otherwise.
- Timestamps are integer milliseconds since the Unix epoch, UTC.
- Errors use `{ "error": { "code": "<slug>", "message": "<human readable>" } }` with a
  matching HTTP status. Codes: `unauthenticated`, `forbidden`, `not_found`, `conflict`,
  `validation_failed`, `rate_limited`, `internal`.
- The Worker performs no computation over samples. Every route is an indexed D1 read/write,
  an R2 get/put, or an auth check.

## Authentication

Two credential types, both opaque tokens hashed in D1 (R-006):

- **Web session** — `Cookie: hh_session=<token>`, set `HttpOnly; Secure; SameSite=Lax`,
  30-day expiry.
- **Device token** — `Authorization: Bearer <device_token>`, long-lived, revocable per
  device. Used only by the Android app.

Every route below requires one of the two and resolves it to a `user_id`. **Ownership rule**:
a route that names a resource verifies `resource.user_id == session.user_id` before doing
anything else; a mismatch returns `404 not_found`, never `403`, so identifiers cannot be
probed.

### `POST /api/auth/register`

```jsonc
// request
{ "email": "a@example.com", "password": "…", "displayName": "Andrew" }
// 201
{ "user": { "id": "…", "email": "…", "displayName": "…", "unitSystem": "metric" } }
```

`409 conflict` if the normalised email exists. Password minimum 10 characters. Sets the
session cookie on success.

### `POST /api/auth/login`

```jsonc
{ "email": "a@example.com", "password": "…" }   // → 200 { "user": {…} } + cookie
```

`401 unauthenticated` on bad credentials — identical response and timing for unknown email
and wrong password.

### `GET /api/auth/providers`

```jsonc
{ "password": true, "auth0": true }   // auth0 is false when the deployment has no tenant
```

Lets a client render only the sign-in methods that are actually configured.

### `GET /api/auth/auth0/login?mode=web|device&deviceName=…`

Starts the OIDC authorization code flow and `302`s to the tenant's `/authorize`. Sets a
short-lived signed `hh_oauth` cookie carrying the `state`, `nonce` and mode.

`mode=device` is how Android signs in: the app opens this in a Custom Tab, so the client
secret stays in the Worker and the athlete's password never passes through the app.

`404 not_found` when Auth0 is not configured for the deployment.

### `GET /api/auth/auth0/callback?code=&state=`

Verifies `state` against the cookie, exchanges the code for an `id_token` using the client
secret, verifies the token's RS256 signature against the tenant JWKS plus issuer, audience,
expiry and nonce, then resolves the identity to an account:

1. a known `(auth0, sub)` identity → that account;
2. otherwise a **verified** email matching an existing account → linked to it;
3. otherwise a new account, with the sentinel password hash `external`.

Finishes by setting the session cookie and redirecting to `/` (web), or by redirecting to
`healthhub://auth/callback?token=<device_token>&device=<id>` (device).

### `POST /api/auth/logout` → `204`

Deletes the session row.

### `GET /api/auth/me` → `200 { "user": {…} }`

### `PATCH /api/auth/me`

```jsonc
{ "displayName": "…", "unitSystem": "imperial" }   // → 200 { "user": {…} }
```

### `DELETE /api/auth/me` → `204`

Deletes every R2 object under `u/{user_id}/`, then the user row (cascading sessions, devices,
activities, splits, zones, cursors, reports). Irreversible. Satisfies FR-029.

## Devices

### `POST /api/devices`

Registers this installation. Requires a **web session** — the app performs a normal login
first, then exchanges it for a device token.

```jsonc
// request
{ "name": "SM-G780F", "platform": "android", "appVersion": "1.0.0" }
// 201 — the token is returned exactly once and never again
{ "device": { "id": "…", "name": "…", "createdAt": 0 }, "token": "<device_token>" }
```

### `GET /api/devices` → `200 { "devices": [ … ] }`

Never includes tokens; includes `lastSeenAt` and `revokedAt`.

### `DELETE /api/devices/:id` → `204`

Revokes the device. Subsequent sync attempts with that token get `401 unauthenticated`
(FR-028, and the sign-out acceptance scenario).

## Sync

### `GET /api/sync/cursors` — device token only

```jsonc
{ "cursors": [ { "recordType": "ExerciseSession", "changeToken": "…", "syncedUntil": 0 } ] }
```

### `PUT /api/sync/cursors` — device token only

Advances cursors. Called **after** the corresponding uploads are confirmed, never before —
this ordering is what makes an interrupted sync resumable without loss (R-012).

```jsonc
{ "cursors": [ { "recordType": "ExerciseSession", "changeToken": "…", "syncedUntil": 0 } ] }
// → 204
```

### `POST /api/sync/reports` — device token only

```jsonc
// request
{
  "startedAt": 0, "finishedAt": 0, "status": "partial",
  "sessionsSynced": 12, "samplesSynced": 480123,
  "failures": [ { "sourceUid": "…", "reason": "…" } ],
  "unhandledTypes": [ "NutritionRecord" ]
}
// 201 { "report": { "id": "…" } }
```

`unhandledTypes` is how Principle VI's "fail loudly" requirement reaches the user — the app
surfaces it, and it is visible on the sync screen.

### `GET /api/sync/reports?limit=20` → `200 { "reports": [ … ] }`

## Activities

### `POST /api/activities` — device token only

Uploads one activity's summary and derived metrics. **Idempotent on `sourceUid`**: re-posting
the same record updates the existing row and returns `200` rather than creating a duplicate
(FR-003, SC-006).

```jsonc
// request
{
  "sourceUid": "hc-uid-…",
  "sport": "cycling",
  "title": "Evening Ride",
  "startTime": 1753600000000,
  "endTime": 1753618000000,
  "tzOffsetMinutes": 180,
  "elapsedSeconds": 18000,
  "movingSeconds": 17240,
  "distanceM": 92310.4,
  "elevationGainM": 812.0,
  "elevationLossM": 806.5,
  "caloriesKcal": 2410,
  "avgSpeedMps": 5.36, "maxSpeedMps": 16.1,
  "avgHrBpm": 138, "maxHrBpm": 176,
  "avgCadenceRpm": 84.2, "avgPowerW": 191, "maxPowerW": 640,
  "hasGps": true,
  "routePolyline": "…encoded…",
  "bounds": [55.70, 37.50, 55.82, 37.71],
  "sampleCount": 184320,
  "channels": ["t","lat","lon","elevation","hr","speed","cadence","power"],
  "splits": [
    { "unit": "km", "idx": 0, "distanceM": 1000, "elapsedSeconds": 186.2,
      "movingSeconds": 186.2, "avgSpeedMps": 5.37, "elevationGainM": 12.0,
      "elevationLossM": 3.0, "avgHrBpm": 131, "avgPowerW": 178 }
  ],
  "zones": [
    { "kind": "hr", "zoneIndex": 1, "lowerBound": 0, "upperBound": 120, "seconds": 1840 }
  ]
}
// 201 (created) or 200 (updated)
{ "activity": { "id": "…", "telemetryUploadPath": "/api/activities/…/telemetry" } }
```

Splits and zones arrive precomputed; the Worker stores them verbatim (R-007). Validation is
structural only — the Worker does not recompute or cross-check derived values.

### `PUT /api/activities/:id/telemetry` — device token only

Body is the raw `.hht` object (`Content-Type: application/octet-stream`, sent with
`Content-Encoding: gzip`). Query parameter `variant=full|preview` selects the target key.
The Worker verifies ownership, streams the body into R2, and records the key and byte count.

`→ 204`. Re-uploading the same variant overwrites — the operation is idempotent.

### `GET /api/activities?cursor=&limit=30&sport=&from=&to=`

The feed query (FR-010, FR-013, FR-015). One indexed D1 read; no R2 access.

```jsonc
{
  "activities": [
    {
      "id": "…", "sport": "cycling", "title": "Evening Ride",
      "startTime": 0, "tzOffsetMinutes": 180,
      "elapsedSeconds": 18000, "movingSeconds": 17240,
      "distanceM": 92310.4, "elevationGainM": 812.0,
      "avgSpeedMps": 5.36, "avgHrBpm": 138,
      "hasGps": true, "routePolyline": "…", "bounds": [ … ]
    }
  ],
  "nextCursor": "…"          // null when the history is exhausted
}
```

Keyset pagination on `(start_time, id)` — stable while new activities arrive at the head.

### `GET /api/activities/:id`

Full detail: every summary column plus `splits`, `zones`, `channels`, `sampleCount`, and
whether each telemetry variant exists. Still no R2 access — the detail screen renders its
summary panel and splits table before any telemetry arrives.

### `PATCH /api/activities/:id`

```jsonc
{ "title": "…", "description": "…" }   // → 200 { "activity": {…} }   (FR-016)
```

### `DELETE /api/activities/:id` → `204`

Removes the row and both R2 objects.

### `GET /api/activities/:id/telemetry?variant=preview|full`

Ownership-checked R2 proxy (R-004). Streams the object with
`Content-Type: application/octet-stream`, `Content-Encoding: gzip`, a strong `ETag`, and
`Cache-Control: private, max-age=31536000, immutable` — telemetry for a given activity never
changes once written.

`404 not_found` if the variant has not been uploaded yet, which the client treats as "still
syncing" rather than an error.

## Rate limiting

Auth routes are limited per IP (10 attempts / 15 min) using D1 counters. Sync routes are
limited per device (generously — a backfill legitimately posts thousands of activities).
Exceeding a limit returns `429 rate_limited` with `Retry-After`.
