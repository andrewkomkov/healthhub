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

**A route that no module serves is a crash, not a dead link.** `Destination` is sealed and lives
in `core:navigation`, so a route value exists as soon as somebody writes it there — but the graph
only contains what a `NavContribution` registered. `Destination.Settings` has existed for months;
`feature:settings` is an empty directory with no Kotlin file in it. A menu that listed the
destinations by hand therefore offered Settings, and tapping it threw `IllegalArgumentException:
Navigation destination that matches route settings cannot be found in the navigation graph`.
The menu is now built from `NavContribution.menuEntries` and provided through `LocalNavMenu`, so
only a module that registered a screen can offer a way into it — the failure is not fixed, it is
unrepresentable. A feature declares its own entries (`feature:sources` declares two, the source
order and the archive), `:app` collects and sorts them, and `feature:feed` draws whatever it is
handed without naming a single destination.

**There are now three registries, and they are the whole of how a module attaches to the app.**
All three live in `core:navigation`, all three are Hilt multibindings, and `:app` or the owning
feature collects each one. Copy the shape rather than inventing a fourth mechanism:

| Registry | Declares | Collected by |
|---|---|---|
| `NavContribution.destination` + `register` | a route and its screens | `:app`, into the `NavHost` |
| `NavContribution.bottomBarEntry` | a place in the navigation bar | `:app`, into the bar |
| `NavContribution.menuEntries` | a way in from the feed's overflow and the settings screen | `:app`, through `LocalNavMenu` |
| `ActivityActionProvider` | an *action on somebody else's screen* | `feature:activity`, into its top-bar overflow |

The last one is what session 2 step 4 was blocked on and is worth understanding before adding a
feature that needs to reach into another. `ActivityScreen` is `internal` to `feature:activity`;
`feature:sources` owns the archive, the visibility call, and the words that explain them; a
feature may not depend on another (Principle VII). So archive-and-restore existed on the archive
screen and over ADB while the screen the athlete was actually looking at could not offer it.
`ArchiveActionProvider` now binds `@IntoSet` and the detail screen renders whatever it is handed:
neither module names the other. The provider is *asked* what applies to the activity in front of
the athlete (`actionsFor(target)`), which is what lets one provider offer "Set aside" on an
active recording and "Restore" on an archived one without the detail screen knowing either word.

**`bottomBarEntry` was declared for months and read by nobody.** The only way to any screen but
the feed was an overflow behind three dots. `:app` builds the bar from the registry now, hides
it below the top level, and `Navigator.navigateTopLevel` is what makes it behave like tabs
rather than a stack — `popUpTo(start) { saveState = true }`, `launchSingleTop`, `restoreState`.
Without that trio, four taps on four tabs cost four presses of back and the feed forgets its
scroll position every time you leave it.

**One registry, one consumer.** `menuEntries` was read in two places at once — the feed's
overflow and the settings screen's "More" card — so Sources, Archive, Updates and About were
each offered twice, on two screens, from the same set. The overflow is gone: of the two, a
three-dot menu is where an app puts what it hopes you will not need, and Settings is a tab on
the bar. The registry itself is unchanged; it simply has one reader now. Found by the athlete
looking at the app, which is the only way this kind of thing is ever found.

**A bottom bar and a FAB do not fit on a landscape phone.** Together they take a third of a
400 dp-tall window away from a scrolling list. `:app` draws a **navigation rail** instead below
`COMPACT_HEIGHT_DP` (480 dp) — Material's compact-height class — which is the same decision the
web client's `AppShell` makes at its own breakpoint. Decided from `LocalConfiguration`'s height
rather than from a window-size-class artefact: it is one threshold, and that dependency is not
otherwise in the build.

**A tab must not draw a back arrow.** Back on a top-level destination points at whichever screen
happened to precede it, or leaves the app; the bar is the way between them. `feature:health` had
one and no longer takes an `onBack` at all, which is the version that cannot regress.

**`core:preferences` is where a setting lives, and the split matters.** Appearance (theme mode,
dynamic colour) is about *this phone* and stays local; the unit system belongs to the *account*,
because the browser renders the same ride and two clients disagreeing about kilometres is SC-008
failing. The local `unitSystem` is a mirror of the server's, written back by every screen that
happens to read `/api/auth/me`, so the phone converges whichever screen is opened first. It used
to be read by the detail screen only, which is how the feed came to draw kilometres under a
detail screen drawing miles. `AppPreferences.clearAccountScoped()` drops the mirror on sign-out
and leaves the appearance alone.

**Sign-out is `AccountSession.signOut()` and it does four things, not one.** Token, cached feed,
**sync cursor** and the mirrored units. The cursor is the one that is easy to miss: it belongs to
the account that was signed in, and leaving it makes the next account's first sync resume from
"now", read nothing, and report success — which presents as a sync button that does nothing. The
server half (revoke this device, end the session) is best-effort and the local half is not: an
athlete who taps sign out on a train has still asked to be signed out. The debug `logout` command
calls the same function, so what an automated run exercises is what a finger on the phone does.

**The app is localised, and the labels a module hands to another module are resource ids.**
Every `feature:*` module carries its own `res/values/strings.xml` and `values-ru/strings.xml`;
shared vocabulary — sport names, metric names, "Try again" — lives in `core:ui` and is reached
through `dev.healthhub.core.ui.R` (AGP generates one `R` per module, so a library's resources
are named by that library rather than by whoever uses them: `import dev.healthhub.core.ui.R as
CoreR`).

Three consequences worth knowing before adding a screen:

- **`BottomBarEntry.label`, `MenuEntry.label`, `ActivityAction.label` and
  `ActivityActionResult.message` are `@StringRes Int`, not `String`.** Whoever *draws* them is
  in another module — `:app` draws the bar, `feature:activity` draws the action menu — and a
  module handing over a finished string would have had to translate it at a point where it has
  no `Context`. The id travels; the lookup happens in the composition;
- **a failure's own message stays a string.** `ActivityMessage` is a sealed pair of `Resource`
  and `Text` for exactly this: there is no catalogue of every sentence a network stack can
  produce, and one generic translated sentence for all of them hides the detail that makes a
  failure diagnosable. The sentence *around* the cause is translated; the cause is quoted;
- **a lambda parameter that resolves a resource must be `@Composable`.** `SettingsScreen`'s
  `Choice` takes `label: @Composable (T) -> String`, and its result is read into a local
  *before* the `Modifier.clearAndSetSemantics` block — that block is an ordinary lambda, not a
  composition, and a resource lookup cannot happen inside one.

**The unit suffixes are localised through a default parameter, and the default is English.**
`Format` is a pure object with no `Context`, and its rounding is pinned by `FormatTest` against
`web/src/core/format.ts` — so a *required* labels argument would have put a language into every
one of those assertions. Instead every function takes `labels: UnitLabels = UnitLabels.ENGLISH`:
a screen calls `unitLabels()` and passes the result, a test leaves it alone and keeps asserting
on "41.20 km". Resolve it **once per composable**, not per figure: the feed card formats six
figures and is drawn for every row of a list somebody scrolls a month of.

**The web client's localisation is `core/i18n.ts`, forty lines and no dependency.** The entry
chunk is a two-second budget that is already 90% React, and an i18n library solves problems this
product does not have. What matters is where the *strings* live: each screen declares its own
bundle beside itself and reads it with `useMessages`, so a lazily-imported screen's words land in
that screen's chunk rather than in the entry. A central dictionary would have put every word of
the archive and the analytics console into the first thing the browser parses. Measured: the
whole mechanism plus the shell, feed, sign-in and health strings cost 2.7 kB on the entry chunk,
and the health strings went into `HealthScreen-*.js` where they belong.

Three things it does deliberately: it matches on the **language and drops the region**, because
`ru-RU` and `ru-BY` are one translation and matching the full tag serves English to everybody
outside Russia; it falls back **key by key**, so a missing entry shows in English while the rest
of the screen stays translated; and it uses Android's **positional** `%1$s` form, because the
same sentence exists in `res/values-ru/` and a translator moving between the two files should
not have to learn a second syntax — and because word order genuinely differs, so an argument
has to be able to move.

`format.ts`'s `UnitLabels` is the twin of `Format.kt`'s, field for field, with the same
English default for the same reason: the tests on both sides assert the English strings, so a
required argument would have put a language into every one of them.

**`core:ui` has source now, and it is where a rule that two screens share belongs.** It carries
`Format` (one copy of every rounding rule, replacing four), `HealthHubIcons` (one glyph per
concept, named after the concept), and the shared Expressive composables — `StatTile`,
`MetricLabel`, `SectionCard`, `SectionHeader`, `EmptyState`, `ErrorState` and the skeletons.
Reach for it before writing a second private `Stat` or a fourth `Format`. Two things it must not
become: it does **not** compute a metric (that is `core:telemetry`, on the device, at ingest),
and it does **not** use an Expressive API directly — those stay wrapped in `core:designsystem`
so an alpha bump is a one-module change. `HealthHubNavigationBar` and `HealthHubProgressBar` are
what that wrapping looks like.

**The icon set is `material-icons-extended`, and it is on the convention plugin.** The core set
is a back arrow, a search glass and about twenty others: no heart, no stopwatch, no mountain,
which is most of the alphabet a workout is written in. Every icon is its own class, so R8 keeps
only what is referenced — the debug build carries the lot and the release build does not. The web
client cannot do the same thing (an icon font is 300 kB against a 200 kB bundle), so it draws
eleven paths by hand in `core/m3e/Icon.tsx` at the same 24-grid proportions. Adding a metric to
one client means adding its mark to both, or the two stop reading as one product.

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

**`MapLibreMap.setStyle` only ever calls back on success.** A style URL that fails — no
network, a 404, a captive portal — invokes `OnStyleLoaded` never, so a `suspendCancellableCoroutine`
wrapped around it suspends forever and the screen shows a shaped container with nothing inside
it, which reads exactly like a bug in the drawing code. The other half of the pair is
`MapView.addOnDidFailLoadingMapListener`; a style that neither loads nor fails is the caller's
`withTimeoutOrNull`. Both are why `RouteMap.awaitStyle` returns `Style?` and the composable
falls back to the inline token style. Same failure, same fix, as the web client's `load` event.

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
code can hold it. Requesting it produces a permanently unmet requirement. GPS tracks are
obtained **per activity** via `android.health.connect.action.REQUEST_EXERCISE_ROUTE` with a
session id, which asks the athlete about one named workout.

**The plural `READ_EXERCISE_ROUTES` does exist, and the per-activity flow does not work
without it.** An earlier note here said it did not exist. It does: `prot=dangerous`, added in
API 35, verified with `dumpsys package permission android.permission.health.READ_EXERCISE_ROUTES`
on the Pixel. The singular is the signature-level dead end; the plural is not.

It must be **declared and never requested**, which is an odd combination worth stating plainly:

- *Declared*, because `RouteRequestActivity` checks the caller's manifest before it draws
  anything. Without the declaration it logs `E RouteRequestActivity: Read permission not
  declared`, finishes with `RESULT_CANCELED`, and the athlete sees no dialog at all — the app
  then reports the track as declined, which is a lie it has no way to detect. This is the whole
  reason "Import route" did nothing for four months.
- *Never requested*, because the platform ignores the request: *"Attempts to request the
  permission by applications will be ignored"*. It is granted only by the athlete, in Health
  Connect's settings or in the route request activity itself. So it stays out of
  `HealthConnectSource.permissionsFor` — putting it there would recreate the permanently-unmet
  requirement that removing the singular was meant to fix.

Once it *is* granted, `readRecords` returns `ExerciseRouteResult.Data` directly and no
confirmation appears at all. `RouteImportState.Offered` already carries the points in that case
and imports without launching anything, so both paths were already handled — only the
declaration was missing. `route-status --extra import:s:true` over ADB exercises the granted
path end to end without a tap.

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

**Match the session by `sourceUid`, never by start time.** The detail response now returns the
Health Connect record id the activity was ingested from, and `RouteImporter.inspect` reads it.
It used to re-derive the match from start time plus source package, which is ambiguous exactly
when it matters: the same walk recorded by two apps is two sessions sharing a start instant, and
`findSession` picked one of the pair arbitrarily. The import then re-ingested *that* session and
upserted on **its** id, so the athlete tapped "Import route" on one activity and watched the
track land on its duplicate — the activity in front of them still offering to import a route it
already had. Two "Walking · 16:39" cards in the feed, one of which has a map, is the signature.
The start-time path is still there as a fallback for rows uploaded before the field existed.

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

**Absence cannot be read, so deletions come from the change log (FR-007).** `readRecords`
returns what exists; no range query can report that a workout has gone. The only thing that can
is `getChangesToken` / `getChanges`, and it answers "what changed since this token" rather than
"what is missing". Three consequences, all of them visible to the athlete:

- **the first sync propagates nothing.** There is no token yet, so `SyncEngine.propagateDeletions`
  takes one and reports zero; deletions flow from the second sync onwards. The token is taken at
  the *end* of the first sync, not the start — a workout deleted while that sync was running
  would otherwise fall in the gap between the two;
- **an expired token is a gap, not a deletion.** Health Connect drops one after about a month.
  A phone that has been off that long takes a fresh token and reports nothing. Inventing
  deletions out of a gap would remove workouts nobody deleted;
- **the change log is paged.** `hasMore` / `nextChangesToken` are drained in one pass, or a
  deletion sits unpropagated behind an upsertion-heavy page.

The server side is `POST /api/activities/deleted`, which takes `sourceUids` — the change log
reports a deleted *record*, and that id is what the activity was ingested under, so neither side
looks anything up. It is a **soft** delete, and the distinction is the storage design: *archived*
is a duplicate the sync demoted and the athlete can restore; *deleted* is the athlete removing it
at the source. `deleted_at` is what every read path already filters on, so one stamp clears it
from the feed, the archive, the detail route and the analytical export at once — and the upsert
sets `deleted_at = NULL`, so re-adding the record in Health Connect brings the workout back.
The token advances only after the server accepts the list, and the route is idempotent, so a
failure means the next sync reports the same ids again and nothing is lost.

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

**Average speed is distance over moving time, never the mean of the speed channel.** A sampled
mean weights a minute standing at a traffic light exactly like a minute at thirty. The detail
screen's range statistics have said so in a comment for months and computed it correctly for a
*selection*, while the summary above them came from `Metrics.mean(speed)` at ingest. What that
produced, read off a Pixel: a 1.43 km walk with elapsed 22:59, moving 6:04 and an average pace
of 18:06 /km — and 1.43 km at 18:06 /km is 25:53, **longer than the elapsed time on the tile
beside it**. Three figures on one screen from three different places, one of them arithmetically
impossible against the others. `Metrics.averageSpeed` is now the one rule: distance over moving,
falling back to distance over elapsed when moving is unknown, and to the channel mean only when
there is no distance at all (a treadmill session with a speed trace and nothing else). Verified
on the device afterwards — every card in the feed now multiplies out.

**Zero is not a measurement.** A session whose speed channel never crosses the moving
threshold has *unknown* moving time, not none — storing 0 made the feed show "0:00" for real
workouts. Same for distance: fall back to the recorded aggregate when there is no GPS.

**A sparse source silently lost its moving time — settled: the gap threshold follows the
source's cadence.** `Metrics.movingSeconds` used to skip any interval longer than a flat 30 s,
on the reasonable theory that a long gap is not evidence of movement. It is reasonable at 1 Hz
and wrong at anything else: Google Fit samples a walk about once every 77 seconds, so *every*
interval was skipped and a 46-minute walk was stored as 2:56 of moving time — while the detail
screen's own range statistics, which had no cap at all, reported 27:35 over the same samples.
Two numbers on one screen, which is what SC-008 forbids, and neither was right.

`Metrics.sampleGapCapSeconds` is the rule now: `max(30 s, 3 × the median sample interval)`, and
all three implementations apply it — `Metrics` (moving time *and* zones), Android
`TelemetryAnalysis`, web `analysis.ts`. Four things about it are decisions rather than details:

- **a 1 Hz recording is unchanged.** Three times a one-second cadence is three seconds, and the
  floor keeps the cap at 30 — so every activity already synced from a dense source keeps exactly
  the moving time it was stored with. That is what made this safe to ship over a synced history
  rather than a migration;
- **the median, not the mean.** A ride with one coffee stop has a mean interval dragged upwards
  by the stop, and would then count the stop as movement;
- **below `MIN_INTERVALS_FOR_CADENCE` (10) intervals there is no cadence**, and the flat floor
  stands. Three samples one second and then five minutes apart have a *median* of two and a half
  minutes; treating that as "how often this source writes" turns the pause into movement;
- **zones were broken the same way and are fixed by the same change.** A sparse source's every
  interval exceeded the flat cap, so its time-in-zone came out as all zeroes — an empty bar
  chart under a full heart-rate trace.

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

## Shipping and the in-app update

Deployment and release both run in Actions — `deploy.yml` on a push to `main`, `release.yml` on
a tag. Nothing ships from a laptop (Constitution Principle V), and the migration step lives
*only* in the workflow: a local `wrangler deploy` puts a Worker live against a database that
was never migrated, which is how every sleep request answered 500 for a day.

`feature:updates` is the other half — there is no store, so the app asks GitHub for its own
releases and installs what it finds. What that cost to get right:

**Play Protect ends the install session rather than pausing it.** An APK it has never scanned
produces "Рекомендуется проверка приложения" *after* the athlete has already confirmed the
update. Choosing to scan returns `STATUS_FAILURE_ABORTED` — the same status as pressing Cancel
— nothing installs, and the progress bar disappears with no error anywhere. Pressing Install a
second time then goes straight through. This is why the abandoned path leaves a note on screen
instead of falling silent. Verified on a Pixel 8, 2026-07-28.

**"Install unknown apps" cannot be requested from a dialog.** It is `REQUEST_INSTALL_PACKAGES`
in the manifest *plus* a per-app appop the athlete flips in system settings. The only useful
response to `canRequestPackageInstalls() == false` is to open
`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this package — which is what the Install
button does when the permission is missing, alongside a line saying what happened.

**A debug build cannot be updated by the release APK.** `applicationIdSuffix = ".debug"` makes
them different packages, so the released APK would install *beside* it. The screen says so and
offers the release page instead of an install that cannot work.

**Two installed HealthHubs make every deep link ambiguous.** With both the debug and the
release build on a phone, `am start -a VIEW -d healthhub://updates` opens the app chooser and
the automated step hangs on it. Always pass `-p dev.healthhub` (or `-p dev.healthhub.debug`).

**`.apk.sha256` does not end in `.apk`** — but it does *contain* it. Matching the release asset
with `contains` hands 64 bytes of text to the package installer. `ReleasesTest` pins the order
of the assets too, because the API does not guarantee one.

**`versionCode` and `versionName` are independent, which is what makes this testable.** To
prove the whole path without uninstalling anything, build a signed release whose *name* is
older than the published one and whose *code* equals what is installed:

```bash
ANDROID_VERSION_NAME=0.0.9 ANDROID_VERSION_CODE=1 ./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The app then finds the real release, downloads it, and installs it over itself — data intact,
same signing key, and the phone ends where it started. A *lower* `versionCode` cannot be
installed over a higher one at all: `-d` only lifts that for debuggable packages.

**A release built before 2026-07-28 has no `.apk.sha256` beside it.** An absent checksum is
tolerated rather than treated as a mismatch — refusing to install because a file was never
published helps nobody, and the signature check that decides whether the APK may replace this
app happens either way.

---

## Web client

**The end-to-end run is `npm run test:e2e`, and it starts the real Worker.** Not a mocked API:
what these tests are for is the seam the unit tests cannot reach — the SPA and the API behaving
as one origin, a session cookie surviving a reload, and a screen rendering what D1 actually
returned. A mocked API would pass with the Worker switched off. Three things cost time:

- **Vite binds `[::1]` only.** Playwright polls the IPv4 `baseURL`, the readiness check times out
  after two minutes, and nothing in the log says why — the server is up, on an address nobody is
  asking about. `--host 127.0.0.1` is load-bearing in `playwright.config.ts`;
- **the auth rate limiter throttles a browser suite too.** Ten sign-ups per IP per fifteen
  minutes, and every test comes from 127.0.0.1 — so the sixth test fails with a screen that
  simply never became the feed. `context.setExtraHTTPHeaders({'cf-connecting-ip': …})` per test,
  the same fix `worker/test` uses;
- **seed through the real upload route, not by writing to D1.** `activity.spec.ts` registers,
  buys a device token and posts a workout exactly as the phone does, because the shape of that
  request is part of what is under test: the Worker stores what the phone computed and
  recomputes nothing, so a figure that came out wrong would mean the contract drifted. Handing
  the browser the session cookie the API just issued is what lets the page open signed in;
- **assert on what the deployment offers, not on what yours has.** The first sign-out test looked
  for the Auth0 button, which a deployment without Auth0 configured correctly does not render —
  `/api/auth/providers` decides. The test was asserting a configuration rather than a behaviour.

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

**The map's `load` event never fires if the style does not arrive.** `load` is raised from the
render loop the first frame `map.loaded()` is true, and a style that 404s or hangs never gets
there — so everything hung off `on('load')`, which is the whole route, silently never happens.
That was harmless while the style was a local object; it is not now that the default basemap is
fetched from OpenFreeMap. `RouteMap` therefore treats a pre-`load` `error` and a timeout the
same way: `setStyle(localStyle, {diff: false})`, whose `load` *does* fire, and the ride is drawn
on the product's surface colour instead. Tile and glyph errors raise `error` too, so the handler
has to be guarded on "has the style settled yet" rather than acting on every error it sees.

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

**A single stored fix is a track by every check except the one that matters.** `Route.geometry`
drops a segment of fewer than two points — one fix draws nothing, and keeping it puts a stray dot
on the map — so it returns no bounds and the map renders nothing. Both detail screens used to
decide *whether to show a map* on a different question ("are there any positions"), and a
recording with one fix answered yes to that and no to the map's, so the screen showed neither the
map nor the card that explains its absence: a gap between two cards and no way to tell it from a
rendering fault. Both now decide from the geometry itself and pass the fix count into the
explanation, because "the recorder stored one position" and "this workout has no GPS" are
different sentences and only one of them is true. Google Fit writes exactly this for a ride it
picked up from another app: `hasGps: true`, `routePolyline` one point long.

**`role="img"` on a container hides everything inside it.** `RouteMap` carried it, which was a
tidy way to name the map and an accidental way to remove MapLibre's own zoom buttons and its
keyboard-pannable canvas from the accessibility tree — the subtree of an `img` is
presentational, by specification. A labelled `role="region"` keeps the name and leaves the
controls reachable. `role="img"` is right on the chart panels, where the subtree really is
nothing but a canvas.

**The web client's navigation is `core/m3e/AppShell.tsx`, and it is one component in two
shapes.** A bar along the bottom under 48 rem, a rail down the side above it, decided in a media
query rather than from a measured width — so there is no first frame in the wrong shape and no
resize listener to leak. The breakpoint is in `rem` for the same reason the type scale is: a
`px` breakpoint does not trip when the reader's *text* grows, which is exactly when the
horizontal room runs out. `current === null` is how a screen below the top level says "draw no
navigation"; the activity detail passes it, because that screen has its own way back and four
sideways moves over it are chrome. The four destinations are the phone's four, in the phone's
order, on purpose.

The file lives in `core/m3e` because that directory is already on the cold-start path, which
means `budget.test.ts` checks it for free — a static import of MapLibre or the telemetry codec
from the navigation shell would fail the build.

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

Analysis lives on the device, so almost nothing is implemented in two places. Five things are —
four because the *screen* derives them and both clients have a screen, and one because both
clients have a sign-in form. SC-008 is the criterion that fails when a pair drifts, and it
fails on a real ride in front of a person, months later.

| Rule | Kotlin | TypeScript |
|---|---|---|
| Distance axis, range statistics | `feature/activity/TelemetryAnalysis.kt` | `web/src/core/telemetry/analysis.ts` |
| Readiness and daily trends | `feature/health/{Readiness,Trends}.kt` | `web/src/features/health/recovery.ts` |
| Chart reduction — bucket means | `feature/activity/ChartSeries.kt` | `web/src/core/charts/buckets.ts` |
| Route segmentation and gap detection | `feature/activity/RouteGeometry.kt` | `web/src/core/map/route.ts` |
| Number and unit formatting | `core/ui/Format.kt` | `web/src/core/format.ts` |
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
  `private`, which is why it is written out a third time rather than imported. Both detail
  screens now floor the *speed axis* here as well, and for a reason the fences cannot cover: a
  commute that stands at lights for two fifths of its samples has a whole quartile of stopped
  speeds, so the Tukey fence sits underneath them and the bottom of a pace axis still reads
  122:54 /km. The floor is applied after the range padding on both sides — `axisRange`'s `floor`
  argument, `ChartSeries.axisFloor` — because padding under it puts the bottom back at half an
  hour per kilometre. A channel that never crosses the threshold at all keeps its own range.
- `ELEVATION_NOISE_THRESHOLD_M = 1.0` — same story.
- The distance reconciliation band, `0.8 < correction < 1.25`. On the Kotlin side this now has
  exactly one definition — `Metrics.MIN_DISTANCE_CORRECTION` / `MAX_DISTANCE_CORRECTION`, which
  `TelemetryAnalysis` references rather than repeats, because the ingest path applies the same
  band when it decides whether an imported GPS track may stand in for a source's own distance.
  The TypeScript copy is still a copy.
- `BUCKETS = 220` — the number of points a chart is reduced to, `ChartSeries.BUCKETS` and
  `CHART_BUCKETS`. Two clients smoothing one ride differently are two different rides, and the
  disagreement is invisible until the two are held side by side. The reduction is the **mean** of
  each bucket on both sides; it used to be the min-max envelope, which kept every one-second
  spike and rendered a five-hour ride as a solid brush of ink with no shape in it. The extremes
  are still reported where they are read as numbers — the summary card and the range statistics.
- `MAX_PLAUSIBLE_SPEED_MPS = 60`, `MAX_PLAUSIBLE_JUMP_M = 2000`. A fixture for either twin has to
  be plausible **at its own sample rate**, and this is easy to get wrong: 0.001° between fixes is
  132 m at 50°N, which one second apart is 475 km/h. `RouteGeometryTest` had exactly that, so
  every leg tripped the guard, every segment was closed at one orphaned vertex, and the assertion
  reported zero segments rather than the two it was looking for — a test that reads like a
  regression in the splitter and is really arithmetic in the fixture. Both twins now use the same
  figures: 11 m legs at 1 Hz, one 16 km jump.
- The speed-versus-pace sport set, and every rounding rule beside it. There were **four**
  Kotlin copies — `feature:activity`, `feature:feed`, `feature:sources`, and `feature:health`
  for its durations — because a feature module may not depend on another one and `core:ui` had
  no source. One had already drifted: `FeedScreen.kt`'s sport set was missing `swimming`, so a
  swim read as a pace in the feed and as a speed on the screen that card opens. There is now
  exactly one, `core/ui/Format.kt`, and `core/ui/FormatTest.kt` pins the rules against
  `web/src/core/format.ts` — including the swimming case, which is the regression that
  motivated the module acquiring source at all.

- The gap rule, `MAX_SAMPLE_GAP_SECONDS = 30` / `GAP_MULTIPLE = 3` /
  `MIN_INTERVALS_FOR_CADENCE = 10`, and the function over them —
  `Metrics.sampleGapCapSeconds` and `sampleGapCapSeconds` in `analysis.ts`. This used to be
  listed here as a *deliberate divergence*: the ingest path capped at 30 s and neither screen's
  range statistics capped at all, on the grounds that the screens mirror each other rather than
  the ingest path. That was the bug, not the design — the summary card and the range statistics
  sit on the same screen, an athlete reads both, and on a sparse source they were an order of
  magnitude apart. All three now apply one rule.

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
