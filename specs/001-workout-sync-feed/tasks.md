---

description: "Task list for Workout Sync & Activity Feed"
---

# Tasks: Workout Sync & Activity Feed

**Input**: Design documents from `/specs/001-workout-sync-feed/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api.md

**Tests**: Included. The specification sets measurable correctness targets (SC-002, SC-003,
SC-005, SC-006, SC-008) that cannot be verified by inspection, and the telemetry codec is
implemented twice — Kotlin writer, TypeScript reader — so a shared round-trip fixture is the
only thing that keeps them honest.

**Organization**: Grouped by user story so each slice is independently demonstrable.

**Reconciled against the code on 2026-07-27.** Every ticked box below was checked by reading
the file it claims. Anything left unticked carries a clause saying what is actually missing,
and several tasks were rewritten because a better decision was made while building — those
say so in the task text. Nothing that needs a phone or a browser to prove is ticked.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1–US5 per spec.md

---

## Phase 1: Setup (Shared Infrastructure)

- [X] T001 Create the monorepo layout from plan.md: `android/`, `worker/`, `web/`, `packages/design-tokens/`, root `package.json` with npm workspaces
- [ ] T002 [P] Root tooling — Prettier, `.editorconfig` and the npm scripts (`dev`, `build`, `test`, `typecheck`, `deploy`) are in place; there is no root `tsconfig.json` and no `eslint.config.*` anywhere in the tree, so `npm run lint` fails outright. MegaLinter (`.mega-linter.yml`, `.github/workflows/megalinter.yml`) is what actually lints in CI, which is why nobody noticed
- [X] T003 [P] Android Gradle skeleton: `android/settings.gradle.kts` declaring every `core:*` and `feature:*` module, `gradle/libs.versions.toml` version catalogue pinning Compose Material3 `1.5.0-alpha24` and Health Connect `1.1.0`, convention plugins in `android/build-logic/`
- [X] T004 [P] `packages/design-tokens/tokens.json` — the Material 3 Expressive token source (colour roles for light and dark, shape scale, type scale, motion scheme, chart series palette)
- [X] T005 `packages/design-tokens/build.mjs` — generates `web/src/core/m3e/generated-tokens.css` and `android/core/designsystem/.../GeneratedTokens.kt` from T004; wired into both builds
- [X] T006 [P] GitHub Actions `.github/workflows/android.yml` — JDK 21, Gradle cache, `assembleDebug` + `test` + `lint`, upload APK artifact
- [X] T007 [P] GitHub Actions `.github/workflows/web.yml` — typecheck, unit tests, web build, `wrangler deploy --dry-run` (the workflow is named "web + worker"; it covers both workspaces)
- [X] T008 [P] GitHub Actions `.github/workflows/deploy.yml` — on `main`, build web then `wrangler deploy` using `CLOUDFLARE_API_TOKEN`. The workflow is complete; it is red only because that secret has never been set, which is a repository-settings item and not code

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T009 `worker/wrangler.jsonc` — Worker name, `compatibility_date`, D1 and R2 bindings, `assets` block pointing at `../web/dist` with `not_found_handling: single-page-application` and `run_worker_first: ["/api/*"]`
- [X] T010 `worker/migrations/0001_init.sql` — the D1 schema from data-model.md, since extended by `0002_identities`, `0003_user_theme`, `0004_sources` and `0005_archive_and_sources`. `0006_health_records` and `0007_archive_tier` are now real — `0007` carries the archive manifest and credential tables. A reserved number still needs a `SELECT 1;` placeholder, because D1 rejects a migration with no statement in it, and filling one in later does not re-run it on a database that already recorded it
- [X] T011 Worker skeleton `worker/src/index.ts` — Hono app, `/api` mounting, JSON error envelope, request-id logging
- [X] T012 [P] `worker/src/lib/errors.ts` + `worker/src/lib/validate.ts` — error codes from contracts/api.md, schema validation helpers
- [X] T013 `worker/src/auth/password.ts` — PBKDF2-SHA-256 hashing and verification over WebCrypto, encoded as `pbkdf2-sha256$iters$salt$hash`, pinned at the runtime's 100,000-iteration ceiling (see T070a for the amendment that lifts the effective work factor)
- [X] T014 `worker/src/auth/tokens.ts` + `worker/src/lib/guard.ts` — opaque session and device tokens, sha256 storage, the ownership guard that resolves a request to a `user_id` and 404s on mismatch
- [X] T015 [P] Vitest + `@cloudflare/vitest-pool-workers` harness with local D1 migrations and R2, plus fixture helpers for a seeded user and device
- [X] T016 [P] Telemetry codec specification fixture at `fixtures/hht/golden-v1.hht` (repo root, not under `packages/`) — written by the Kotlin `HhtGoldenFixtureTest` and read by the TypeScript `hht.test.ts`, so neither side can drift without a suite going red
- [X] T017 [P] `web/` Vite + React + TypeScript skeleton with routing, the `m3e` token import, and a themed app shell
- [ ] T018 [P] `web/src/core/m3e/` — Material 3 Expressive component layer over the generated tokens. Built and themed in light and dark: surface, card, button, top app bar, form field, table, empty state, error. Still missing: a navigation component, a list and a dialog — no screen has needed one yet
- [ ] T019 [P] Android `core:model`, `core:designsystem` (`MaterialExpressiveTheme` wired to `GeneratedTokens`), `core:navigation` (the `NavContribution` interface and `@IntoSet` registry that makes Principle VII real) are done; `core:ui` has a build file and no source at all, so the shared composables live in the feature modules that first needed them
- [X] T020 [P] Android `core:network` — typed client for contracts/api.md, device-token store in `EncryptedSharedPreferences`, error mapping
- [X] T021 [P] Android `core:database` — Room entities for `pending_activities`, `cached_activities` and `sync_state`. There is deliberately no `pending_telemetry` table: telemetry is written to the cache directory and uploaded inside the same sync pass, and a failed activity is already retried through `pending_activities`, so a second staging table would only duplicate that
- [X] T022 `app` module — Application class, Hilt graph, navigation host assembled from `NavContribution` contributions, no feature logic
- [ ] T022a `core:devcontrol` (debug source set) — the ADB command surface required by Principle VIII. Built as a **ContentProvider**, not the BroadcastReceiver this task originally called for: a receiver cannot see its caller, and a signature-level permission locks out `adb shell` itself while still admitting any co-signed app, so `DevControlProvider` checks `Binder.getCallingUid()` against shell and root instead. `DevCommand` + `@IntoSet` registration, the `healthhub://` deep-link router, and a `DevReporter` emitting JSON on the `HealthHubState` logcat tag with a correlation id per command all exist. What is missing: `healthhub://settings` resolves to nothing, because `feature:settings` contributes no route
- [ ] T022b `scripts/adb.sh` — not started. There is no `scripts/` directory; the raw `adb shell content call --uri content://dev.healthhub.debug.devcontrol --method …` invocations are written down in docs/AGENT-NOTES.md instead, and there is no smoke script driving sign-up → sync → feed → detail

**Checkpoint**: Foundation ready — user stories can proceed

---

## Phase 3: User Story 1 - Get my workouts off the phone (Priority: P1) 🎯 MVP

**Goal**: Health Connect data lands in D1 and R2, incrementally, resumably, without duplicates

**Independent Test**: Sign up on the SM-G780F, grant permissions, sync, and confirm imported session count and date range match Health Connect

### Tests for User Story 1

- [X] T023 [P] [US1] Kotlin codec round-trip test in `android/core/telemetry/src/test/` — `HhtRoundTripTest` encodes then decodes including sentinel handling, and `HhtGoldenFixtureTest` writes the T016 golden fixture the TypeScript reader is pinned to
- [X] T024 [P] [US1] Kotlin metric tests — splits, zones, moving time, elevation gain against hand-computed fixtures, in `MetricsTest`
- [X] T025 [P] [US1] Worker contract tests for `POST /api/activities` — creation, idempotent re-post returning 200 with no duplicate row (SC-006), rejection of a browser session where a device token is required
- [ ] T026 [P] [US1] Worker contract tests for `PUT /api/activities/:id/telemetry` and the cursor/report routes — the telemetry round trip, the cross-athlete refusal and the missing-variant 404 are covered; nothing exercises `GET`/`PUT /api/sync/cursors` or `POST`/`GET /api/sync/reports`

### Implementation for User Story 1

- [X] T027 [P] [US1] `core:healthconnect` — client wrapper, the workout permission set, and the **record-type registry** keyed by Health Connect type so remaining types are additive (Principle VI)
- [X] T028 [P] [US1] `core:telemetry` — the `.hht` encoder writing channel-by-channel to a file, never a single in-memory buffer (SC-003), plus LTTB downsampling for the preview
- [X] T029 [US1] `core:telemetry` — on-device metric computation: splits, HR/power zones, moving time, per-channel statistics, elevation gain/loss, Douglas–Peucker simplification and polyline encoding
- [ ] T030 [US1] `core:sync` — the delta engine does **not** use Health Connect change tokens. Reads are batched into day windows because the API quota is per call, not per session (eight reads exhausted it around session 75), and the cursor is a `syncedUntil` timestamp in `sync_state` — the `changeToken` column is written as `null` and is vestigial. Room staging, the upload pipeline and "advance the cursor only when every upload in the window succeeded" are all in place. Still missing: deletions are never propagated, so FR-007 is unmet — a workout removed in Health Connect stays in the feed
- [ ] T031 [US1] `core:sync` — the WorkManager periodic worker (6 h), the manual trigger and the unmetered-network constraint honouring the user preference (FR-008) all exist; there is no Health Connect data-change trigger registered, so a new workout is only picked up on the next periodic run or a manual sync
- [X] T032 [P] [US1] Worker `worker/src/routes/activities.ts` — `POST /api/activities` with `ON CONFLICT` upsert on `(user_id, source_uid)`, splits and zones stored verbatim
- [X] T033 [P] [US1] Worker `worker/src/routes/telemetry.ts` — `PUT` streaming into R2, key and byte count recorded
- [X] T034 [P] [US1] Worker `worker/src/routes/sync.ts` — cursors and reports per contracts/api.md
- [X] T035 [US1] `feature:sync` — sync status screen, manual sync trigger, sync report display including unhandled record types, network preference

**Checkpoint**: Data flows phone → edge, verifiably and idempotently

---

## Phase 4: User Story 2 - Browse my activity feed (Priority: P1)

**Goal**: The same feed, on phone and web, from one indexed D1 query

**Independent Test**: Open the feed in both clients and confirm identical activities, order and figures

### Tests for User Story 2

- [ ] T036 [P] [US2] Worker contract tests for `GET /api/activities` — keyset pagination stability, the archived/active split and cross-user isolation (SC-009) are covered; sport and date filtering are implemented but untested
- [ ] T037 [P] [US2] Web unit tests for the feed data layer — not started; there is no test file for pagination accumulation or cache behaviour

### Implementation for User Story 2

- [X] T038 [US2] Worker `GET /api/activities` — keyset pagination on `(start_time, id)`, filters, no R2 access
- [X] T039 [P] [US2] Worker `PATCH /api/activities/:id` and `DELETE /api/activities/:id` (FR-016)
- [ ] T040 [P] [US2] `web/src/features/feed/` — the feed route, activity cards in the Expressive card idiom, polyline route thumbnails rendered to canvas, infinite scroll via an `IntersectionObserver` sentinel and the empty state are all built; there are no sport or date filters in the UI, though the Worker supports both
- [ ] T041 [P] [US2] `feature:feed` on Android — the lazy feed, the Room-backed offline cache (FR-014) and the empty state exist; there are no route thumbnails from the stored polyline and no filters
- [X] T042 [US2] Shared polyline decoder in both clients with a common test vector — `web/src/core/polyline.test.ts` and `MetricsTest.polyline encoding matches the reference vector` use the same string

**Checkpoint**: A recognisable Strava-class feed exists on both clients

---

## Phase 5: User Story 3 - Analyse a single workout (Priority: P1)

**Goal**: Map, aligned multi-series charts, splits, zones, cursor linkage, range selection

**Independent Test**: Open a synced ride; verify route, chart alignment, kilometre splits, chart↔map cursor linkage

### Tests for User Story 3

- [X] T043 [P] [US3] TypeScript codec test — `web/src/core/telemetry/hht.test.ts` decodes the T016 golden fixture into typed arrays with correct values and sentinel skipping, and covers the unaligned-channel copy path
- [ ] T044 [P] [US3] Web unit tests for range-selection statistics against a fixture, cross-checked against the Kotlin implementation's values (SC-008) — the unit tests exist; the codec is pinned to the shared golden fixture from both sides, but the statistics have not yet been compared against Kotlin's on a real activity. That comparison is the device-QA step
- [ ] T045 [P] [US3] Playwright end-to-end: sign in → feed → activity detail → map and charts render — not started. `@playwright/test` and the `test:e2e` script are installed, but there is no `playwright.config` and no spec file

### Implementation for User Story 3

- [X] T046 [US3] Worker `GET /api/activities/:id` (detail with splits and zones) and `GET /api/activities/:id/telemetry` — ownership-checked R2 proxy with ETag and immutable caching
- [X] T047 [P] [US3] `web/src/core/telemetry/` — `.hht` reader building typed-array views over the response buffer, copying only the channels the format leaves unaligned. Range statistics run on the main thread: they are a single pass over a selection and measured well under a frame, so a Web Worker would only add transfer cost
- [X] T048 [P] [US3] `web/src/core/charts/` — µPlot wrapper themed entirely from generated tokens: stacked aligned series, time/distance axis switch, shared cursor, range selection
- [X] T049 [P] [US3] `web/src/core/map/` — MapLibre GL wrapper with a token-derived style, route rendering with gaps preserved, position marker driven by the chart cursor
- [X] T050 [US3] `web/src/features/activity/` — detail route: summary panel, map, charts, splits table, zone distribution; preview-then-full progressive loading; no-GPS explanatory state
- [ ] T051 [P] [US3] `feature:activity` on Android — the same detail surface with MapLibre Native and a Compose canvas chart drawn from `core:designsystem` primitives. Written: `ActivityScreen`, `TelemetryCharts` (min/max column reduction, shared cursor, long-press range selection), `RouteMap`, `SplitsTable`, `ZoneDistribution`, plus `activity` and `activity-range` ADB commands. Left open until it has compiled and run on the Pixel
- [ ] T052 [US3] Progressive loading on both clients: render preview telemetry immediately, upgrade to full resolution in the background (SC-005) — done on the web; written on Android in `ActivityViewModel`, with the same late-preview rule, and unverified on a device

**Checkpoint**: The analytical payoff is delivered; MVP is functionally complete

---

## Phase 6: User Story 4 - Accounts across devices (Priority: P2)

**Goal**: Multi-user sign-up with strict per-user isolation and revocable devices

### Tests for User Story 4

- [ ] T053 [P] [US4] Worker auth contract tests — register, sign in, `me`, the duplicate-email refusal, the short-password rejection, rate limiting and the identical answer for an unknown email versus a wrong password are all covered. `POST /api/auth/logout` is never called by a test, and the *timing* half of the equal-failure claim is asserted nowhere
- [X] T054 [P] [US4] Isolation test — account B cannot read account A's activity or its telemetry by direct identifier (SC-009)
- [ ] T055 [P] [US4] Account deletion test — not started. `DELETE /api/auth/me` is implemented and deletes D1 rows in an explicit batch plus the whole R2 prefix, but no test asserts either half (FR-029)

### Implementation for User Story 4

- [X] T056 [US4] Worker `worker/src/routes/auth.ts` — register, login, logout, get/patch/delete me, session cookie handling. It also carries the Auth0 authorisation-code flow (`/providers`, `/auth0/login`, `/auth0/callback`), which was not in the original scope
- [X] T057 [US4] Worker `worker/src/routes/devices.ts` — register device (token issued once), list, revoke
- [X] T058 [P] [US4] Auth rate limiting via D1 counters, `429` with `Retry-After`
- [ ] T059 [P] [US4] `feature:auth` on Android — sign up, sign in and the device-registration exchange work. There is no sign-out anywhere in the UI, and the debug `logout` ADB command only clears the local token and staging state; it never calls `DELETE /api/devices/:id`, so the authorisation survives on the server
- [ ] T060 [P] [US4] `web/src/features/auth/` — sign up and sign in exist. Account settings, the device list with revoke, and account deletion with confirmation do not

**Checkpoint**: The platform is genuinely multi-user

---

## Phase 7: User Story 5 - Trust the interface (Priority: P2)

**Goal**: One Expressive design language across both clients, charts included

### Tests for User Story 5

- [ ] T061 [P] [US5] Token parity test — the generated Kotlin and CSS token sets are asserted equal, so the two clients cannot drift. Not started; there is no test anywhere under `packages/design-tokens`
- [ ] T062 [P] [US5] Contrast test over the token palette for text and essential graphics (SC-011). Not started — `tokens.json` claims the series palette was validated for colour-vision deficiency, but nothing re-checks it when a token changes

### Implementation for User Story 5

- [ ] T063 [US5] Audit every web surface against the token layer; remove any residual library default styling from µPlot and MapLibre. The known overrides are in `base.css` (`.uplot`, `.u-select`, `.u-cursor-*`, `.maplibregl-ctrl-*`); no audit has confirmed the list is complete
- [ ] T064 [US5] Audit every Android surface; confirm Expressive motion scheme and shape scale are applied app-wide, not per-screen
- [ ] T065 [P] [US5] Dark appearance pass on both clients including charts and map overlays (SC-010). The generated stylesheet carries a full dark set behind `prefers-color-scheme` and a `data-theme` override; nothing has been walked screen by screen
- [ ] T066 [P] [US5] Accessibility pass: large font sizes, touch targets, content descriptions, focus order (FR-035). No Compose `contentDescription` appears in any feature module

---

## Phase 7b: Analytical Archive Tier (Priority: P3 — after MVP)

**Goal**: The athlete's full history queryable from DuckDB / ClickHouse / Polars at zero
egress cost, per R-013. Independent of the MVP and deliberately sequenced after it.

- [X] T066a Cron Trigger in `worker/wrangler.jsonc` plus `worker/src/archive/compaction.ts` — daily at 04:00 UTC, `scheduled` in `index.ts` calls `compactArchive`. Closed months only, 48-hour grace so every timezone has left the month; twelve months per run, oldest first. Assembly only: the sole aggregates are `COUNT(*)` and `MAX(updated_at)` over row metadata, used to decide what to rebuild
- [X] T066b Parquet writer for the `activities` dataset, schema in `worker/src/archive/parquet.ts` and documented in data-model.md. **Codec is SNAPPY**, decided explicitly: workerd has no zstd and the writer's compressor hook is synchronous while the runtime's only compression is an async stream (R-013 amendment). The long-format `samples` table is **not** built here and cannot be — it would mean decoding `.hht` in the Worker
- [ ] T066c Multipart upload above 100 MB with 8 MB parts in the compaction job (`worker/src/archive/upload.ts`), tested at R2's 5 MiB part minimum. Nothing the activities dataset produces reaches the threshold. **The Android telemetry uploader still sends a single streamed PUT** — that half is not done
- [X] T066d Scoped R2 credentials — `POST /api/archive/credentials` mints `object-read-only` temporary keys for `u/{user_id}/archive/` and nothing else; the prefix is derived from the principal and no request field can influence it. `GET /api/archive` serves the part manifest. README still needs the worked DuckDB example (session 5 step 5)
- [X] T066e Re-run safety: a month is rebuilt only when its rows changed, surplus objects are deleted before the new parts are written, and the D1 manifest flips in one batch. `worker/test/archive-compaction.test.ts` fails on an orphaned part — verified by removing the prune and watching only that test go red
- [X] T066f DuckDB-Wasm in the browser (R-014): `web/src/core/analytics/` opens the engine behind a dynamic `import()`, points it at the athlete's own R2 prefix with the scoped keys from T066d, and builds every query from `DESCRIBE` so the archive's own column spelling wins. Surfaced as `web/src/features/history/`, rendered on `/health`, which `App.tsx` already code-splits. `budget.test.ts` fails if any cold-start file imports the engine or the analytics core; `duckdb.test.ts` runs the real engine over a Parquet fixture in the compaction job's own schema, including the archived-duplicate row that must not be counted. **The S3 transport against real R2 is unverified** — it needs a deployed Worker, a compacted month and a CORS rule on the bucket, none of which exist on a development machine

---

## Phase 8: Polish & Cross-Cutting

- [X] T067 Modularity proof (SC-012): add a trivial `feature:about` module and confirm it wires in through navigation contributions with no edit to any existing feature module — `android/feature/about/`; attaching it touched only `core:navigation` (one destination), `settings.gradle.kts` and `app/build.gradle.kts`, and `healthhub://about` renders on the Pixel 8
- [ ] T067a ADB coverage audit (Principle VIII): enumerate every user action and screen, assert each has a command or deep link, and verify the release build contains none of the surface. Known gap going in: `healthhub://settings` resolves to nothing, and sign-out exists only as an ADB command
- [ ] T068 [P] Device verification run on the SM-G780F per quickstart.md — install with `--user 0`, first sync, feed, detail; record results. **Open — needs hardware**
- [ ] T069 Performance verification: 100k-sample activity interactive within 3 s (SC-005); 1M-sample import without an OOM kill (SC-003). **Open — needs hardware**
- [ ] T070 [P] Repository documentation — README carries the architecture section; there is no CONTRIBUTING and no issue templates
- [ ] T070a Client-side password pre-hash in the browser and on Android, so total KDF work is not capped by the Worker's 100,000-iteration ceiling (R-006 amendment); the Worker keeps salting and hashing what it receives. Neither client does any pre-hashing today
- [ ] T071 Security pass: cookie flags, rate limits, ownership guard coverage across every route, no secret in the repository. The individual controls exist; no audit has walked every route. One thing already known: `DELETE /api/auth/me` does not delete the athlete's `source_preferences` rows
- [ ] T072 Run the full quickstart.md from a clean clone and fix whatever does not work

---

## Dependencies & Execution Order

- **Setup (T001–T008)**: start immediately; T005 depends on T004
- **Foundational (T009–T022)**: blocks all stories. T010 depends on T009; T013–T014 depend on T011; T019 depends on T005
- **US1 (T023–T035)**: needs Foundational. This is the MVP spine — nothing downstream has data without it
- **US2 (T036–T042)**: needs US1 data to display, though the routes and UI can be built against fixtures in parallel
- **US3 (T043–T052)**: needs US1 telemetry in R2; T046 depends on T033
- **US4 (T053–T060)**: needs Foundational auth (T013–T014); can proceed in parallel with US2/US3
- **US5 (T061–T066)**: needs US2 and US3 surfaces to exist
- **Polish (T067–T072)**: last

### Parallel Opportunities

- T002, T003, T004, T006, T007, T008 all touch different files
- T017–T021 are five independent module skeletons
- Within US1: T027, T028, T032, T033, T034 are independent; T029–T031 serialise on `core:telemetry` and `core:sync`
- Within US3: T047, T048, T049 are independent, T050 joins them

---

## Implementation Strategy

**MVP is Setup + Foundational + US1 + US2 + US3.** That is the slice agreed with the product
owner: workouts, feed, map, charts. Stop there, verify on the SM-G780F, deploy, and demo
before starting US4 and US5.

Then: US4 makes it multi-user in practice, US5 makes it look like one product, Polish proves
the modularity and performance claims the specification makes.

That sequencing was not followed exactly. US4's Worker side shipped alongside US1 because
nothing can be uploaded without an account, and the archive/source-priority work in
migrations 0004 and 0005 was pulled forward because Health Connect handed the same ride over
several times and the feed was wrong until it was addressed. What remains of the MVP slice is
the Android detail screen (T051) and the verification runs (T068, T069).

## Notes

- Commit after each task or coherent group; Conventional Commits
- Every task that adds a D1 table or an R2 prefix must state the classification (Principle II)
- Every UI task inherits Principle III — no library default palettes
- No `feature:*` → `feature:*` dependency may be introduced (Principle VII)
