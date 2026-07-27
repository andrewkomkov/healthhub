# Implementation Plan: Workout Sync & Activity Feed

**Branch**: `001-workout-sync-feed` | **Date**: 2026-07-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-workout-sync-feed/spec.md`

## Summary

Ship the first slice of HealthHub: an Android application that reads workout sessions from
Google Health Connect, computes every derived metric on the phone, and pushes small summary
rows plus large opaque telemetry blobs to a single Cloudflare Worker; and a web application,
served as static assets by that same Worker, that renders the resulting activity feed, route
maps and multi-series charts.

The architecture has no backend in the conventional sense. One Worker on `*.workers.dev`
performs authentication, ownership checks and byte routing. Small relational data lives in
**D1**; large and historical data lives in **R2** as a compact columnar binary that both
clients read into typed arrays without parsing. All analysis — splits, zones, smoothing,
range statistics — runs on the athlete's device or in their browser.

Both clients render Material 3 Expressive from one shared token source, generated into a
Kotlin theme and a CSS custom-property sheet so charts and maps use the same palette as the
rest of the UI.

## Technical Context

**Language/Version**: Kotlin 2.2 (Android, JDK 21 toolchain) · TypeScript 5.9 (Worker + web,
Node 26 for tooling)

**Primary Dependencies**:

- Android — Jetpack Compose with `androidx.compose.material3:material3:1.5.0-alpha24`
  (Material 3 Expressive APIs; alpha is a deliberate, constitution-sanctioned choice),
  `androidx.health.connect:connect-client:1.1.0`, WorkManager, Room (local staging), Hilt,
  MapLibre Native Android, Kotlinx Serialization, OkHttp.
- Worker — Hono on the Workers runtime, D1 and R2 bindings, Workers static assets, Wrangler
  4.x. No ORM, no database engine.
- Web — Vite + React 19 + TypeScript, MapLibre GL JS, µPlot for canvas charting, a
  hand-built Material 3 Expressive component layer over generated design tokens.

**Storage**: Cloudflare **D1** for accounts, devices, activity summaries, derived metrics,
sync cursors and reports. Cloudflare **R2** for raw and preview telemetry blobs. No
PostgreSQL, no ClickHouse, no other database engine — see Constitution Principle II.

**Testing**: Android — JUnit 5 + Kotlin Test for codec, sync and metric maths; Robolectric
for ViewModels; a scripted on-device verification run on the Samsung SM-G780F. Worker/web —
Vitest with `@cloudflare/vitest-pool-workers` against local D1/R2 (Miniflare); Playwright for
one end-to-end feed-and-detail flow.

**Target Platform**: Android 9+ (`minSdk 28`, `targetSdk 36`); verification device is Samsung
SM-G780F on Android 13 / API 33 with Health Connect 2026.05.14.01 installed and Samsung
Health feeding it. Web — evergreen browsers. Edge — Cloudflare Workers.

**Project Type**: Mobile application + edge-hosted web application, in one monorepo.

**Performance Goals**: Activity detail interactive within 3 s at 100k samples (SC-005); feed
first screen within 2 s (SC-004); import of a 1M-sample activity completes on the test device
without an OOM kill (SC-003); chart pan/zoom stays at 60 fps.

**Constraints**: Worker CPU budget per request is small and must not be spent on analytics —
the Worker streams bytes and runs indexed D1 queries only. R2 objects are written once and
read whole. The phone must sync incrementally, offline-tolerantly, and without duplicating
work. Free-tier `*.workers.dev` hostname; no custom domain.

**Scale/Scope**: Multi-user with sign-up. Per athlete, expect on the order of 10³ activities
and up to ~10⁶ samples for a single long session. This slice covers roughly 8 Android screens
and 5 web routes.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Status |
|-----------|------|--------|
| I. Local-First Aggregation | Splits, zones, moving time, averages and smoothing are computed on Android at ingest; range statistics are computed in the browser from downloaded typed arrays. The Worker contains no analytics code path. | **PASS** |
| II. Serverless-Only Storage | Cloudflare Worker + D1 + R2 only. D1 holds accounts, devices, activity summaries, derived metrics, cursors, reports, encoded route polylines. R2 holds telemetry blobs and previews. Classification recorded in [data-model.md](./data-model.md). No database engine introduced. | **PASS** |
| III. Material 3 Expressive Everywhere | One token source in `packages/design-tokens` generates a Kotlin theme object and a CSS custom-property sheet. Android uses `MaterialExpressiveTheme`; web uses a hand-built Expressive component layer. µPlot and MapLibre are configured from the generated tokens, not their own defaults. | **PASS** |
| IV. Health Privacy by Default | Narrow Health Connect permission set for workouts only; graceful degradation on denial; no third-party analytics/crash SDKs; per-user ownership enforced on every route; account deletion cascades D1 rows and deletes the R2 prefix. Privacy-zone columns are reserved in the schema for the later phase. | **PASS** |
| V. Open Source and CI-Enforced | Public GitHub repository, Apache-2.0. GitHub Actions builds and tests Android, type-checks and tests Worker and web, validates `wrangler deploy --dry-run`, and deploys from `main`. | **PASS** |
| VI. Complete Data Fidelity | This slice ingests workout-domain record types; the ingestion layer is a registry keyed by record type so remaining Health Connect types are added as registry entries. Unknown types are recorded in the sync report rather than dropped. Samples are transferred at full recorded resolution — the downsampled object is an *additional* preview, never a replacement. | **PASS** — with scope note below |
| VII. Modular by Construction | Android is a Gradle multi-module build: `core:*` modules carry no feature logic; `feature:*` modules never depend on each other; navigation is assembled from `@IntoSet` contributions so a social module can be added without editing existing ones. Web mirrors this with feature-scoped directories over `src/core`. | **PASS** |
| VIII. Fully ADB-Controllable | `core:devcontrol` (debug source set only) exposes a signature-permission-guarded receiver implementing every user action as a command, a deep link per screen, and a JSON state dump on a stable logcat tag. Feature modules contribute their commands through the same `@IntoSet` mechanism as navigation, so the surface stays complete as modules are added. Absent from release builds. | **PASS** |

**Scope note on Principle VI**: the constitution requires the client to be *able* to ingest
all 80+ record types. This slice implements the workout-domain subset and the registry
mechanism that makes the remainder additive. Sleep, cardiovascular, body-composition and
symptom record types are registry entries deferred to the next feature, not a design gap.
No re-architecture is required to add them, and no data is silently dropped in the meantime.

## Project Structure

### Documentation (this feature)

```text
specs/001-workout-sync-feed/
├── plan.md              # This file
├── research.md          # Phase 0 output — decisions and rejected alternatives
├── data-model.md        # Phase 1 output — D1 schema, R2 layout, telemetry codec
├── quickstart.md        # Phase 1 output — clone to running on device
├── contracts/
│   └── api.md           # Phase 1 output — Worker HTTP contract
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
android/
├── settings.gradle.kts
├── gradle/libs.versions.toml           # single version catalogue
├── app/                                # assembly only: Application, DI graph, nav host
├── core/
│   ├── model/                          # domain types, no Android deps
│   ├── designsystem/                   # M3 Expressive theme from generated tokens, charts
│   ├── ui/                             # shared composables (cards, states, scaffolds)
│   ├── navigation/                     # NavContribution contract + registry
│   ├── database/                       # Room: staging buffer + offline feed cache
│   ├── healthconnect/                  # HC client, permission set, record-type registry
│   ├── telemetry/                      # columnar codec + on-device metric computation
│   ├── sync/                           # WorkManager workers, delta engine, upload pipeline
│   ├── network/                        # Worker API client, device token store
│   └── devcontrol/                     # debug-only ADB command surface + state dump
└── feature/
    ├── auth/                           # sign-up, sign-in, device registration
    ├── feed/                           # activity feed
    ├── activity/                       # detail: map, charts, splits, zones
    ├── sync/                           # sync status, report, network preferences
    └── settings/                       # units, appearance, account

worker/
├── wrangler.jsonc                      # D1 + R2 bindings, assets → ../web/dist
├── migrations/                         # D1 SQL migrations
└── src/
    ├── index.ts                        # Hono app, route mounting
    ├── auth/                           # password hashing, sessions, device tokens
    ├── routes/                         # activities, telemetry, devices, account
    └── lib/                            # ownership guard, validation, errors

web/
├── vite.config.ts
└── src/
    ├── core/
    │   ├── m3e/                        # Expressive component layer + generated tokens
    │   ├── charts/                     # µPlot wrappers themed from tokens
    │   ├── map/                        # MapLibre wrapper themed from tokens
    │   ├── telemetry/                  # codec reader + Web Worker analysis
    │   └── api/                        # typed client for the Worker contract
    └── features/
        ├── auth/
        ├── feed/
        └── activity/

packages/design-tokens/
├── tokens.json                         # single source of truth
└── build.mjs                           # emits Kotlin object + CSS custom properties

.github/workflows/
├── android.yml
├── web.yml
└── deploy.yml
```

**Structure Decision**: A single monorepo with four top-level source trees — `android/`,
`worker/`, `web/`, `packages/design-tokens/` — because the design-token package must be
consumed by both clients at build time, and because the Worker serves the web build output
as its own static assets (`assets.directory` points at `../web/dist`), making them one
deployable unit. The Android tree is a Gradle multi-module build laid out per Constitution
Principle VII: `core:*` modules are feature-agnostic and `feature:*` modules are mutually
independent, so the planned social modules attach without touching existing code.

## Key Design Decisions

Full reasoning and rejected alternatives are in [research.md](./research.md). The decisions
that shape everything else:

1. **One Worker, not a Worker plus a Pages project.** Workers static assets serve the SPA
   from the same script that answers `/api/*`, so there is one deployment, one origin, no
   CORS, and one `*.workers.dev` hostname.
2. **A columnar binary telemetry format (`.hht`)** — a JSON header followed by contiguous
   typed-array payloads — rather than JSON, Protobuf or Parquet. The browser maps the buffer
   straight into `Float32Array`/`Int32Array` with zero parsing, which is what makes 100k+
   samples interactive within 3 seconds.
3. **Two R2 objects per activity**: a full-resolution blob and a ~2,000-point preview. The
   detail screen renders the preview immediately, then upgrades to full resolution in the
   background. Fidelity is preserved because the preview is additive.
4. **The Worker proxies R2 reads** behind an ownership check instead of issuing presigned
   URLs — presigning would require S3-API credentials in the Worker and would hand out a
   token that outlives the authorisation check.
5. **Route polylines live in D1, tracks live in R2.** A feed card needs a few hundred bytes
   of encoded polyline, not a GPS series; this keeps the feed query to a single indexed D1
   read with no R2 access at all.
6. **Opaque tokens in D1, not JWTs.** Device authorisations must be individually revocable
   (FR-028); a stateless token cannot be revoked without inventing a revocation list, which
   is a D1 lookup anyway.
7. **Metrics are computed once, on the phone.** The web client never recomputes splits or
   zones — it reads them from D1 — and only computes statistics for interactive range
   selections, from telemetry it has already downloaded.

## Complexity Tracking

> No Constitution Check violations. This section is retained empty per the template contract.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
