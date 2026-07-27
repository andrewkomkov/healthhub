# Hard-won notes

Every entry below cost real time to discover. Read the section for the area you are touching
**before** you start, not after something breaks.

The rule that produced most of these: **this platform lies in the pleasant direction.** Local
runtimes are more permissive than production, pre-granting is more generous than a real user,
and a data hub happily hands you the same measurement twice. Verify against the real thing.

---

## Cloudflare Workers

**PBKDF2 is capped at 100,000 iterations.** Above that the runtime throws
`Pbkdf2 failed: iteration counts above 100000 are not supported`. The local `workerd` dev
runtime does **not** enforce it, so the code passes locally and fails in production. This is
the general shape of Workers bugs — assume any crypto or resource limit differs locally.

**So the KDF is split, and the halves cannot be changed apart.** The client derives 600,000
iterations' worth and sends the result; the Worker salts and hashes *that* at 100,000. Two
consequences worth having in your head before touching any of it: the Worker **cannot compute a
client proof** — 600,000 is over the ceiling, so `worker/test` uses opaque stand-in values and
the real vectors are pinned by `fixtures/auth/prehash-v1.json` from the web and Kotlin sides;
and an account created before the amendment stores a hash of the **password**, which no proof
can ever verify. That is why a stored record carries the scheme it was built from in a fifth
`$`-segment, and why `/auth/login` accepts the password beside the proof — it is the only thing
that can open an old record, and sending it is what migrates the account. Do not remove that
field to tidy the wire format up; read R-006 first.

**`wrangler` resolves its config from the current directory.** Always pass
`--config worker/wrangler.jsonc` from the repo root, or `cd` first. A `cd` that fails leaves
the shell in the previous directory and the next command silently targets the wrong place.

**Static assets and the API are one Worker.** `run_worker_first: ["/api/*"]` keeps static
requests out of the script. There is no Pages project; do not add one.

**D1 cascades need `PRAGMA foreign_keys = ON`**, which is per connection. Account deletion
therefore deletes explicitly, in order, in a batch — do not rely on `ON DELETE CASCADE` for
anything that matters. R2 objects have no cascade at all: delete the prefix by hand.

**`@cloudflare/vitest-pool-workers` 0.18 changed shape.** There is no
`@cloudflare/vitest-pool-workers/config` subpath and no `defineWorkersConfig`. The integration
is now a Vite plugin: `cloudflareTest({...})` imported from the package root. Tests use
`createExecutionContext()` + `waitOnExecutionContext()`.

**The auth rate limiter will throttle your test suite.** Ten sign-ups per IP per 15 minutes.
Give each test athlete its own `cf-connecting-ip` header.

**A migration file with no statements fails every test before one runs.** D1 answers
`SQL code did not contain a statement`, and because `applyD1Migrations` runs in `beforeAll`,
the entire suite reports as failed suites rather than as a migration problem. A comment-only
file counts as no statements. Reserved migrations therefore carry a `SELECT 1;` placeholder.
`0006_health_records.sql` and `0007_archive_tier.sql` are both real now — see the note further
down about what that placeholder costs on a database that already recorded it.

**Parquet in the Worker: `hyparquet-writer`, and it must be told how to compress.** The
popular writers do not run on workerd — `@dsnp/parquetjs` pulls in `thrift`, `@zenfs/core`
and the AWS S3 client, and the original `parquetjs` wants `fs` and Node streams.
`hyparquet-writer` is pure ESM with one dependency, and wrangler bundles it through its
`browser` export condition with no `fs` import at all (~110 kB unbundled, verified with
`wrangler deploy --dry-run --outdir`). One trap: it ships **only a snappy compressor**, and
`compressors[codec]?.(bytes) ?? bytes` means asking for `codec: 'ZSTD'` without supplying one
writes *uncompressed* pages labelled ZSTD — a file every reader will fail on. Either supply a
zstd compressor or write snappy honestly. workerd has no native zstd; `CompressionStream`
does gzip and deflate only.

**Session 5 settled it: snappy.** Two independent walls, both verified in workerd rather than
assumed. `new CompressionStream('zstd')` throws *"The compression format must be either
'deflate', 'deflate-raw' or 'gzip'"*, and the writer's `Compressor` is **synchronous** —
`(bytes) => bytes` — while every compression primitive the runtime offers is an async stream.
So even gzip could not be plugged in. Do not reopen this without a pure-JS zstd.

**A `TIMESTAMP` column must be fed `Date` objects, not epoch numbers.** The values would
convert either way, but the page *statistics* only handle `Date`, and the writer throws
`unsupported type for statistics: INT64 with value 1680595200000` partway through the file.
It reads as a data-type error; it is a statistics-encoder error.

**R2 multipart: 5 MiB is the floor, and miniflare enforces it.** Every part except the last
must reach it, or `complete()` fails with *"Your proposed upload is smaller than the minimum
allowed object size"*. A test cannot use a 1 KB part size to exercise the multipart path
cheaply — 5 MiB parts inside a 128 MB isolate is the cheapest honest version.

**A part encoded in a Worker cannot be 128–512 MB.** The isolate ceiling is 128 MB and the
buffer is built in memory. Whatever a storage playbook says about part sizes, the archive job
cuts on a memory bound.

**`wrangler dev --test-scheduled` cannot reach `/__scheduled` in this project.** Static assets
run first for everything outside `run_worker_first`, so the SPA fallback answers with
`index.html` and a cheerful 200 — the scheduled handler never runs and nothing says so. To
fire it locally, copy `wrangler.jsonc`, add `"/__scheduled*"` to `run_worker_first`, and run
`wrangler dev --config` against the copy.

**Filling in a placeholder migration does not re-run it.** `0007_archive_tier.sql` was applied
as a `SELECT 1;` reservation, so `d1 migrations apply --local` answers *"No migrations to
apply!"* and the tables never appear — while the test suite is fine, because it applies the
files from disk to a fresh database. Local dev needs
`wrangler d1 execute healthhub --local --file migrations/0007_archive_tier.sql` by hand. Check
`d1 migrations list --remote` before assuming production is in the same state; it was not.

**A response that already carries `Content-Encoding: gzip` gets compressed again.** Telemetry
is stored gzipped in R2 and served back with the header R2 recorded, so the client unwraps one
layer and is left holding the stored object — still compressed. Every client behaves this way
(Chrome, `curl --compressed`, undici), and nothing anywhere logs a word about it; the symptom
is a `.hht` reader complaining about magic `\x1f\x8b`. The web client therefore sniffs for the
gzip magic and un-gzips explicitly. Do not remove that check because the header looks correct.

---

## Android build

**AGP 9 brings Kotlin in-tree.** Applying `org.jetbrains.kotlin.android` is now an error:
*"The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since
AGP 9."* Remove it everywhere; keep `kotlin.plugin.compose`, `ksp` and `hilt`.

**`CommonExtension` lost its type parameters** in AGP 9, and `defaultConfig`/`compileOptions`/
`buildFeatures` are only reachable through the concrete `LibraryExtension` /
`ApplicationExtension`. Convention plugins must be typed on those.

**Library modules no longer accept `targetSdk`.** Only the application sets it.

**Gradle 9 refuses to configure a module whose directory does not exist.** Create the
directory before adding the `include(...)`.

**Material 3 Expressive alpha requires `compileSdk 37`** — `material3-ripple-android` says so
outright. The Expressive APIs are `MaterialExpressiveTheme`, `MotionScheme.expressive()` and
`@OptIn(ExperimentalMaterial3ExpressiveApi::class)`; usage is confined to
`core:designsystem` so an alpha bump is a one-module change.

**`shadow` is not a `ColorScheme` role in Compose.** The token generator filters it out;
Compose derives shadows from elevation.

**JUnit 5 needs `junit-platform-launcher` on the test runtime classpath**, or the Gradle test
worker cannot start at all: *"Failed to load JUnit Platform."*

**Health Connect types are not transitive.** A module whose public API returns them must
expose the dependency with `api(...)`, not `implementation(...)`.

**`IntervalRecord` is internal to the Health Connect SDK.** There is no shared supertype to
filter against; repeat the predicate per record type.

**A feature module cannot declare its own route.** `Destination` is a *sealed* interface, so
only `core:navigation` can implement it — a new `feature:*` module adds its destination there
and nowhere else. This is the design, not a violation of SC-012: the point of that criterion is
that no *existing feature* is touched. Attaching a module costs exactly three edits outside the
feature layer — a `Destination` in `core:navigation`, an `include` in `settings.gradle.kts`, and
one `implementation(project(...))` in `:app`. `feature:about` exists solely to keep that number
from growing; if it ever does, that module is what notices.

**The registry attaches whole screens and nothing smaller.** `NavContribution` lets a module add
a *route*; there is no equivalent for adding an *action to somebody else's screen*, and that gap
is where session 2 step 4 stopped. Archive-and-restore belongs on the activity detail screen,
but `ActivityScreen` is `internal` to `feature:activity` and has no slot a second module can put
a button into — so the action exists in `feature:sources` (the archive screen, and the `restore`
and `set-aside` ADB commands) while the detail screen still cannot offer it. The fix is one more
multibinding beside `NavContribution`: a `DetailAction` set declared in `core:navigation`,
collected by `feature:activity` and rendered in its top bar. That is a single edit to an existing
feature, made once, after which every later module attaches an action the way it already attaches
a route. Do it before the next feature needs the same thing — this is the second registry the
architecture wants, not a workaround.

**`viewModelScope` is cancelled *before* `onCleared()` runs.** `ViewModel.clear()` closes its
closeables — the scope is one of them — and only then calls `onCleared`, so any job launched
there is already in the cancelling state and `saveJob?.isActive` is `false` by the time you ask.
A last-gasp write guarded on `isActive` therefore never fires, in exactly the case it was
written for. `SourcesViewModel` compares what is on screen against what the server last accepted
instead, and hands the difference to a repository-owned scope that outlives the screen. The same
comparison has to key on *everything the write stores* — ordering by package name alone answers
"nothing changed" for a source the athlete just switched off inside the debounce window.

**MapLibre Native is not MapLibre GL JS, and the trap is a different one.** There is no worker
to point at; instead `MapLibre.getInstance(context)` must run before any `MapView` is
constructed, and the `MapView` needs every lifecycle callback forwarded by hand
(`onStart`/`onResume`/`onPause`/`onStop`/`onDestroy`) or it leaks a GL context per screen.
`CameraUpdateFactory.newLatLngBounds` is computed against the map's current viewport, so it
must be called after the map is ready rather than at construction. Geometry is handed over as
GeoJSON **text** across JNI — `RouteMap` builds it into one `StringBuilder` and thins the
route to a vertex budget first, because a hundred thousand coordinate pairs is several
megabytes of string on the frame that opens the screen.

**Kotlin `when` branches with braces are blocks, not lambdas.** `"hr" -> { value -> … }` inside
a `when` that is supposed to produce a `(Double) -> String` is a parse ambiguity you do not
want to discover through an error message. Return the lambda from a small function with a
declared return type instead — `formatterFor` in `feature:activity` exists only for that.

**The Kotlin token generator now emits `chart.sequential` — use it.** It used to write the
sequential ramp into the CSS custom properties and not into `GeneratedTokens.kt`, so the web
zone bars came from the ramp and the Android ones were mixed by hand from the channel colour
and the surface: same idea, same source, different steps. `GeneratedTokens.sequentialStep(index,
count, dark)` is the Kotlin half of the web's `ChartTheme.ordinal` and lays a set of ordinal
categories out over the same span. Anything still mixing its own ramp is drifting.

**The version catalogue already carries MapLibre and every Health Connect record type.**
`org.maplibre.gl:android-sdk` is declared, and sleep, HRV, resting heart rate, SpO2, weight,
body fat and blood pressure all ship inside the single `connect-client` artefact — there is no
separate module to add for any of them. Check the catalogue before adding a line to it.

---

## Health Connect (the big one)

**Never `adb install -g`.** Pre-granting makes the SDK believe every permission it knows
about is already held, so its request contract returns instantly and the in-app grant button
does nothing at all. This wasted a lot of time and looked like an app bug. Install clean:

```bash
adb -s <serial> install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

`--user 0` matters too: both test devices have a second profile (Secure Folder / Private
Space), and without it the app lands where it can see no data.

**`READ_EXERCISE_ROUTE` can never be granted to us.** The platform declares it
`prot=signature`, owned by `com.google.android.healthconnect.controller` — only Google-signed
code can hold it. There is no plural `READ_EXERCISE_ROUTES`. Requesting it produces a
permanently unmet requirement. GPS tracks are obtained **per activity** via
`android.health.connect.action.REQUEST_EXERCISE_ROUTE` with a session id, which asks the
athlete about one named workout.

**Do not build that intent by hand.** The action only exists on API 34+; on a device where
Health Connect is still an installed APK — the SM-G780F — the request has to travel over the
SDK's own service instead, and a hand-built intent resolves to nothing with no error. Use
`ExerciseRouteRequestContract`, which picks the road. `core:healthconnect/ExerciseRouteContract`
wraps it so no screen has to import a Health Connect type to ask for a track.

**The contract returns `null` for "declined" *and* for "nothing came back", and that ambiguity
must not reach the athlete.** What disambiguates it is the session, read *before* asking:
`ExerciseSessionRecord.exerciseRouteResult` is `ConsentRequired` when a track exists and
`NoData` when the recording app wrote no positions at all. On the platform path the converter
derives those from `hasRoute()`, so they are trustworthy. Telling someone "permission needed"
for a workout that has no track sends them through Health Connect's settings looking for a
switch that is not there — `RouteImportState` in `feature:activity` exists to keep the two
apart, and `route-status` over ADB prints which one applies.

**A route arrives after the fact, so the ingest has to be re-runnable.** `SyncEngine.importRoute`
reads the session again, re-reads its window, recomputes the duplicate verdict and puts the
whole thing through the same `ingest` a sync uses. Ten Health Connect reads for one tapped
workout, which is fine — the quota is exhausted by hundreds of automatic reads, not by one
deliberate one. What is *not* fine is a second implementation that only route imports use.

**An imported track is not automatically the activity's distance.** It covers what its own
recorder covered, which is sometimes the ten minutes before the battery died. Preferring it
unconditionally shrinks a ride the same way summing across sources inflates one.
`Metrics.reconcileDistance` checks both candidates against average speed over elapsed time and
takes the one inside the 0.8–1.25 band; splits are only cut from the track when the track *is*
the distance.

**And plenty of recordings have no speed channel to check against** — a distance aggregate and
nothing else is a normal thing for a phone app to write. That is not a reason to let the track
win by default; it is the case where a partial track is *most* likely, so the guard matters most.
With no third figure the two candidates are compared to each other over the same band: the track
wins when it agrees with the source's own summary, the summary wins when they disagree, and the
verdict is `DistanceAgreement.UNKNOWN` either way. The import message on the detail screen says
which happened, in words, and keeps saying it after the map appears — the card that explained it
is replaced by the route it produced, so the sentence is rendered beside the map instead.

**Telemetry is therefore no longer immutable**, and `GET /api/activities/:id/telemetry` no
longer says it is. A route import rewrites both `.hht` objects at the same key. The header is
`private, max-age=0, must-revalidate`; conditional requests already worked, so revalidation
costs a 304 with no body and no R2 egress.

**There is an API quota.** Eight reads per session exhausted it around session 75 with
*"API call quota exceeded"*. Reads are batched per day-window instead: eight calls per window
regardless of how many sessions it contains.

**The daily-grain pass uses a *month* window, and that is not an inconsistency.** Sleep, HRV,
resting heart rate, SpO2, weight, body fat and blood pressure exist on days with no workout on
them, so they cannot ride along with the session windows — `HealthRecordSync` is a second pass
with its own per-domain cursors. What both passes share is the shape that matters: one read per
record type per window, never one per record. What differs is the bound. The workout window is a
day because a day of heart-rate samples is as much as should be held in memory; a month of
resting heart rates is thirty rows. A year of all four daily-grain domains is twelve windows at
seven reads — eighty-four calls. The same year on a day window would be over two thousand, which
is how the quota was exhausted the first time. `content call --method health-sync` prints
`reads`, so this is checkable rather than assumed.

**A night's stage intervals arrive free.** `SleepSessionRecord` carries its own `stages` list, so
the hypnogram costs no extra call — unlike a GPS route, which does not.

**Sources write overlapping sleep stages.** A later write that relabels an hour already covered
is a correction, not additional sleep, and summing both reports a nine-hour night inside an
eight-hour window — the sleep version of the 89.59 km ride. `SleepSummary` clips every interval
to the session and gives overlap to whichever started first. And a source that writes a duration
with **no** stages at all is normal: that night slept for its whole duration, not for zero
seconds. Same rule as moving time.

**Permissions are declared in `core:healthconnect`'s manifest, not the app's.** Two lists that
have to agree is one too many — a record type added to `HealthRecordRegistry` without a matching
`<uses-permission>` produces a request that can never be granted, and that failure is silent, the
way `READ_EXERCISE_ROUTE` was. The manifest merger folds the library's declarations in.

**Declaring is not asking.** `HealthConnectSource.permissions` is now a *getter* over the domains
`HealthFeatures` says are on, so a fresh install requests the workout domain and nothing else
(Principle IV). The health screen is where the rest is turned on, and the switch flips only after
the grant comes back — switching first leaves a screen promising data it cannot read.

**Health Connect is a hub, and it will hand you the same ride several times.** Strava, Google
Fit, Samsung Health and a bike computer's own app each write their own session *and their own
`DistanceRecord`s covering the same minutes*. Summing across sources reported an 89.59 km ride
that was really ~42 km. Rules:

- Sum aggregates **within one source**, then choose a source. Never across sources.
- Prefer the session's own `metadata.dataOrigin.packageName`.
- Sanity check: distance must agree with average speed over elapsed time. If it does not,
  you are double counting.

**Zero is not a measurement.** A session whose speed channel never crosses the moving
threshold has *unknown* moving time, not none — storing 0 made the feed show "0:00" for real
workouts. Same for distance: fall back to the recorded aggregate when there is no GPS.

**A sparse source silently loses its moving time.** `Metrics.movingSeconds` skips any interval
longer than `MAX_SAMPLE_GAP_SECONDS` (30 s), on the reasonable theory that a long gap is not
evidence of movement. But Google Fit samples a walk about once every 77 seconds, so *every*
interval is skipped and a 46-minute walk is stored as 2:56 of moving time. Found on a real Pixel:
the detail screen's own range statistics, which have no such cap, reported 27:35 over the same
samples. Both numbers are on the same screen and they disagree, which is precisely what SC-008
forbids. The threshold needs to follow the source's actual cadence rather than assume 1 Hz, and
all three implementations of the rule — `Metrics`, Android `TelemetryAnalysis`, web `analysis.ts`
— have to agree once it does.

**The sync cursor belongs to an account.** Signing out must clear it, or the next account's
first sync reads from "now" and imports nothing — which presents as a dead sync button.

---

## Devices

| Device | Serial | Notes |
|---|---|---|
| Pixel 8 | `38041FDJH006G1` | Android 17 / API 37, Health Connect built in, Material You |
| Samsung SM-G780F | `RZ8R21EG0DJ` | Android 13 / API 33, Health Connect as an APK, Secure Folder |

The screen sleeps between ADB steps and screenshots come back black. `adb shell svc power
stayon true` first, and `input keyevent KEYCODE_WAKEUP` if it already slept.

**Tap coordinates shift** when a status card above the button grows. Screenshot, then tap —
do not reuse a coordinate from an earlier screenshot.

---

## The ADB control surface

Debug builds only. It is a **ContentProvider**, not a BroadcastReceiver: a receiver cannot see
its caller, and guarding it with a signature permission locks out `adb shell` itself while
still admitting any co-signed app. The provider checks `Binder.getCallingUid()` against shell
and root.

```bash
URI=content://dev.healthhub.debug.devcontrol
adb shell content call --uri $URI --method help
adb shell content call --uri $URI --method state
adb shell content call --uri $URI --method sync --extra days:s:365
adb shell am start -a android.intent.action.VIEW -d "healthhub://sync"
```

`sync` without `days` resumes from the cursor. Uploads are idempotent on the Health Connect
record id, so re-reading any window is always safe.

---

## Web client

**MapLibre's worker does not survive bundling.** MapLibre 6 resolves `maplibre-gl-worker.mjs`
relative to its own module URL, which after a build is an asset path that does not exist. The
request fails, no error reaches the console, the map's zoom buttons work, and the route simply
never appears. The fix is one line — `setWorkerUrl(url)` with a URL the bundler emitted:

```ts
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'
setWorkerUrl(workerUrl)
```

`?worker&url` and not `?url`: the plain form copies the file without its `maplibre-gl-shared`
import, which fails the same silent way.

**µPlot treats `null` as a gap and `NaN` as a coordinate.** The codec uses `NaN` for "not
recorded", and canvas quietly ignores a `NaN` coordinate — so the path continues from the last
good point and every tunnel renders as a straight line. Convert to `null` before plotting.

**Only the first channel payload is aligned.** The header is padded so the first payload starts
on an 8-byte boundary, but channels are contiguous after that: an odd sample count in the `u32`
time channel leaves every following `f64` on a 4-byte boundary, which `Float64Array` refuses to
view. The reader copies those channels and records it in `channel.copied`.

**Synthetic mouse events cannot drive a µPlot selection.** `mouseMove` ignores an event whose
`movementX` and `movementY` are both zero, which is exactly what `new MouseEvent()` produces.
Set `movementX` when scripting a drag, or the selection silently never forms.

**`@types/node` in the web workspace shadows lib.dom's stream globals.** The fixture tests
need `node:fs`, so `"node"` is in `web/tsconfig.json`'s `types` — and its `ReadableStream`
then wins the global merge, after which `pipeThrough` demands a writable of exactly
`Uint8Array` while DOM's `DecompressionStream` declares `BufferSource`. Nothing is wrong at
runtime; `decompressIfNeeded` in `core/telemetry/hht.ts` casts once and says so. Adding a
dependency that also declares stream globals will reopen this.

**`@duckdb/duckdb-wasm` publishes its `latest` tag as a prerelease.** `npm i` without a range
picks up a `1.33.1-devNN` build. The workspace pins `^1.32.0`, which a caret range will not
resolve to a prerelease. Load it with a dynamic `import()` only — it is roughly the size of
everything else on the page put together.

**The DuckDB Wasm binary cannot be a Workers asset.** `duckdb-eh.wasm` is 34 MB and
`duckdb-mvp.wasm` is 39 MB; Workers static assets cap a single file at 25 MiB. Importing them
through Vite (`?url`) produces a `dist/` that cannot be deployed at all. The bundle URL is
therefore resolved at run time — `VITE_DUCKDB_BUNDLE_BASE` for a self-hosted copy, the
library's own jsDelivr URLs otherwise — and `duckdb.createWorker(url)` is what makes the
cross-origin worker legal (`new Worker('https://cdn…')` is blocked outright).

**DuckDB-Wasm has no httpfs — it has five S3 settings and no more.** `s3_endpoint`,
`s3_region`, `s3_access_key_id`, `s3_secret_access_key`, `s3_session_token` work.
`SET s3_url_style` and `SET s3_use_ssl` throw *"Extension parameter s3_url_style was not found
after autoloading"*, which aborts the connection before a byte is read. Every DuckDB CLI
example on the internet sets both. Consequences:

- **Buckets are addressed virtual-host style**, always: the request goes to
  `https://{bucket}.{endpoint}/{key}`. Verified by watching the XHR — with endpoint
  `abc.r2.cloudflarestorage.com` and bucket `healthhub` it fetched
  `https://healthhub.abc.r2.cloudflarestorage.com/…`. There is no path-style switch.
- **It cannot list a bucket**, so `read_parquet('s3://…/*.parquet')` matches nothing. Every
  part has to be named — which is why `GET /api/archive` returns `parts`.
- The bucket needs a **CORS rule allowing the app's origin**, and a missing one surfaces as an
  opaque `NetworkError` with nothing in the console.

**The analytical Parquet is not shaped like the D1 row it came from.** `id` is written as
`activity_id`, instants are Parquet `TIMESTAMP` rather than epoch milliseconds (so the local
clock is `start_time + to_minutes(tz_offset_minutes)`, not `epoch_ms(…)`), and `visibility` is
in there — the archive keeps the duplicate recordings the feed set aside, so a sum without
`WHERE visibility = 'active'` counts one ride once per app that recorded it. `web/src/core/
analytics/fields.ts` resolves all of this from `DESCRIBE` instead of hard-coding it, and
`duckdb.test.ts` runs the real engine over a fixture in the job's own schema.

**Reordering a list drops focus off the item being moved.** React reorders siblings with
`insertBefore`, and moving a connected node is a remove-then-insert as far as focus is
concerned — so the second arrow key in a keyboard drag goes to `document.body` and nothing
happens. Nothing warns about it, and it does not reproduce with the mouse. The fix is to key
rows by something stable, remember which one is lifted, and re-focus its handle in a
`useLayoutEffect` after every reorder; `features/sources/SourcesScreen.tsx` does exactly that.

**A `useEffect` that loads a page runs twice in development, and `loading` will not stop it.**
StrictMode double-invokes mount effects, both calls read the same pre-update state, and both
append their page — the archive showed every workout twice. Production is unaffected, which is
why this survives review. Guard with a ref, not with state.

**A failed page must switch the scroll sentinel off, not merely report itself.** The sentinel
sits below the last card, so a *first* page that fails leaves it in an empty viewport — and
`IntersectionObserver` delivers an initial notification the moment it observes an element that
is already on screen. Rebuilding the observer on the next render therefore fires it again, and
one dropped connection becomes a request loop running as fast as the requests can fail. Nothing
shows it: the screen is already displaying its error and never changes. The feed and the archive
each had this, because the second was copied from the first, so both now come from
`web/src/core/paging.ts` — one `useActivityPages`, latching off on failure and offering a "Try
again" that un-latches. `paging.test.tsx` drives a stub observer and pins the call count; both
guards were confirmed by removing them and watching it go red (six requests, not one).

**`role="img"` on a container hides everything inside it.** `RouteMap` carried it, which was a
tidy way to name the map and an accidental way to remove MapLibre's own zoom buttons and its
keyboard-pannable canvas from the accessibility tree — the subtree of an `img` is
presentational, by specification. A labelled `role="region"` keeps the name and leaves the
controls reachable. `role="img"` is right on the chart panels, where the subtree really is
nothing but a canvas.

**Every route below the feed is lazily imported, and that is a budget, not a habit.** The
feed is what opens on a cold cache. `App.tsx` code-splits the activity, archive, sources and
health routes; a static import from `App.tsx` into any of them silently folds that screen
into the first chunk, and `npm run build` will show it as a jump in `dist/assets/index-*.js`.
Reading the build output is no longer the only line of defence: `core/analytics/budget.test.ts`
asserts that `App.tsx` names every screen below the feed *only* inside `lazy(import())`, and
that nothing on the cold-start path imports DuckDB, MapLibre, µPlot, or `core/{analytics,map,
charts,telemetry}`. The entry chunk is ~92% React — 189 kB of the 208 kB — so a leak is
obvious in the *app* attribution long before it is obvious in the total.

---

## Rules that exist twice

Analysis lives on the device, so almost nothing is implemented in two places. Four things are —
three because the *screen* derives them and both clients have a screen, and one because both
clients have a sign-in form. SC-008 is the criterion that fails when a pair drifts, and it
fails on a real ride in front of a person, months later.

| Rule | Kotlin | TypeScript |
|---|---|---|
| Distance axis, range statistics | `feature/activity/TelemetryAnalysis.kt` | `web/src/core/telemetry/analysis.ts` |
| Route segmentation and gap detection | `feature/activity/RouteGeometry.kt` | `web/src/core/map/route.ts` |
| Number and unit formatting | `feature/activity/Format.kt` | `web/src/core/format.ts` |
| Password pre-hash | `core/network/PasswordProofs.kt` | `web/src/features/auth/prehash.ts` |

The password pre-hash is the one where drift is not a wrong number on a screen: an athlete who
registered on their phone simply cannot sign in in a browser, and the message they get is
"incorrect email or password". `fixtures/auth/prehash-v1.json` holds the vectors both sides
assert against, and it is the only thing standing between those two implementations. The Worker
is not a third copy and cannot referee — it never sees a password, and 600,000 iterations is
above the ceiling it is allowed to run.

The Kotlin half writes PBKDF2 out over `Mac` rather than calling `SecretKeyFactory`, and that is
deliberate: `PBEKeySpec` takes a `char[]`, and the Bouncy Castle implementation Android ships
converts each character to a single byte. Any password outside ASCII then derives a different
key from the one WebCrypto derives in a browser — silently, on some devices and not others.
There is a vector in the fixture with a Cyrillic letter, an Ω and a bicycle in it for exactly
this reason.

The constants that must move together:

- `MOVING_SPEED_THRESHOLD_MPS = 0.5` — also `Metrics.MOVING_SPEED_THRESHOLD_MPS`, where it is
  `private`, which is why it is written out a third time rather than imported.
- `ELEVATION_NOISE_THRESHOLD_M = 1.0` — same story.
- The distance reconciliation band, `0.8 < correction < 1.25`. On the Kotlin side this now has
  exactly one definition — `Metrics.MIN_DISTANCE_CORRECTION` / `MAX_DISTANCE_CORRECTION`, which
  `TelemetryAnalysis` references rather than repeats, because the ingest path applies the same
  band when it decides whether an imported GPS track may stand in for a source's own distance.
  The TypeScript copy is still a copy.
- `MAX_PLAUSIBLE_SPEED_MPS = 60`, `MAX_PLAUSIBLE_JUMP_M = 2000`. A fixture for either twin has to
  be plausible **at its own sample rate**, and this is easy to get wrong: 0.001° between fixes is
  132 m at 50°N, which one second apart is 475 km/h. `RouteGeometryTest` had exactly that, so
  every leg tripped the guard, every segment was closed at one orphaned vertex, and the assertion
  reported zero segments rather than the two it was looking for — a test that reads like a
  regression in the splitter and is really arithmetic in the fixture. Both twins now use the same
  figures: 11 m legs at 1 Hz, one 16 km jump.
- The speed-versus-pace sport set, and every rounding rule beside it. `FeedScreen.kt` has its
  own copy of the sport set that is missing `swimming`; the detail screen's is the correct one.
  `feature/sources/Format.kt` is a *fourth* copy, added because the archive card shows the same
  figures and a feature module may not depend on another. Three Kotlin copies of one rounding
  rule is one too many: `core:ui` exists, has no source, and is where this belongs the moment
  someone is allowed to edit `feature:activity` and `feature:feed` in the same change.

One deliberate divergence: `Metrics.movingSeconds` ignores sample gaps longer than 30 s, and
neither screen's range statistics do. The screens mirror each other, not the ingest path.

## Design tokens

`packages/design-tokens/tokens.json` is the single source; `build.mjs` generates the Kotlin
theme and the CSS custom properties. **Neither generated file is committed** — run
`npm run tokens` before anything that compiles or lints them, including in CI.

Material You overrides **UI roles only** — never the series palette, which carries no such
guarantee once it comes from someone's wallpaper. Three places enforce that (the phone's
`toRoleMap`, the Worker's `ROLES`, the browser's `UI_ROLES`) and `dynamicTheme.test.ts` pins
all three to the token file's own role list, because a role added to one and not the others is
a colour that silently never arrives.

**The two token sets are now compared, not assumed.** `web/src/core/m3e/tokens.test.ts` reads
both *generated* artefacts and fails on any difference in roles, values, slot order, scales or
mark geometry. It found two live drifts on the day it was written: the sequential ramp and the
elevation scale reached the web and never reached Android. Two asymmetries are legitimate and
listed in the test — `shadow` (no Compose `ColorScheme` slot) and the font family (Compose
resolves the platform default). Anything else is drift.

**Contrast is measured in `contrast.test.ts`, and it says three uncomfortable things.**

- The light series palette has **three slots below 3:1** against the surface (aqua, yellow,
  magenta). That is what obliges the relief rule — a channel gets its own panel, its own name
  and its own value readout, so colour never carries identity alone. The exemption is pinned
  as the exact set of slots, not as a count.
- The dark series palette is **not CVD-safe on every pair**: slot 1 against slot 7 collapses to
  ΔE 2.5 under protanopia and slot 3 against slot 5 to ΔE 4.7 under deuteranopia. Eight
  categorical hues cannot survive three simulations at once; a search over the whole hue space
  could only reach ΔE 9.7. The weak pairs are pinned so a *new* collision fails the build.
- `chart.chrome.surface` is the page colour, but the chart stack is drawn **inside a card**
  (`surfaceContainerLow`). Ink and series are therefore checked against both.

**Type sizes are published in `rem`, and that is load-bearing.** A px font-size ignores the
reader's own browser font setting and only answers to page zoom. Anything that reads a type
token and needs a number — a canvas `font`, a µPlot axis gutter — has to resolve it first:
`toPixels` in `core/charts/theme.ts` is the one place that does. Media queries in `base.css`
are in `rem` for the same reason: a breakpoint in px does not trip when the *text* grows.
