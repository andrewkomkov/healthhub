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

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1–US5 per spec.md

---

## Phase 1: Setup (Shared Infrastructure)

- [ ] T001 Create the monorepo layout from plan.md: `android/`, `worker/`, `web/`, `packages/design-tokens/`, root `package.json` with npm workspaces
- [ ] T002 [P] Root tooling: TypeScript config, ESLint + Prettier, `.editorconfig`, npm scripts (`dev`, `build`, `test`, `deploy`)
- [ ] T003 [P] Android Gradle skeleton: `android/settings.gradle.kts` declaring every `core:*` and `feature:*` module, `gradle/libs.versions.toml` version catalogue pinning Compose Material3 `1.5.0-alpha24` and Health Connect `1.1.0`, convention plugins in `android/build-logic/`
- [ ] T004 [P] `packages/design-tokens/tokens.json` — the Material 3 Expressive token source (colour roles for light and dark, shape scale, type scale, motion scheme, chart series palette)
- [ ] T005 `packages/design-tokens/build.mjs` — generates `web/src/core/m3e/generated-tokens.css` and `android/core/designsystem/.../GeneratedTokens.kt` from T004; wire into both builds
- [ ] T006 [P] GitHub Actions `.github/workflows/android.yml` — JDK 21, Gradle cache, `assembleDebug` + `test` + `lint`, upload APK artifact
- [ ] T007 [P] GitHub Actions `.github/workflows/web.yml` — typecheck, unit tests, web build, `wrangler deploy --dry-run`
- [ ] T008 [P] GitHub Actions `.github/workflows/deploy.yml` — on `main`, build web then `wrangler deploy` using `CLOUDFLARE_API_TOKEN`

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T009 `worker/wrangler.jsonc` — Worker name, `compatibility_date`, D1 and R2 bindings, `assets` block pointing at `../web/dist` with `not_found_handling: single-page-application` and `run_worker_first: ["/api/*"]`
- [ ] T010 `worker/migrations/0001_init.sql` — the full D1 schema from data-model.md, applied locally and remotely
- [ ] T011 Worker skeleton `worker/src/index.ts` — Hono app, `/api` mounting, JSON error envelope, request-id logging
- [ ] T012 [P] `worker/src/lib/errors.ts` + `worker/src/lib/validate.ts` — error codes from contracts/api.md, schema validation helpers
- [ ] T013 `worker/src/auth/password.ts` — PBKDF2-SHA-256 hashing and verification over WebCrypto, encoded as `pbkdf2-sha256$iters$salt$hash`
- [ ] T014 `worker/src/auth/tokens.ts` + `worker/src/lib/guard.ts` — opaque session and device tokens, sha256 storage, the ownership guard that resolves a request to a `user_id` and 404s on mismatch
- [ ] T015 [P] Vitest + `@cloudflare/vitest-pool-workers` harness with local D1 migrations and R2, plus fixture helpers for a seeded user and device
- [ ] T016 [P] Telemetry codec specification fixture in `packages/design-tokens/../fixtures/` — a small `.hht` golden file plus its expected decoded values, consumed by both the Kotlin and TypeScript codec tests
- [ ] T017 [P] `web/` Vite + React + TypeScript skeleton with routing, the `m3e` token import, and a themed app shell
- [ ] T018 [P] `web/src/core/m3e/` — Material 3 Expressive component layer over the generated tokens: surface, card, button, top app bar, navigation, list, table, dialog, empty state; light and dark
- [ ] T019 [P] Android `core:model`, `core:designsystem` (`MaterialExpressiveTheme` wired to `GeneratedTokens`), `core:ui`, `core:navigation` (the `NavContribution` interface and `@IntoSet` registry that makes Principle VII real)
- [ ] T020 [P] Android `core:network` — typed client for contracts/api.md, device-token store in `EncryptedSharedPreferences`, error mapping
- [ ] T021 [P] Android `core:database` — Room entities for `pending_activities`, `pending_telemetry`, `cached_activities`, `sync_state`
- [ ] T022 `app` module — Application class, Hilt graph, navigation host assembled from `NavContribution` contributions, no feature logic
- [ ] T022a `core:devcontrol` (debug source set) — the ADB command surface required by Principle VIII: a `DevCommand` interface with `@IntoSet` registration, a receiver guarded by a signature-level permission, a `healthhub://` deep-link router covering every screen, and a `StateDumper` emitting JSON to the `HealthHubState` logcat tag with a correlation id per command
- [ ] T022b `scripts/adb.sh` — thin wrapper over the command surface (`./scripts/adb.sh sync`, `… login <email> <pass>`, `… dump`, `… open activity/<id>`) plus a smoke script that drives a full sign-up → sync → feed → detail run and asserts on dumped state

**Checkpoint**: Foundation ready — user stories can proceed

---

## Phase 3: User Story 1 - Get my workouts off the phone (Priority: P1) 🎯 MVP

**Goal**: Health Connect data lands in D1 and R2, incrementally, resumably, without duplicates

**Independent Test**: Sign up on the SM-G780F, grant permissions, sync, and confirm imported session count and date range match Health Connect

### Tests for User Story 1

- [ ] T023 [P] [US1] Kotlin codec round-trip test in `android/core/telemetry/src/test/` — encode then decode, byte-identical against the T016 golden fixture, including sentinel handling
- [ ] T024 [P] [US1] Kotlin metric tests — splits, zones, moving time, elevation gain against hand-computed fixtures
- [ ] T025 [P] [US1] Worker contract tests for `POST /api/activities` — creation, idempotent re-post returning 200 with no duplicate row (SC-006), ownership rejection
- [ ] T026 [P] [US1] Worker contract tests for `PUT /api/activities/:id/telemetry` and the cursor/report routes

### Implementation for User Story 1

- [ ] T027 [P] [US1] `core:healthconnect` — client wrapper, the workout permission set, and the **record-type registry** keyed by Health Connect type so remaining types are additive (Principle VI)
- [ ] T028 [P] [US1] `core:telemetry` — the `.hht` encoder writing channel-by-channel to a file, never a single in-memory buffer (SC-003), plus LTTB downsampling for the preview
- [ ] T029 [US1] `core:telemetry` — on-device metric computation: splits, HR/power zones, moving time, per-channel statistics, elevation gain/loss, Douglas–Peucker simplification and polyline encoding
- [ ] T030 [US1] `core:sync` — delta engine over Health Connect change tokens, Room staging, upload pipeline, cursor advanced only after confirmed upload; deletions propagated (FR-007)
- [ ] T031 [US1] `core:sync` — WorkManager periodic worker plus data-change trigger, unmetered-network constraint honouring the user preference (FR-008)
- [ ] T032 [P] [US1] Worker `worker/src/routes/activities.ts` — `POST /api/activities` with `ON CONFLICT` upsert on `(user_id, source_uid)`, splits and zones stored verbatim
- [ ] T033 [P] [US1] Worker `worker/src/routes/telemetry.ts` — `PUT` streaming into R2, key and byte count recorded
- [ ] T034 [P] [US1] Worker `worker/src/routes/sync.ts` — cursors and reports per contracts/api.md
- [ ] T035 [US1] `feature:sync` — sync status screen, manual sync trigger, sync report display including unhandled record types, network preference

**Checkpoint**: Data flows phone → edge, verifiably and idempotently

---

## Phase 4: User Story 2 - Browse my activity feed (Priority: P1)

**Goal**: The same feed, on phone and web, from one indexed D1 query

**Independent Test**: Open the feed in both clients and confirm identical activities, order and figures

### Tests for User Story 2

- [ ] T036 [P] [US2] Worker contract tests for `GET /api/activities` — keyset pagination stability, sport and date filtering, cross-user isolation (SC-009)
- [ ] T037 [P] [US2] Web unit tests for the feed data layer — pagination accumulation and cache behaviour

### Implementation for User Story 2

- [ ] T038 [US2] Worker `GET /api/activities` — keyset pagination on `(start_time, id)`, filters, no R2 access
- [ ] T039 [P] [US2] Worker `PATCH /api/activities/:id` and `DELETE /api/activities/:id` (FR-016)
- [ ] T040 [P] [US2] `web/src/features/feed/` — feed route, activity cards in the Expressive card idiom, polyline route thumbnails rendered to canvas, infinite scroll, filters, empty state
- [ ] T041 [P] [US2] `feature:feed` on Android — lazy feed, Room-backed offline cache (FR-014), route thumbnails from the stored polyline, filters, empty state
- [ ] T042 [US2] Shared polyline decoder in both clients with a common test vector

**Checkpoint**: A recognisable Strava-class feed exists on both clients

---

## Phase 5: User Story 3 - Analyse a single workout (Priority: P1)

**Goal**: Map, aligned multi-series charts, splits, zones, cursor linkage, range selection

**Independent Test**: Open a synced ride; verify route, chart alignment, kilometre splits, chart↔map cursor linkage

### Tests for User Story 3

- [ ] T043 [P] [US3] TypeScript codec test — decodes the T016 golden fixture into typed arrays with correct values and sentinel skipping
- [ ] T044 [P] [US3] Web unit tests for range-selection statistics against a fixture, cross-checked against the Kotlin implementation's values (SC-008)
- [ ] T045 [P] [US3] Playwright end-to-end: sign in → feed → activity detail → map and charts render

### Implementation for User Story 3

- [ ] T046 [US3] Worker `GET /api/activities/:id` (detail with splits and zones) and `GET /api/activities/:id/telemetry` — ownership-checked R2 proxy with ETag and immutable caching
- [ ] T047 [P] [US3] `web/src/core/telemetry/` — `.hht` reader building zero-copy typed-array views, plus a Web Worker for range statistics
- [ ] T048 [P] [US3] `web/src/core/charts/` — µPlot wrapper themed entirely from generated tokens: stacked aligned series, time/distance axis switch, shared cursor, range selection
- [ ] T049 [P] [US3] `web/src/core/map/` — MapLibre GL wrapper with a token-derived style, route rendering with gaps preserved, position marker driven by the chart cursor
- [ ] T050 [US3] `web/src/features/activity/` — detail route: summary panel, map, charts, splits table, zone distribution; preview-then-full progressive loading; no-GPS explanatory state
- [ ] T051 [P] [US3] `feature:activity` on Android — the same detail surface with MapLibre Native and a Compose canvas chart drawn from `core:designsystem` primitives
- [ ] T052 [US3] Progressive loading on both clients: render preview telemetry immediately, upgrade to full resolution in the background (SC-005)

**Checkpoint**: The analytical payoff is delivered; MVP is functionally complete

---

## Phase 6: User Story 4 - Accounts across devices (Priority: P2)

**Goal**: Multi-user sign-up with strict per-user isolation and revocable devices

### Tests for User Story 4

- [ ] T053 [P] [US4] Worker auth contract tests — register, login, logout, `me`, identical failure shape and timing for unknown email vs wrong password
- [ ] T054 [P] [US4] Isolation test — account B cannot read account A's activity or telemetry by direct identifier (SC-009)
- [ ] T055 [P] [US4] Account deletion test — D1 rows gone and the R2 prefix emptied (FR-029)

### Implementation for User Story 4

- [ ] T056 [US4] Worker `worker/src/routes/auth.ts` — register, login, logout, get/patch/delete me, session cookie handling
- [ ] T057 [US4] Worker `worker/src/routes/devices.ts` — register device (token issued once), list, revoke
- [ ] T058 [P] [US4] Auth rate limiting via D1 counters, `429` with `Retry-After`
- [ ] T059 [P] [US4] `feature:auth` on Android — sign up, sign in, device registration exchange, sign-out revoking the device token
- [ ] T060 [P] [US4] `web/src/features/auth/` — sign up, sign in, account settings, device list with revoke, account deletion with confirmation

**Checkpoint**: The platform is genuinely multi-user

---

## Phase 7: User Story 5 - Trust the interface (Priority: P2)

**Goal**: One Expressive design language across both clients, charts included

### Tests for User Story 5

- [ ] T061 [P] [US5] Token parity test — the generated Kotlin and CSS token sets are asserted equal, so the two clients cannot drift
- [ ] T062 [P] [US5] Contrast test over the token palette for text and essential graphics (SC-011)

### Implementation for User Story 5

- [ ] T063 [US5] Audit every web surface against the token layer; remove any residual library default styling from µPlot and MapLibre
- [ ] T064 [US5] Audit every Android surface; confirm Expressive motion scheme and shape scale are applied app-wide, not per-screen
- [ ] T065 [P] [US5] Dark appearance pass on both clients including charts and map overlays (SC-010)
- [ ] T066 [P] [US5] Accessibility pass: large font sizes, touch targets, content descriptions, focus order (FR-035)

---

## Phase 7b: Analytical Archive Tier (Priority: P3 — after MVP)

**Goal**: The athlete's full history queryable from DuckDB / ClickHouse / Polars at zero
egress cost, per R-013. Independent of the MVP and deliberately sequenced after it.

- [ ] T066a Cron Trigger in `worker/wrangler.jsonc` plus `worker/src/archive/compact.ts` — compacts closed months into Parquet, targeting 128–512 MB parts, Hive-partitioned by local year and month; assembly only, no aggregation (Principle I)
- [ ] T066b Parquet writer with zstd for the activities and long-format samples tables; schema documented alongside the migration
- [ ] T066c Multipart upload above 100 MB with 8 MB parts, both in the compaction job and in the Android telemetry uploader
- [ ] T066d Scoped R2 S3 credentials flow — the athlete generates read-only keys for their own prefix; documented with a working DuckDB query in the README
- [ ] T066e Idempotency and re-run safety: recompacting a month replaces its parts atomically and never double-counts

---

## Phase 8: Polish & Cross-Cutting

- [ ] T067 Modularity proof (SC-012): add a trivial `feature:about` module and confirm it wires in through navigation contributions with no edit to any existing feature module
- [ ] T067a ADB coverage audit (Principle VIII): enumerate every user action and screen, assert each has a command or deep link, and verify the release build contains none of the surface
- [ ] T068 [P] Device verification run on the SM-G780F per quickstart.md — install with `--user 0`, first sync, feed, detail; record results
- [ ] T069 Performance verification: 100k-sample activity interactive within 3 s (SC-005); 1M-sample import without an OOM kill (SC-003)
- [ ] T070 [P] Repository documentation: README architecture section, CONTRIBUTING, issue templates
- [ ] T070a Client-side password pre-hash in the browser and on Android, so total KDF work is not capped by the Worker's 100,000-iteration ceiling (R-006 amendment); the Worker keeps salting and hashing what it receives
- [ ] T071 Security pass: cookie flags, rate limits, ownership guard coverage across every route, no secret in the repository
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

## Notes

- Commit after each task or coherent group; Conventional Commits
- Every task that adds a D1 table or an R2 prefix must state the classification (Principle II)
- Every UI task inherits Principle III — no library default palettes
- No `feature:*` → `feature:*` dependency may be introduced (Principle VII)
