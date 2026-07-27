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

### Password credentials

The KDF is split between the client and the Worker (R-006). A request may carry either or both
of two fields, and the account's stored record decides which one is verified:

```jsonc
"password": "…",                    // the password itself
"passwordProofs": [                 // what a client-side KDF produced from it
  { "scheme": "pbkdf2-sha256/600000/v1", "value": "<32 bytes, base64>" }
]
```

`scheme` names the algorithm and every parameter, so a change of parameters is a change of
name. The only scheme accepted today is `pbkdf2-sha256/600000/v1`:

```text
salt  = SHA-256( "healthhub/password/v1\n" + email.trim().toLowerCase() )
proof = base64( PBKDF2-HMAC-SHA-256( utf8(password), salt, 600000, 32 bytes ) )
```

The Worker hashes whichever material the record names with a per-user random salt at 100,000
iterations — the ceiling workerd enforces — and stores
`pbkdf2-sha256$<iterations>$<salt>$<hash>[$<scheme>]`. A record with no trailing `<scheme>`
was built from the password itself, which is every record written before the amendment.

**Migration.** A proof cannot verify a record built from the password, and vice versa. An
up-to-date client therefore sends both on sign-in: the password opens the old record, and the
proof beside it is written in its place, in the same request. After one sign-in per account the
`password` field is no longer needed and comes out of `/auth/login`. Sending only a password is
still accepted, so a client that has not been updated keeps working.

**The ten-character minimum is the client's to keep** whenever a proof is sent instead of a
password. The Worker is handed a fixed-width digest and cannot measure what produced it; it
still enforces the minimum on any `password` it does receive.

Errors: `422 validation_failed` for an unknown scheme, a proof that is not 32 base64-encoded
bytes, a repeated scheme, or a request carrying neither field.

### `POST /api/auth/register`

```jsonc
// request — an up-to-date client sends no "password" at all
{
  "email": "a@example.com",
  "displayName": "Andrew",
  "passwordProofs": [{ "scheme": "pbkdf2-sha256/600000/v1", "value": "…" }]
}
// 201
{ "user": { "id": "…", "email": "…", "displayName": "…", "unitSystem": "metric" } }
```

`409 conflict` if the normalised email exists. Sets the session cookie on success.

### `POST /api/auth/login`

```jsonc
// → 200 { "user": {…} } + cookie
{
  "email": "a@example.com",
  "password": "…",
  "passwordProofs": [{ "scheme": "pbkdf2-sha256/600000/v1", "value": "…" }]
}
```

`401 unauthenticated` on bad credentials — identical response and timing for unknown email
and wrong password. The decoy record hashed for an unknown email is built under the same scheme
the request offered, so the two paths cost the same derivation.

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

No `email`, and that is now load-bearing rather than a gap: the address is the client-side KDF
salt, so changing one would invalidate the athlete's password proof.

### `DELETE /api/auth/me` → `204`

Deletes every R2 object under `u/{user_id}/` — telemetry, hypnograms, archive parts — then
every row the athlete owns, named table by table in one batch: sessions, devices, activities,
splits, zones, cursors, reports, source preferences, health measurements and sleep sessions.
The batch is explicit rather than relying on `ON DELETE CASCADE`, which D1 honours only when
the connection has `PRAGMA foreign_keys = ON`. Irreversible. Satisfies FR-029.

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

### `GET /api/activities?cursor=&limit=30&sport=&from=&to=&view=`

The feed query (FR-010, FR-013, FR-015). One indexed D1 read; no R2 access.

`view` selects which recordings are visible, and defaults to `active`:

| `view` | Returns |
|---|---|
| `active` (default) | One row per workout — the representative recording |
| `archive` | Only the recordings that were archived as duplicates or by hand |
| `all` | Both, so a client can show "also recorded by N apps" without a second request |

Nothing is ever deleted, so the archive is always retrievable; `PATCH` restores a row and
locks that decision against later syncs.

```jsonc
{
  "activities": [
    {
      "id": "…", "sport": "cycling", "title": "Evening Ride",
      "startTime": 0, "tzOffsetMinutes": 180,
      "elapsedSeconds": 18000, "movingSeconds": 17240,
      "distanceM": 92310.4, "elevationGainM": 812.0,
      "avgSpeedMps": 5.36, "avgHrBpm": 138,
      "hasGps": true, "routePolyline": "…", "bounds": [ … ],

      // The archive relationship, on every item in every view.
      "sourcePackage": "com.strava",   // which app recorded this one
      "sourceCount": 3,                // how many apps recorded the workout, this one included
      "visibility": "active",          // "active" | "archived"
      "archivedReason": null,          // "duplicate" | "manual" | null
      "visibilityLocked": false        // true once the athlete overrode the automatic choice
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
{ "visibility": "active" }             // → 200 { "activity": {…} }   restore from the archive
{ "visibility": "archived" }           // → 200 { "activity": {…} }   set aside by hand
```

Any field may be sent on its own; all three may be sent together. Changing `visibility` also
sets `visibility_locked = 1` and stamps `archived_reason` (`manual`, or `null` on restore), so
the athlete's decision outranks the automatic one on every later sync. Nothing is removed
either way — the row and both R2 objects stay exactly where they are.

### `DELETE /api/activities/:id` → `204`

Removes the row and both R2 objects.

### `GET /api/activities/:id/telemetry?variant=preview|full`

Ownership-checked R2 proxy (R-004). Streams the object with
`Content-Type: application/octet-stream`, `Content-Encoding: gzip`, a strong `ETag`, and
`Cache-Control: private, max-age=0, must-revalidate`.

**Telemetry is not immutable.** It was, and this header said `immutable` for a year at a time
until session 3: importing a GPS route re-ingests the workout and rewrites both objects at the
same key (R-015), so a client holding an immutable copy would keep drawing a map with no route
on it and nothing would say why. Conditional requests are honoured, so revalidation costs a
304 with no body and no R2 egress.

`404 not_found` if the variant has not been uploaded yet, which the client treats as "still
syncing" rather than an error.

## Sources

Which apps the athlete trusts, and in what order. Health Connect is a hub, so one ride arrives
from several apps; this ordering is what the phone applies when it decides which recording
represents a workout. Sources are **discovered, never created** — the athlete only orders and
enables them.

### `GET /api/sources` → `200`

```jsonc
{
  "sources": [
    {
      "packageName": "com.wahoofitness.bolt",
      "priority": 0,              // lower sorts first; 0 is the most trusted
      "enabled": true,
      "label": "Wahoo ELEMNT",    // null when Android gave the phone no display name
      "firstSeenAt": 0, "lastSeenAt": 0,
      "activityCount": 87         // activities currently attributed to this package
    }
  ]
}
```

Ordered by `priority` ascending, then package name.

### `POST /api/sources/seen` — device token only → `204`

```jsonc
{ "packages": [ { "packageName": "com.strava", "label": "Strava" } ] }   // max 100
```

Registers what the phone observed during a sync. An existing row keeps its `priority` and
`enabled` — discovery must never reshuffle an ordering the athlete set deliberately.

### `PUT /api/sources` → `204`

```jsonc
{ "sources": [ { "packageName": "com.strava", "enabled": true, "priority": 0 } ] }   // max 100
```

The array order **is** the priority; `priority` may be omitted and defaults to the array index.
Send the whole list — a partial write would leave two sources claiming the same rank.

Disabling a source never discards anything: its recordings are still ingested and land in the
archive, so re-enabling it restores an intact history.

## Personalisation

The phone extracts a Material You palette from the athlete's wallpaper and pushes it here; the
browser fetches it on load and overrides its own token values, so both clients wear the same
personalised scheme rather than merely the same design system.

**Only UI colour roles travel.** The chart series palette is deliberately not part of this: it
is measured for contrast against both surfaces and for colour-vision separation, and a palette
mixed from someone's wallpaper carries no such guarantee. Unknown roles are dropped rather than
stored — this JSON becomes CSS custom properties in a page, so accepting arbitrary keys would
be a way to write into a stylesheet. The accepted role list is exactly the one the token
pipeline defines, and `dynamicTheme.test.ts` pins the phone's, the Worker's and the browser's
copies of it to `packages/design-tokens/tokens.json`.

### `GET /api/theme` → `200`

```jsonc
{
  "theme": {
    "light": { "primary": "#4a5c92", "onPrimary": "#ffffff" },   // #RRGGBB, role → colour
    "dark":  { "primary": "#b3c5ff", "onPrimary": "#1b2d61" },
    "source": "dynamic",        // or "default"
    "updatedAt": 0
  }
}
```

`{ "theme": null }` — a `200`, not a `404` — when the athlete has never pushed one. That is the
ordinary state for an account with no phone attached, and the client falls back to the built-in
palette without saying anything.

### `PUT /api/theme` — device token only → `204`

```jsonc
{ "light": { … }, "dark": { … }, "source": "dynamic" }
```

`source` defaults to `dynamic`. `422 validation_failed` if either scheme is not an object, if a
recognised role carries anything but a `#RRGGBB` string, or if a scheme survives filtering with
no recognised roles left in it — a palette that arrived entirely misspelled is a bug worth
hearing about, not an empty write to store.

### `DELETE /api/theme` → `204`

Reverts to the built-in palette. Both clients may call it; it is not device-only, because the
athlete undoing a personalisation should not need the phone that applied it.

## Liveness

### `GET /api/health` → `200 { "ok": true }`

Unauthenticated, and the one route that is. It exists so a probe can tell "the Worker is
running" from "the Worker is throwing", and it touches neither D1 nor R2 — a probe that queried
the database would report the database, which is a different question. Daily-grain health data
lives under `/api/health-records`; the two prefixes are kept apart on purpose.

## Health records

Daily-grain health data, under `/api/health-records`. **Not** `/api/health`, which is the
unauthenticated liveness probe and stays that way; two routers on one prefix would resolve by
registration order, which is not a property to depend on.

Every value arrives already computed from the phone and is stored verbatim, exactly as splits
and zones are. There is no readiness score, no sleep-quality index and no rolling average on
this API, because none of those is computed at the edge (Principle I). Storage class per data
class is decided in migration `0006` and explained in `data-model.md`: measurements and the
per-night summary are D1 rows; a night's stage intervals are one R2 object.

### `POST /api/health-records/measurements` — device token only

Scalar point measurements, in a batch. **Idempotent on `sourceUid`**: re-reading a Health
Connect window never doubles a reading.

```jsonc
// request
{
  "measurements": [
    { "sourceUid": "hc-uid-…", "sourcePackage": "com.google.android.apps.fitness",
      "kind": "resting_heart_rate", "measuredAt": 1753596300000, "tzOffsetMinutes": 180,
      "value": 48, "unit": "bpm" },
    { "sourceUid": "hc-uid-…", "kind": "blood_pressure", "measuredAt": 1753596400000,
      "value": 118, "secondaryValue": 74, "unit": "mmHg" }
  ]
}
// 200
{ "accepted": 2 }
```

`kind` is a lowercase slug (`^[a-z][a-z0-9_]*$`) and is **not** allow-listed: which record
types exist is the phone's registry's knowledge, and requiring a Worker deploy per new Health
Connect type is exactly the silent-loss failure Principle VI forbids. `value` /
`secondaryValue` / `unit` are stored as sent — only blood pressure uses the second number
today (systolic, diastolic), and the Worker does not know that.

At most 2000 measurements per request. Counted against the sync rate limit.

### `GET /api/health-records/measurements?kind=&from=&to=&limit=&cursor=`

One indexed D1 read; no R2 access, no aggregation. Newest first, keyset paginated on
`(measuredAt, id)`. `limit` defaults to 90 and caps at 366.

```jsonc
{
  "measurements": [
    { "id": "…", "kind": "resting_heart_rate", "sourceUid": "…", "sourcePackage": "…",
      "measuredAt": 1753596300000, "tzOffsetMinutes": 180, "localDate": "2026-07-27",
      "value": 48, "secondaryValue": null, "unit": "bpm" }
  ],
  "nextCursor": null
}
```

A client that wants a weekly average computes it from these rows.

### `POST /api/health-records/sleep` — device token only

One night's summary. **Idempotent on `sourceUid`**, `201` on create and `200` on update, the
same contract as `POST /api/activities`.

```jsonc
// request
{
  "sourceUid": "hc-uid-…",
  "sourcePackage": "com.samsung.android.shealth",
  "title": "Sleep",
  "startTime": 1753560600000, "endTime": 1753589100000, "tzOffsetMinutes": 180,
  "totalSeconds": 28500, "timeInBedSeconds": 29400,
  "stages": { "awake": 900, "light": 15600, "deep": 6000, "rem": 6000 },
  "stageCount": 42
}
// 201 / 200
{ "sleep": { "id": "…", "stagesUploadPath": "/api/health-records/sleep/…/stages" } }
```

`stages` accepts Health Connect's eight stage names — `awake`, `awakeInBed`, `outOfBed`,
`sleeping`, `light`, `deep`, `rem`, `unknown` — as seconds. An omitted stage stays `null`,
meaning "this source never reported it", which is not the same as zero.

The night is filed under the **local date it ended** (`localDate`), so a 23:40 and a 00:20
bedtime on consecutive evenings do not land on the same chart column.

### `PUT /api/health-records/sleep/:id/stages` — device token only

Body is the hypnogram: a JSON array of `{ stage, startTime, endTime }`, sent gzipped with
`Content-Encoding: gzip`. The Worker verifies ownership, streams the body into R2, and records
the key and byte count. It never parses it. `→ 204`, and re-uploading overwrites.

### `GET /api/health-records/sleep?from=&to=&limit=&cursor=`

Nights, newest first, keyset paginated on `(startTime, id)`. Stage totals come back with each
row, so the trend screen never touches R2.

```jsonc
{
  "sleeps": [
    {
      "id": "…", "sourceUid": "…", "sourcePackage": "…", "title": "Sleep",
      "startTime": 1753560600000, "endTime": 1753589100000, "tzOffsetMinutes": 180,
      "localDate": "2026-07-27", "totalSeconds": 28500, "timeInBedSeconds": 29400,
      "stages": { "awake": 900, "awakeInBed": null, "outOfBed": null, "sleeping": null,
                  "light": 15600, "deep": 6000, "rem": 6000, "unknown": null },
      "stageCount": 42,
      "hypnogram": { "stored": true, "bytes": 1840 }
    }
  ],
  "nextCursor": null
}
```

### `GET /api/health-records/sleep/:id` → `200 { "sleep": {…} }`

### `GET /api/health-records/sleep/:id/stages`

Ownership-checked R2 proxy (R-004). Streams the stored object with a strong `ETag` and
`Cache-Control: private, max-age=0, must-revalidate` — never `immutable`, because a source can
correct a night and the phone re-uploads it. Telemetry is served the same way, for the same
reason.

`404 not_found` while the night's summary exists but its stages have not been uploaded, which
the client treats as "still syncing".

## `/api/archive` — the analytical tier

Which months have been compacted into Parquet under `u/{user_id}/archive/`, and the scoped
read-only R2 credentials the athlete issues against their own prefix. Unrelated to
`GET /api/activities?view=archive`, which is archived *duplicate recordings*.

The Worker never reads a Parquet file it wrote (R-014); it lists parts and mints credentials.

### `GET /api/archive`

```jsonc
{
  "prefix": "u/{user_id}/archive/",
  "bucket": "healthhub-data",
  "endpoint": "https://{account}.r2.cloudflarestorage.com", // null without an R2 API token
  "credentialsAvailable": true,
  "months": [
    {
      "dataset": "activities", "year": 2026, "month": 6,
      "generation": 2, "partCount": 1, "rowCount": 63, "bytes": 24880,
      "compactedAt": 1751337600000,
      "parts": ["u/…/archive/activities/year=2026/month=06/part-0001.parquet"]
    }
  ]
}
```

`parts` is the point of this endpoint. A glob over a month's prefix is correct except during
the seconds a rebuild is replacing that month; this list comes from a manifest that flips in
one transaction and is exact at every instant — and DuckDB-Wasm cannot list a bucket at all,
so in the browser naming the files is the only option. Clients hand the keys to
`read_parquet([...])`.

The bucket name is served even where credentials cannot be minted: it is an identifier, not a
secret, and someone using keys of their own still needs to know where to look.

### `POST /api/archive/credentials`

```jsonc
// request — both fields optional
{ "ttlSeconds": 3600, "label": "laptop duckdb" }
// 201
{
  "credentials": {
    "id": "…", "accessKeyId": "…", "secretAccessKey": "…", "sessionToken": "…",
    "permission": "object-read-only",
    "bucket": "healthhub-data", "prefix": "u/{user_id}/archive/",
    "endpoint": "https://{account}.r2.cloudflarestorage.com",
    "issuedAt": 1753600000000, "expiresAt": 1753603600000, "label": "laptop duckdb"
  },
  "duckdb": "CREATE OR REPLACE SECRET healthhub (…);\nSELECT sport, …"
}
```

The prefix, the bucket and the permission are **derived from the authenticated athlete**, not
read from the body: there is no parameter that could name someone else's prefix, and a body
that tries is ignored. `ttlSeconds` is 900–604 800, an hour by default — Cloudflare cannot
revoke a temporary credential before it expires, so the TTL is the only limit that exists.

`422 validation_failed` on a TTL outside those bounds. `429 rate_limited` above ten mints per
athlete per hour. `404 not_found` when the deployment has no R2 API token configured, exactly
like the Auth0 routes: a fork keeps everything else.

The secret and session token appear in this response and nowhere else. D1 records the access
key id, the prefix, the permission and the expiry so the athlete can audit what is
outstanding.

### `GET /api/archive/credentials`

The same rows without any secret, newest first, each with an `expired` flag.

## Scheduled work

A Cron Trigger (`0 4 * * *`, `worker/wrangler.jsonc`) invokes the Worker's `scheduled` handler
to compact closed months into the analytical tier. Daily rather than monthly because a
backfill can add activities to an already-compacted month.

A month is rebuilt when `COUNT(*)` or `MAX(updated_at)` over its activity rows differs from
the numbers recorded for the last build, and only once the month has been over for 48 hours —
local dates run from UTC-12 to UTC+14, so a month ends at different moments for different
athletes. Twelve months per run, oldest first.

Re-running a month **replaces** it: everything under the prefix that the new build will not
write is deleted first, the parts are written over stable `part-NNNN` names, and the manifest
flips in a single D1 batch. The failure this ordering rules out is the quiet one — an orphaned
part from a longer previous build, which no row count would ever reveal and which would make
an athlete's own query count some rides twice.

Compaction is file assembly — it concatenates rows the device already computed. No
aggregation runs here (Principle I), and only the `activities` dataset is produced: building
`samples` would mean decoding `.hht` in the Worker.

## Rate limiting

Auth routes are limited per IP (10 attempts / 15 min) using D1 counters. Sync routes are
limited per device (generously — a backfill legitimately posts thousands of activities).
Exceeding a limit returns `429 rate_limited` with `Retry-After`.
