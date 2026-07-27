# Phase 0 Research: Workout Sync & Activity Feed

Decisions taken before design, each with the alternatives that were rejected and why.
Constraints inherited from [the constitution](../../.specify/memory/constitution.md) are
treated as fixed inputs, not as open questions.

## R-001 — Hosting topology: one Worker with static assets

**Decision**: A single Worker deployed to `<name>.<subdomain>.workers.dev` serves the web
SPA through Workers static assets and answers the API under `/api/*`.

Configuration shape confirmed against Cloudflare documentation:

```jsonc
{
  "name": "healthhub",
  "main": "src/index.ts",
  "compatibility_date": "2026-07-26",
  "assets": {
    "directory": "../web/dist",
    "binding": "ASSETS",
    "not_found_handling": "single-page-application",
    "run_worker_first": ["/api/*"]
  }
}
```

**Why**: One origin means no CORS layer, one deployment, one hostname, and one place where
authentication is enforced. `run_worker_first` scoped to `/api/*` keeps asset serving on the
fast path — static requests never enter the script.

**Rejected**:

- *Worker + separate Pages project* — two deployments, two hostnames, CORS and cookie-domain
  friction, and Pages is the legacy path for new projects of this shape.
- *Custom domain* — the product owner asked explicitly for `*.workers.dev`; a custom domain
  would add DNS setup as a prerequisite for anyone cloning the repository.

## R-002 — Telemetry storage format: a columnar binary blob (`.hht`)

**Decision**: Each activity's samples are stored as one R2 object: a UTF-8 JSON header
(length-prefixed) describing the channels present, followed by each channel's samples as a
contiguous little-endian typed array. Details in [data-model.md](./data-model.md).

**Why**: The browser reads the object as an `ArrayBuffer` and constructs `Float32Array` /
`Int32Array` views directly over it — no parsing, no per-sample object allocation. This is
the single decision that makes SC-005 (interactive at 100k samples within 3 s) achievable,
and it is what µPlot wants as input anyway. Kotlin writes the same layout via `ByteBuffer`.

**Rejected**:

- *JSON* — a 1M-sample activity is hundreds of megabytes of text and produces millions of
  transient objects when parsed. Fails SC-003 and SC-005 outright.
- *Protobuf* — smaller than JSON but still requires a decode pass into objects, plus a
  schema-compiler dependency in three languages, for no gain over typed arrays.
- *Parquet* — the right answer for a query engine, and there is no query engine here.
  Reading it in the browser means shipping an Arrow/Parquet WASM bundle to render one chart.
- *Cloudflare Analytics Engine / Pipelines* — reintroduces a server-side time-series query
  path, which Constitution Principle I forbids.

## R-003 — Two objects per activity: full plus preview

**Decision**: Alongside the full-resolution blob, the phone writes a downsampled preview of
roughly 2,000 points per channel, produced with Largest-Triangle-Three-Buckets so the visual
shape survives.

**Why**: The detail screen paints from the preview (tens of kilobytes) immediately and swaps
in full resolution when it arrives, satisfying both the 3-second interactivity target and the
"detail fills in progressively" edge case. Fidelity is not compromised: the preview is an
addition, and the full-resolution object remains the source of truth (Principle VI).

**Rejected**:

- *Server-side downsampling on demand* — analytics in the Worker; forbidden.
- *Preview only* — loses fidelity permanently.
- *Range requests into the full object* — appealing, but channel-interleaved seeking plus
  gzip transfer encoding makes byte-range slicing unreliable; the preview is simpler and
  smaller than any range that would be useful.

## R-004 — R2 access path: Worker proxy, not presigned URLs

**Decision**: Telemetry is fetched through `GET /api/activities/:id/telemetry`, which checks
ownership in D1 and then streams `env.R2.get(key).body` back.

**Why**: The Worker already holds the session; proxying keeps authorisation and delivery in
the same place, streams without buffering, and inherits Cloudflare caching. Presigned URLs
would require S3-API credentials inside the Worker and would emit a bearer capability that
stays valid after the athlete's session ends or their device is revoked (FR-028).

**Rejected**: *Presigned S3 URLs*; *public bucket with unguessable keys* — security by
obscurity over health and location data, unacceptable under Principle IV.

## R-005 — Route geometry: encoded polyline in D1, full track in R2

**Decision**: A simplified route (Douglas–Peucker, then Google encoded-polyline format) is
stored as a text column on the activity row in D1. Full-resolution positions are a channel
in the R2 telemetry object.

**Why**: Feed cards need a route preview (FR-012) for every visible activity. Reading one R2
object per card would make the feed a fan-out of blob fetches. A few hundred bytes of
polyline per row keeps the feed a single indexed D1 query — which is what makes SC-004
(2-second feed) straightforward.

**Rejected**: *GeoJSON in D1* — an order of magnitude larger for the same shape, violating
the "rows stay small" rule in Principle II. *Rendering feed thumbnails as images* — requires
server-side rasterisation.

## R-006 — Authentication: opaque tokens in D1

**Decision**: Passwords are hashed with PBKDF2-SHA-256 via WebCrypto (600,000 iterations,
per-user random salt). Web sessions are opaque random tokens in an `HttpOnly`, `Secure`,
`SameSite=Lax` cookie, stored in D1. Devices hold a separate long-lived opaque device token,
also a D1 row, revocable individually.

**Why**: FR-028 requires per-device revocation and FR-004's offline buffering means device
tokens must be long-lived — the exact case where stateless tokens are wrong. A D1 lookup per
request is one indexed read on the path that already reads D1. PBKDF2 is available natively
in the Workers runtime with no dependency.

**Rejected**:

- *JWT* — revocation requires a server-side deny-list, i.e. the same D1 read, minus clarity.
- *Argon2id / scrypt* — stronger per unit of work, but needs a WASM or pure-JS dependency
  and CPU time the Worker's per-request budget cannot reliably afford.
- *OAuth via GitHub/Google* — good future addition, but it makes a third-party account a
  prerequisite for a self-hostable health app, and it does not solve device tokens.

## R-007 — Where each metric is computed

**Decision**: The phone computes splits, heart-rate zones, moving time, per-channel averages
and maxima, elevation gain/loss and smoothed series at ingest, and uploads them as small
rows. The browser computes only statistics for interactive range selections (FR-020), from
telemetry it has already downloaded. The Worker computes nothing.

**Why**: Direct expression of Principle I. It also removes the duplicate-implementation risk:
the two clients would otherwise both need split and zone maths, and would drift apart,
breaking SC-008 (identical figures on both clients).

**Rejected**: *Compute in the browser on every view* — recomputing over a million samples per
page load, and risking divergence from the Android numbers. *Compute in the Worker* —
forbidden, and would not fit the CPU budget.

## R-008 — Charting library: µPlot

**Decision**: µPlot, wrapped in a themed component that takes its colours, typography, grid
and cursor styling from the generated design tokens.

**Why**: It consumes typed arrays directly, renders hundreds of thousands of points to canvas
in milliseconds, is about 50 kB, and exposes the cursor-sync and range-selection hooks that
FR-019 and FR-020 need. Its visual output is fully controlled by our own options object, so
Principle III's "no library default palette" rule is satisfiable.

**Rejected**: *Recharts / Victory / Chart.js* — SVG or object-array oriented; they collapse
well below the sample counts required. *ECharts* — capable, but a large bundle and an opinionated
theming model to fight. *D3 by hand* — µPlot is what that would converge to.

## R-009 — Maps: MapLibre, openly licensed tiles

**Decision**: MapLibre GL JS on the web and MapLibre Native on Android, with an openly
licensed vector tile source and a style generated from the design tokens.

**Why**: Principle III requires map surfaces to match the product palette, which needs a
style we control; MapLibre is the open-source renderer that accepts one. It also avoids a
proprietary account being required to run the project, per the constitution's technology
constraints.

**Rejected**: *Mapbox GL* (as named in the PRD) — requires an access token and its licence is
incompatible with "clone and run"; *Leaflet with raster tiles* — no vector styling, so the
map cannot be themed, and raster tiles look wrong beside an Expressive UI.

## R-010 — Material 3 Expressive on the web

**Decision**: A hand-built React component layer implementing the Expressive specification
over CSS custom properties generated from `packages/design-tokens/tokens.json` — the same
file that generates the Android theme.

**Why**: There is no official Material 3 Expressive web library. `@material/web` implements
classic M3, is in maintenance, and would have to be overridden into Expressive shape anyway.
Generating both platforms from one token file is what actually delivers SC-008's visual
correspondence and Principle III's single source of truth; the component layer is then a
thin styling concern.

**Rejected**: *`@material/web`* — wrong Material generation, maintenance status, and fighting
its Sass pipeline costs more than writing the components. *MUI* — a different design system
wearing Material's name.

## R-011 — Android library versions

**Decision**: `androidx.compose.material3:material3:1.5.0-alpha24` and
`androidx.health.connect:connect-client:1.1.0`, verified as the newest published versions in
Google's Maven repository at planning time.

**Why**: Material 3 Expressive APIs are still evolving; the constitution explicitly accepts
alpha to get the full Expressive component set. Health Connect, by contrast, carries the data
fidelity obligation of Principle VI, so it takes the stable release rather than
`1.2.0-alpha04`.

**Risk accepted**: Expressive API names and signatures may change between alphas. Mitigation:
Expressive APIs are used only inside `core:designsystem`, so an alpha bump is a one-module
change; and CI compiles on every push, so drift surfaces immediately rather than at release.
Exact API symbols are verified by compilation rather than assumed from documentation.

## R-012 — Sync engine: Health Connect change tokens plus a Room staging buffer

**Decision**: A WorkManager periodic worker plus a Health Connect data-change trigger reads
changes via change tokens, writes them into a Room staging table, computes metrics, uploads,
and only then advances the stored cursor. Uploads are idempotent, keyed by the Health Connect
record UID.

**Why**: Satisfies FR-002 (incremental), FR-003 (resumable, no duplicates — the cursor
advances only after a confirmed upload), FR-004 (offline buffering), FR-005 (background) and
FR-007 (deletions propagate, since change tokens report deletions).

**Rejected**: *Full re-read on every sync* — fails SC-003 and drains battery. *Advancing the
cursor before upload* — loses data on interruption. *Client-generated identifiers* — would
duplicate on reinstall; the Health Connect UID is the natural idempotency key.

## Open risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Compose Material3 alpha API churn | Build breaks on version bump | Expressive usage confined to `core:designsystem`; CI compiles every push |
| A 1M-sample activity exceeding phone memory during codec encoding | SC-003 failure | Encode channel-by-channel streaming into a file, never a single in-memory buffer; verify on the SM-G780F |
| D1 write throughput during first-sync backfill | Slow initial import | Batch activity rows; telemetry goes to R2, so D1 writes are per-activity, not per-sample |
| Samsung Secure Folder (user 150) present on the test device | `adb install` targets the wrong user | Always install with `--user 0`; recorded in [quickstart.md](./quickstart.md) |
| MapLibre Native Android artifact coordinates and style compatibility | Map screen blocked | Verified at implementation time by build; fallback is a static route rendering from the polyline while the native map is wired up |
