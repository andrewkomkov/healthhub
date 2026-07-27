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

**Decision**: Passwords are hashed with PBKDF2-SHA-256 via WebCrypto (100,000 iterations,
per-user random salt).

**Amended after deployment**: the original figure was 600,000. The Workers runtime rejects it
outright — `Pbkdf2 failed: iteration counts above 100000 are not supported` — so 100,000 is
the platform ceiling, not a tuning choice. This was caught by deploying and exercising the
route, not by reading documentation; the local `workerd` dev runtime does not enforce the
limit, so it passes locally and fails in production. Worth remembering for anything else
crypto-related.

The encoded hash embeds its own iteration count, so the parameters can be raised later
without invalidating stored passwords. The way to buy more work without spending Worker CPU
is a **client-side pre-hash** — the browser and the phone run a high-iteration KDF and send
the result, which the Worker then salts and hashes at 100,000. That also fits Principle I,
since the expensive part runs on the athlete's device. It is queued as a hardening task
rather than done now, because it has to be implemented identically in three places. Web sessions are opaque random tokens in an `HttpOnly`, `Secure`,
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

## R-013 — Two storage tiers: interactive `.hht`, analytical Parquet

R-002 was challenged with the standard R2 large-data playbook: Parquet with zstd, Iceberg on
top, 128–512 MB objects, Hive-style partitioned prefixes, multipart upload, and range reads
from DuckDB or ClickHouse. That playbook is correct — for the workload it describes. This
project has two workloads, and they want opposite things.

**Decision**: keep `.hht` for the interactive tier, and add a Parquet analytical tier.

### Interactive tier — one activity, read whole, by a browser

Stays `.hht` (R-002). The reasoning that survives the challenge:

- The reader is a browser rendering one activity. It needs **every sample of the channels it
  charts**, so column projection and predicate pushdown — Parquet's entire advantage — buy
  nothing. There is no `WHERE` clause; the query is always "give me all of it".
- Parquet's footer-then-column range dance is a win when it lets you avoid downloading data.
  Here it would mean 2–3 sequential round trips to fetch data we were going to fetch anyway,
  adding latency to the 3-second interactivity target (SC-005).
- `.hht` decodes by pointing typed arrays at an `ArrayBuffer`. Any Parquet reader — even a
  light one — decodes pages into JS arrays first. That decode is the thing SC-005 cannot
  afford.
- **zstd specifically does not work here**: browsers decompress gzip natively via
  `DecompressionStream`; zstd is not universally available, so a zstd object would require
  shipping a decompressor to every reader. Interactive objects therefore stay gzip.

Sizes make the point: a typical activity is a few hundred kilobytes compressed, a five-hour
million-sample ride a few megabytes. These are not 128 MB analytical files, and padding them
into one would destroy the "read exactly one activity" access pattern.

### Analytical tier — the whole history, read by a query engine

This is where the playbook applies, and it is genuinely missing from the original design. A
scheduled compaction job rolls closed months into partitioned Parquet:

```text
u/{user_id}/archive/activities/year=2026/month=07/part-0001.parquet   # one row per activity
u/{user_id}/archive/samples/year=2026/month=07/part-0001.parquet      # long-format samples
```

- **Parquet + zstd** — the reader here is DuckDB, ClickHouse or Polars, all of which handle
  zstd natively and benefit from column projection and partition pruning.
- **Target 128–512 MB per part**, produced by compacting many activities — the small-files
  problem is real for a query engine even though it is irrelevant to the interactive tier.
- **Hive-style partitioning** by year and month, so `WHERE year = 2026` prunes prefixes.
- Combined with R2's zero egress, this makes the athlete's full history queryable from
  anywhere at no transfer cost — which is a genuine product feature, not just plumbing:
  ```sql
  SELECT sport, sum(distance_m)/1000 AS km
  FROM 's3://healthhub-data/u/<uid>/archive/activities/year=2026/*/*.parquet'
  GROUP BY sport;
  ```

**Explicitly deferred**: Iceberg and Delta Lake. They buy ACID, schema evolution and time
travel over an append-only, single-writer, immutable dataset that has none of those problems.
The partitioned-Parquet layout is a strict subset of an Iceberg table, so adopting Iceberg
later is additive rather than a migration.

**Rejected**: *replacing `.hht` with Parquet everywhere* — trades the interactive tier's
zero-parse read for an optimisation that only pays off when you skip data, which the
interactive tier never does. *Keeping only `.hht` and querying it with DuckDB* — DuckDB
cannot read a bespoke format, which is exactly why the analytical tier exists.

### Consequences adopted from the playbook

- Key layout gains date partitioning in **both** tiers, so prefixes prune and lifecycle rules
  are expressible (data-model.md).
- Telemetry upload uses **multipart upload above 100 MB** with 8 MB parts, so a million-sample
  activity on a flaky mobile connection resumes per part rather than restarting.
- Compaction runs from a **Cron Trigger**, batching whole months; it never runs per activity,
  which is what would recreate the small-files problem in the analytical tier.
- Compaction is *file assembly*, not analysis: it concatenates already-computed rows. No
  aggregation happens in the Worker, so Principle I still holds.

## R-014 — DuckDB-Wasm: in the browser, not in the Worker

**Decision**: Adopt DuckDB-Wasm for whole-history analytical queries over the Parquet archive
tier — running **in the athlete's browser**, reading from R2 over HTTP range requests. Do not
run DuckDB inside the Worker.

The proposal to run it in the Worker is technically real, and the mechanics described are
right: range-read the Parquet footer, fetch only the needed columns, aggregate in memory. The
objection is not that it doesn't work — it is where the work happens.

**Why the browser wins here, on this project's own terms**:

- **Principle I is the whole architecture.** "Aggregation happens on the user's device" is the
  reason there is no backend, no database and no bill. Putting the query engine in the Worker
  moves analysis back to the server — quietly reintroducing the thing the design exists to
  avoid. The browser placement gets every benefit of DuckDB while keeping the principle.
- **The stated limits bite in the Worker and not in the browser.** A 15–20 MB Wasm binary
  against a Worker bundle limit; 128 MB of isolate memory; a 100–300 ms cold start on every
  isolate. A browser tab has none of these constraints — hundreds of megabytes of heap, the
  Wasm cached by the HTTP cache after first load, and a cold start the user pays once per
  session rather than per request.
- **Credentials.** Running in the Worker means S3 access keys live in the Worker and the
  Worker becomes the thing that must be trusted with every athlete's data. In the browser,
  the page fetches through the ownership-checked proxy already built for `.hht`, and no
  long-lived S3 credential exists at all.
- **Zero egress makes the browser path free.** The reason server-side aggregation is normally
  preferred is bandwidth cost. R2 removes that argument specifically.

**What this changes**: the analytical tier (R-013) gains a defined client. `web/src/core/analytics/`
loads DuckDB-Wasm lazily — only when the athlete opens a whole-history view, never on the feed
path — and queries the partitioned Parquet directly. Partition pruning and column projection do
the heavy lifting, so a "distance by sport across 2026" query touches a few megabytes.

**Kept from the proposal**: R2 Event Notifications are a good fit for triggering compaction
when new activities land, and are recorded as an option for the archive job.

**Reconsider if**: a future feature needs cross-athlete aggregation (leaderboards, segment
rankings for the planned social modules). That genuinely cannot run on one athlete's device,
and at that point a Worker-side query engine — or a scheduled job writing precomputed
leaderboard rows into D1 — becomes the right call. It is not needed for anything in this
specification.

## R-015 — GPS routes are not obtainable in bulk

**Finding**: `android.permission.health.READ_EXERCISE_ROUTE` is declared by the platform with
`prot=signature`, owned by `com.google.android.healthconnect.controller`. Only code signed
with Google's key can hold it. There is no plural `READ_EXERCISE_ROUTES` on the device at
all. Requesting it in the permission set produced a permission that could never be granted
and that then sat in every sync report as an unmet requirement.

**Decision**: drop it from the manifest and the permission set. Obtain routes the way a
third-party app actually can — `android.health.connect.action.REQUEST_EXERCISE_ROUTE` with a
session id, which asks the athlete about **one named workout** and returns that track once.

**Consequence for the specification**: FR-017's "activities with GPS render their route"
holds, but the route arrives per activity on the detail screen ("Import route") rather than
automatically during sync. SC-002's fidelity claim is unaffected — every sample Health
Connect will give us is still ingested; the tracks are simply gated behind an explicit,
per-activity consent that the platform does not let any app bypass.

**Also observed**: on the test device the writing apps are Google Fit and a bike-computer
app, and their sessions carry aggregates rather than tracks — so some activities would have
no route to import even with consent. Worth confirming per source before promising maps.

## Open risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Compose Material3 alpha API churn | Build breaks on version bump | Expressive usage confined to `core:designsystem`; CI compiles every push |
| A 1M-sample activity exceeding phone memory during codec encoding | SC-003 failure | Encode channel-by-channel streaming into a file, never a single in-memory buffer; verify on the SM-G780F |
| D1 write throughput during first-sync backfill | Slow initial import | Batch activity rows; telemetry goes to R2, so D1 writes are per-activity, not per-sample |
| Samsung Secure Folder (user 150) present on the test device | `adb install` targets the wrong user | Always install with `--user 0`; recorded in [quickstart.md](./quickstart.md) |
| MapLibre Native Android artifact coordinates and style compatibility | Map screen blocked | Verified at implementation time by build; fallback is a static route rendering from the polyline while the native map is wired up |
