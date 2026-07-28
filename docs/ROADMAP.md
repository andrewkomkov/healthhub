# Roadmap

Work grouped into sessions. Each session is a coherent slice that ends with something
demonstrable, names the agent that should do it, and states how you know it is finished.

Agents live in `.claude/agents/`. All of them must read `docs/AGENT-NOTES.md` first —
it is where the platform's traps are written down, and most were expensive to find.

**Standing rules for every session**

- The spec-kit artefacts in `specs/001-workout-sync-feed/` are the contract. If reality
  disagrees with them, amend the document in the same change — a stale spec is worse than none.
- Verify on the real deployment and the real device. Local runtimes and pre-granted
  permissions both lie in the pleasant direction.
- Constitution first: analysis on the device, D1/R2 only, Expressive everywhere, features
  independent, everything drivable over ADB.

---

## Session 1 — Activity detail: map, charts, splits

**Why first**: it is the analytical payoff the whole product exists for, and today it is a
placeholder. Everything else is polish by comparison.

**Agents**: `healthhub-web` (lead) → `healthhub-android` → `healthhub-device-qa`

| Step | Agent | Work | State |
|---|---|---|---|
| 1 | web | `.hht` reader: `ArrayBuffer` → typed-array views, no parse pass. Validate against the golden fixture the Kotlin codec is tested with | **done** — `web/src/core/telemetry/hht.ts`, pinned to `fixtures/hht/golden-v1.hht` from both sides |
| 2 | web | µPlot wrapper themed entirely from generated tokens: one channel at a time behind chips, time↔distance axis switch, cursor, range selection | **done** — `web/src/core/charts/`; the stack of five panels was replaced by one chart and a chip row, and the min-max envelope by bucket means |
| 3 | web | MapLibre GL with a token-derived style; route drawn with gaps preserved, marker driven by the chart cursor | **done** — `web/src/core/map/`; OpenFreeMap vector basemap by default, `VITE_MAP_TILES_URL` overrides it with a style or a raster template, `none` turns it off |
| 4 | web | Detail route: summary, map, charts, splits table, zone distribution. Preview telemetry paints immediately, full resolution swaps in | **done** — `web/src/features/activity/` |
| 5 | android | Same surface with MapLibre Native and a Compose canvas chart from `core:designsystem` primitives | **done, and drawn on a Pixel 8** — `android/feature/activity/`; charts, map, splits, zones, range selection and two ADB commands. The chart is now one channel at a time behind chips, reduced to bucket means; the summary is a grid of tiles |
| 6 | device-qa | Open a real ride on the Pixel and in the browser; confirm identical figures and smooth panning | **phone half done** — a real 41 km ride and several walks opened on the Pixel: tiles, chips, scrub, map and basemap all correct. The browser half is still unverified: the deployment is live but nobody has signed into it and compared the two side by side |

**Done when**: a 100k-sample activity is interactive within 3 s, chart cursor moves the map
marker, a range selection reports statistics, and an indoor workout shows charts with an
explanatory state instead of an empty map.

**Watch for**: the codec is implemented twice on purpose. If the TypeScript reader disagrees
with the Kotlin writer, the shared fixture must fail — fix the fixture first if it does not.

**Still open on the web side**: the athlete cannot yet colour the route by a channel.

**Settled late, on a device**: the pace axis used to bottom out at 122:54 /km, because a
commute standing at traffic lights has a *quartile* of stopped samples rather than a tail — the
Tukey fence sits underneath them. Both clients now floor the speed axis at the moving threshold.
And both reduce a channel to the mean of 220 buckets instead of the min-max envelope: the
envelope kept every one-second spike and rendered a five-hour ride as a brush of ink with no
shape in it.

---

## Session 2 — Archive and source priority, with screens

**Why**: the model, the API and the sync logic all landed already; there is no UI for any of
it. Right now the athlete cannot see the archive or reorder their sources.

**Agents**: `healthhub-web` + `healthhub-android` in parallel → `healthhub-device-qa`

| Step | Agent | Work | State |
|---|---|---|---|
| 1 | web | Archive view (`GET /api/activities?view=archive`), restore action, "also recorded by N apps" on cards | **done** — `web/src/features/archive/` |
| 2 | web | Source settings: drag-to-reorder priority, enable/disable, activity counts, via `GET/PUT /api/sources` | **done** — `web/src/features/sources/`; reordering is drag *and* keyboard, both through `reorder.ts` |
| 3 | android | The same two screens inside `feature:sources`. The module, both routes (`healthhub://sources`, `healthhub://archive`) and the contribution already exist as skeletons, so this is filling in two composables and a ViewModel — and doing it without touching another feature module. SC-012 itself is already proved by `feature:about` (T067); this is the same claim under load | **compiles, unit tests green, never run** — `android/feature/sources/`; both screens, a repository for the three calls `core:network` lacks, drag *and* button reordering through `Reorder.kt`, and five ADB commands. No file outside the module was touched, so SC-012 holds under load. `ReorderTest`, `LabelsTest` and `VocabularyTest` pass. A review pass fixed two defects that a compile would not have caught: the last-gasp priority write was guarded on `saveJob.isActive`, which is already `false` inside `onCleared`, and it compared package order only, so a switch flipped inside the debounce window was dropped; and a failed archive page stopped paging silently under a footer that still read "Loading more…" |
| 4 | android | Per-activity archive/restore from the detail screen | **blocked — `feature:activity` exposes no seam.** `ActivityScreen` is `internal` and its only contribution point is `NavContribution`; there is no multibinding a second module can put an action into, so the button cannot be added from `feature:sources` without editing `feature:activity`. The *action* is built and reachable (archive screen restore/undo, plus `restore` and `set-aside` over ADB); only the entry point on the detail screen is missing. Fix once, in `feature:activity`: an `@IntoSet` set of detail-screen actions declared in `core:navigation` and rendered in the detail top bar, after which any later feature attaches to it the way it attaches to the graph |
| 5 | device-qa | Reorder sources, re-sync, confirm the representative changed and nothing was lost | next — step 3 compiles now; step 4's action is drivable over ADB in the meantime |

**Done when**: changing priority changes which recording represents a workout on the next
sync; a manually restored workout survives every later sync (`visibility_locked`); nothing is
ever deleted.

---

## Session 3 — GPS routes, per activity

**Why**: no activity currently has a track, and the reason is subtle — see R-015. Bulk route
access is signature-level and unavailable to third-party apps.

**Agents**: `healthhub-android` → `healthhub-device-qa`

| Step | Agent | Work | State |
|---|---|---|---|
| 1 | android | "Import route" on activity detail → `REQUEST_EXERCISE_ROUTE` with the session id | **run on a device** — `core:healthconnect/ExerciseRouteContract.kt` wraps the SDK's contract (never the raw intent — it does not exist below API 34), and `feature/activity/RouteCard.kt` is the action |
| 2 | android | Ingest the returned track, recompute polyline, bounds and distance, re-upload | **run on a device, 137 tracks** — `SyncEngine.importRoute` re-reads the session and runs the *same* `ingest` a sync runs, so there is no second implementation. `Metrics.reconcileDistance` decides whether the track may be the activity's distance |
| 3 | android | Surface honestly when a source wrote no route at all — that is data absence, not a permission problem | **run on a device** — `RouteImportState` keeps "no track recorded", "consent needed", "not on this phone" and "no Health Connect" apart, decided from `exerciseRouteResult` *before* anything is asked |
| 4 | device-qa | Import a route on a real ride; confirm the polyline appears on both clients | **done on the phone** — `route-status` printed the four states on real recordings, and the maps appeared. Confirmed on the browser side only that the same rows carry a polyline |
| 5 | android | Fill in tracks for everything already synced without one, a workout at a time | **done, 137 tracks on a Pixel** — `SyncEngine.backfillRoutes` asks the server which workouts have no track, matches each to its Health Connect record by `sourceUid` and re-ingests through the same `importRoute`. Runs at the end of every sync with a budget of five, or on demand over ADB (`routes`). A track whose length disagrees with the stored distance is **not** imported: that is a correction, not a fill-in, and a correction made to two hundred workouts unattended is how a history quietly changes shape. Sixty such are waiting for the athlete on the detail screen |

**Done when**: a ride with a recorded track shows its route, and one without says so clearly
rather than looking broken.

**Do not**: re-add `READ_EXERCISE_ROUTE` to the manifest. It cannot be granted to us.

**A review pass over steps 1–3 closed four things a compile would not have caught**, all of them
places where the screen would have said something that was not true:

- `reconcileDistance` preferred the GPS track unconditionally whenever there was no speed
  channel to check it against — which is a normal shape for a recording, and precisely the case
  where a partial track shrinks a ride. The two candidates are now compared to each other over
  the same 0.8–1.25 band and the source's own aggregate wins a disagreement;
- the import message was dropped the moment the map appeared, because the card that carried it is
  the thing a route replaces. It is rendered beside the map now, which is where "the distance is
  still the one the source recorded" actually needs to be read;
- a granted track whose points all fall outside the session's window produced "route imported"
  above a screen with no map. It reports what happened instead;
- the route card's initial copy read "this workout was recorded with GPS", which is a lie for one
  frame on every workout that was not. The screen starts in `Checking` for those.

The four-way decision is now a pure function, `decideRouteImport`, with `RouteImportStateTest`
covering absence against consent — the pair a device cannot be trusted to exercise on demand.

**Two things step 4 must actually check**, because neither can be verified without a real
device holding real recordings:

- a workout whose source wrote no positions reports `absent`, not `consent_required`. The
  distinction comes from Health Connect and this is the first time it has been read;
- after an import, the distance still agrees with average speed over elapsed time. A track that
  covers part of a ride must leave the recorded distance alone — `route-status` says which
  happened, and the summary card says so in words.

**Also settled here**: telemetry is no longer served as `immutable`, because a route import
rewrites the `.hht` at the same key. Any client that opened the activity before the import
would otherwise keep its cached copy for a year.

---

## Session 4 — Sleep, recovery and the rest of Health Connect

**Why**: the PRD's other four categories. The ingestion layer was built as a registry
precisely so this is additive.

**Agents**: `healthhub-android` (lead) → `healthhub-edge` → `healthhub-web`

| Step | Agent | Work | State |
|---|---|---|---|
| 1 | android | Extend the record-type registry: sleep stages, HRV, resting HR, SpO2, weight, body fat, blood pressure | **compiles, `SleepSummaryTest` green, never run** — `core:healthconnect/RecordRegistry.kt` holds all sixteen types across five domains and, beside them, the twenty-four Health Connect types the app deliberately does not model. `core:sync/HealthRecordSync.kt` is the daily-grain pass; `SleepSummary.kt` is the night arithmetic, unit-tested |
| 2 | android | Narrow permission requests — only what the enabled features need (Principle IV) | **compiles, never run** — `HealthFeatures` decides what is asked for. A fresh install requests workouts only; sleep, recovery, body composition and blood pressure are requested on the health screen at the moment each switch is turned on. Every `android.permission.health.*` declaration moved to `core:healthconnect`'s own manifest, beside the registry that decides which of them is requested |
| 3 | edge | Schema and routes for daily-grain health data; classify each new class as D1 or R2 explicitly | **done** — `0006_health_records.sql`, `worker/src/routes/health-records.ts`; measurements and per-night summaries in D1, the hypnogram in R2, reasoning in `data-model.md` |
| 4 | android+web | Health & recovery surfaces: sleep stages, HRV trend, readiness. `feature:health` exists as a skeleton on `healthhub://health`, already depending on `core:healthconnect` | **android compiles, `ReadinessTest` and `TrendsTest` green, never run** — `feature:health` has readiness, last night with a stage breakdown, a three-week sleep trend, HRV and resting-HR trends against the athlete's own median, the latest scalar readings, the domain switches and the not-ingested list. `Readiness` and `Trends` are pure and unit-tested; five ADB commands drive the lot. **Web half not started** |
| 5 | device-qa | Verify against Health Connect's own numbers, category by category | next — `health-domains`, `health-sync`, `sleep` and `readiness` over ADB report every figure the screen draws |

**Done when**: every record type the device holds is either ingested or named in the sync
report. Silence is the failure mode Principle VI exists to prevent.

**Three things step 5 has to check**, because none can be trusted without a device holding real
recordings:

- the **quota**. `health-sync --extra days:s:365` prints `reads`; a year of all four daily-grain
  domains should be about eighty-four calls, not eight hundred. If it is the larger number the
  window batching has been broken;
- a night whose source wrote **no stage detail** shows a duration and says so, rather than an
  empty bar. Plenty of phone apps write exactly that;
- **readiness with no baseline is absent, not average.** `readiness` prints the baselines it
  used; a score with empty `hrvBaseline` and `restingHrBaseline` behind it is the bug.

**Still open in this session**: deletions are still not propagated (FR-007), and the *hypnogram*
— the stage intervals themselves, uploaded to R2 by the phone — is not read back by any screen.
The Android surfaces draw stage **totals**, which is what a trend needs; a night rendered as a
time-ordered hypnogram is the obvious next thing and `GET /api/health-records/sleep/:id/stages`
is already there for it. The web half of step 4 has not been started.

---

## Session 5 — The analytical archive

**Why**: whole-history questions ("distance by sport across 2026") over years of data.
Designed in R-013 and R-014; the edge half is now built.

**Agents**: `healthhub-edge` → `healthhub-web`

| Step | Agent | Work |
|---|---|---|
| 1 | edge | **Built.** Cron-triggered compaction of closed months into Hive-partitioned Parquet. Assembly only — no aggregation in the Worker |
| 2 | edge | **Built.** Multipart upload above 100 MB; re-running a month replaces it rather than adding to it |
| 3 | edge | **Built.** Scoped read-only R2 credentials the athlete generates for their own prefix |
| 4 | web | **Built.** DuckDB-Wasm **in the browser**, loaded lazily, never on the feed path — `web/src/core/analytics/`, surfaced on `/health` |
| 5 | — | README: a working DuckDB query against the athlete's own archive |

Three things in steps 1 and 2 turned out differently, and the reasoning is in R-013:
**snappy, not zstd** (workerd has no zstd, and the writer's compressor hook is synchronous);
**parts are kilobytes, not 128–512 MB** (one row per activity, and a part is encoded inside a
128 MB isolate); and the `samples` dataset is **not** produced, because assembling it would
mean decoding `.hht` in the Worker. Multipart is implemented and, at these volumes, dormant.

Step 4 added three constraints nobody had written down, all of them in AGENT-NOTES now: the
Wasm binary is **larger than a Workers asset may be** (34 MB against a 25 MiB cap), so its URL
is resolved at run time rather than bundled; DuckDB-Wasm has **no httpfs**, so `s3_url_style`
and `s3_use_ssl` throw and buckets are addressed **virtual-host style**; and it **cannot list a
bucket**, which is what makes `parts` in the manifest load-bearing rather than tidy.

**Still unverified**: the S3 read against real R2. It needs a deployed Worker with an R2 API
token, a compacted month, and a CORS rule on the bucket allowing the app's origin — none of
which exists on a development machine. Everything up to the transport is covered by
`web/src/core/analytics/duckdb.test.ts`, which runs the real engine over a Parquet file in the
compaction job's own schema, and the surface was driven end to end in a browser against a local
part.

**Done when**: a year of history answers an aggregate query in the browser at zero egress
cost. DuckDB does **not** go in the Worker — see R-014.

---

## Session 6 — Hardening and the claims we have made

**Agents**: `healthhub-edge` + `healthhub-android`, `healthhub-device-qa` throughout

- Client-side password pre-hash, so total KDF work is not capped by the Worker's 100,000
  iterations (R-006 amendment). **Done** — the browser and the phone derive 600,000 iterations
  over an address-derived salt and send the result; the Worker salts and hashes that at its
  ceiling. A stored record now names the scheme it was built from, so an account created before
  the amendment is migrated on its owner's next sign-in rather than invalidated —
  `worker/test/api.test.ts` proves that path end to end, including that the record actually
  changes shape and then opens on the proof alone. The two client derivations are pinned to
  `fixtures/auth/prehash-v1.json`, which the Worker cannot generate: 600,000 iterations is six
  times what workerd will run. **Unverified**: the production ceiling itself, which cannot be
  re-tested from a development machine, and the on-device cost of 600,000 hand-rolled HMAC
  iterations on the SM-G780F.
- ADB coverage audit: every action and screen reachable; release build contains none of it.
- Performance verification against the numbers in the spec: 1M-sample import without an OOM
  kill, 100k-sample activity interactive in 3 s, feed first screen in 2 s.
- Accessibility: large font sizes, contrast on charts, touch targets, content descriptions.
  **The token layer and the web foundation are done and tested** — parity between the two
  generated token sets, every text and essential-graphic pairing measured in both appearances,
  colour-vision separation measured and its failures pinned, type published in `rem`, one focus
  ring, 48px targets, tables that scroll instead of clipping at 200% text, and a spoken
  description for every chart canvas and the map. **Still open**: the Android surfaces (they
  compile, but none has been drawn on a screen, let alone at a large font size or under
  TalkBack), the feature screens on the web (`article role="button"` on the feed card
  is the known one), and any of it in front of a real screen reader.
- `CLOUDFLARE_API_TOKEN` in GitHub secrets so `deploy` goes green — it is the one red
  workflow, and it is red only for that.
- Run the quickstart from a clean clone and fix whatever does not work.

---

## Known open items

Checked against the code on 2026-07-28, after a session spent on a Pixel 8 rather than in a
build: `:app:assembleDebug`, `./gradlew test lint`, `npm run typecheck`, 420 tests across the
worker and the web, and everything below opened by hand on the phone. Anything here is genuinely
missing, not merely undocumented.

| Item | Where |
|---|---|
| `deploy` workflow red — missing `CLOUDFLARE_API_TOKEN` | GitHub → Secrets → Actions |
| **The deploy workflow is the thing that applies migrations, and it has never run.** `deploy.yml` deploys the Worker and then runs `d1 migrations apply --remote` — but without `CLOUDFLARE_API_TOKEN` the job fails, so every deployment so far has been a local `wrangler deploy` with no migration step beside it. `0006` and `0007` were therefore missing on the remote D1 while a Worker that reads `sleep_sessions` was live, and every sleep request answered 500 for a day. Applied by hand on 2026-07-28. Fixing the token fixes both | GitHub → Secrets → Actions |
| The new web surfaces — basemap, one-channel chart, figure-first cards, stat tiles — are deployed but have never been seen in a browser under a signed-in account. The phone half of every one of them was checked by hand | Session 1 step 6 |
| ~~Activity detail has never been drawn on a screen~~ — **drawn on a Pixel 8**, real walk, summary and pace chart correct. It exposed two defects: an empty chart on any workout with fewer samples than the chart has columns (fixed, `ChartSeriesTest`), and the moving-time disagreement below | Session 1 step 6 |
| ~~The Android archive and source screens have never been exercised~~ — **verified over ADB on a Pixel**: flipping source priority and re-syncing 101 sessions moved the representative between Google Fit and the bike computer's own app, and a manual restore survived the sync with `locked=true`. Nothing was deleted. The screens themselves were driven by command, not by finger | Session 2 step 5 |
| No detail-screen entry point for archive/restore on Android: `feature:activity` has no action seam a second module can contribute to | Session 2 step 4 |
| ~~No activity has a GPS track yet~~ — **137 tracks imported on a Pixel**, and the maps draw. The lesson underneath: `READ_EXERCISE_ROUTES` was already granted, so the platform hands routes over with no confirmation at all — everything synced before the athlete flipped that switch simply never got one, and a sync only reads forward. `backfillRoutes` is what repairs it | Session 3 step 5 |
| The route backfill leaves sixty workouts alone, because their track disagrees with the stored distance by more than the reconciliation band. Each needs the athlete on the detail screen, where the outcome is explained. There is no "review these sixty" surface | Session 3 follow-up |
| A route cannot be imported onto an archived duplicate: the re-upload carries the duplicate verdict, and `GET /api/activities/:id` does not return `duplicateOf`, so the screen refuses rather than risk resurrecting the recording. Returning `duplicateOf` and `sourceUid` on the detail route would close it | Session 3 follow-up |
| `POST /api/auth/login` still carries the raw password beside the proof, so a pre-amendment account can be migrated. It comes out once every account has signed in once — three client edits and one Worker edit | Session 6 follow-up |
| `feature:settings` has a build file and no source, so `healthhub://settings` resolves to nothing. It is no longer *reachable* — the app menu is built from `NavContribution.menuEntries`, and a module with no source registers none — but the empty module and its `Destination` are still there | Session 2 or 6 |
| The Android health surfaces compile but have never been drawn on a screen. `feature:about` is deliberately trivial and stays that way — it is the SC-012 proof, re-verified by reading: attaching it cost a `Destination` in `core:navigation`, an `include`, and one `:app` dependency line, and no `feature:*` file names it | Session 4 step 5 |
| No web health surface: sleep, HRV and readiness exist on Android only, though `/health` on the web already hosts the analytics console | Session 4 step 4 |
| The hypnogram is uploaded to R2 and never read back — the screens draw stage totals, not the interval detail | Session 4 follow-up |
| **Moving time disagrees with itself on a sparse source.** `Metrics.movingSeconds` drops every sample interval over 30 s, so a walk sampled once a minute stores 2:56 of movement where the detail screen's range statistics — which apply no cap — report 27:35 over the same samples. Two numbers on one screen, and SC-008 says there may only be one. Choosing the rule changes stored figures on every existing activity from a sparse source, so it is a decision, not a patch | Session 4 or 6 — **found on device 2026-07-27** |
| Deletions are never propagated: a workout removed in Health Connect stays in the feed (FR-007) | Session 4, alongside the registry work — **still open** |
| No in-app sign-out on Android, and the debug `logout` command clears the local token without revoking the device server-side | Session 6 |
| No account settings, device list or account deletion on the web | Session 6 |
| No Playwright end-to-end run — the dependency and script are installed, the config and specs are not | Session 6 |
| No root `eslint.config.*` and no root `tsconfig.json`, so `npm run lint` fails; MegaLinter is what actually lints in CI | Session 6 |
| ~~No token parity test and no contrast test~~ — **done**, `web/src/core/m3e/{tokens,contrast,dynamicTheme,a11y}.test.ts`. Two live drifts closed (sequential ramp, elevation scale); the dark series palette's CVD collisions are pinned rather than fixed, see AGENT-NOTES | Session 6 |
| Accessibility below the foundation is unaudited: the feed card is an `article role="button"` wrapping an `h2`, and no screen has been driven with a screen reader or at 200% text | Session 6 |
| `core:ui` has a build file and no source; shared composables live in whichever feature needed them first | Session 6 |
| `scripts/adb.sh` does not exist — the raw `content call` invocations are in AGENT-NOTES instead, and there is no smoke script | Session 6 |
| No CONTRIBUTING and no issue templates | Session 6 |
| `DELETE /api/auth/me` leaves the athlete's `source_preferences` rows behind | Session 6 |
