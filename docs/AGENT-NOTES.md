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

**There is an API quota.** Eight reads per session exhausted it around session 75 with
*"API call quota exceeded"*. Reads are batched per day-window instead: eight calls per window
regardless of how many sessions it contains.

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

## Design tokens

`packages/design-tokens/tokens.json` is the single source; `build.mjs` generates the Kotlin
theme and the CSS custom properties. **Neither generated file is committed** — run
`npm run tokens` before anything that compiles or lints them, including in CI.

The chart series palette is validated for colour-vision deficiency against both surfaces.
Material You overrides **UI roles only** — never the series palette, which carries no such
guarantee once it comes from someone's wallpaper.
