<!--
Sync Impact Report
==================
Version change: TEMPLATE (unversioned) → 1.0.0
Bump rationale: Initial ratification of the project constitution. All placeholder
tokens replaced with concrete governance for the HealthHub project.

Modified principles: none (initial adoption)
Added sections:
  - Core Principles I–VII
  - Technology Constraints
  - Development Workflow & Quality Gates
  - Governance
Removed sections: none

Templates requiring updates:
  ✅ .specify/templates/plan-template.md — Constitution Check gate is generic and
     reads this file at plan time; no edit required.
  ✅ .specify/templates/spec-template.md — scope/requirements sections compatible
     with Principle VI and VII constraints; no edit required.
  ✅ .specify/templates/tasks-template.md — task categories cover device-side,
     edge, and UI work; no edit required.
  ✅ .claude/skills/speckit-*/SKILL.md — reviewed, no agent-specific stale refs.
  ⚠ README.md — does not exist yet; must be created with Principle V references
     when the repository is published.

Deferred TODOs: none
-->

# HealthHub Constitution

HealthHub is an open-source, Strava-class analytics platform for fitness, biometric and
sleep data sourced from Google Health Connect on Android. It consists of an Android
application and a web application, backed only by Cloudflare edge primitives.

## Core Principles

### I. Local-First Aggregation (NON-NEGOTIABLE)

Heavy computation MUST run on the user's device — on Android in Kotlin, on the web in the
browser (WASM or JS workers). The edge tier MUST NOT perform time-series analytics,
windowed aggregation, or per-point transformation of telemetry.

Concretely: derived metrics (pace/speed series, heart-rate zones, power curve, TSS/IF/NP,
sleep-stage rollups, HRV, readiness score, splits, elevation profiles) MUST be computed by
the producing device at ingest time, or by the browser at view time from raw series it has
fetched. A Worker MAY read, write, authorize, and route bytes; it MUST NOT own analysis
logic.

Rationale: This is what makes a database-free architecture viable. It removes server cost,
keeps raw health data out of server-side compute, and lets the product scale to a million
telemetry points per session without any query engine.

### II. Serverless-Only Storage Topology (NON-NEGOTIABLE)

The system MUST run entirely on Cloudflare Workers, D1, R2, and Cloudflare Pages. Adding a
persistently-running server, container, VM, or self-hosted database is a violation.

PostgreSQL, ClickHouse, and every other managed or self-hosted database engine are
explicitly forbidden. The storage split is fixed:

- **D1** — small, relational, queryable data: user accounts, device registrations, session
  metadata and summaries, precomputed aggregates, privacy zones, notes, sync cursors.
  Individual rows MUST stay small; no blobs, no raw sample arrays.
- **R2** — large and historical data: raw and downsampled telemetry series, GPS tracks,
  generated exports (GPX/FIT/CSV), and any object that grows with session duration.

Any new data class MUST be classified into D1 or R2 before implementation, and the choice
MUST be recorded in the feature's plan.

Rationale: A hard topology rule prevents the slow drift back toward a conventional backend,
which is the single most likely way this architecture degrades.

### III. Material 3 Expressive Everywhere

Both clients MUST implement Material 3 Expressive as their design language, including on
the web, and including while the relevant libraries are in alpha. Alpha status is an
accepted, deliberate risk and MUST NOT be used as a reason to fall back to classic
Material 3 or to an unrelated design system.

This applies to every surface without exception: navigation, screens, dashboards, tables,
charts, maps overlays, empty states, and dialogs. Charts MUST draw from the same shared
design tokens (color roles, shape scale, motion/easing, typography) as the rest of the UI
rather than the defaults of a charting library.

Android and web MUST share a single source of truth for design tokens, so that the two
clients remain visibly the same product.

Rationale: The product's differentiator is a coherent, modern, expressive experience; a
chart library's default palette breaks that coherence instantly.

### IV. Health Privacy by Default

Health data is sensitive by definition and MUST be treated as such.

- Privacy Zones MUST mask GPS coordinates within a configured radius of user-defined points
  before any track leaves the device in shareable form.
- The app MUST request the narrowest set of Health Connect permissions needed for the
  features the user has actually enabled, and MUST degrade gracefully when a permission is
  denied.
- No third-party analytics, advertising, crash-reporting, or telemetry SDK that transmits
  user health data may be added to either client.
- Every stored object MUST be attributable to exactly one user, and deletion of a user
  MUST remove their D1 rows and R2 objects.
- Secrets (API tokens, signing keys, Cloudflare credentials) MUST NOT be committed; they
  live in Wrangler secrets and GitHub Actions secrets.

Rationale: This is the user's medical and location history. Losing it, leaking it, or
quietly monetizing it is an unrecoverable failure.

### V. Open Source and CI-Enforced Quality

The project MUST be developed in a public GitHub repository under an OSI-approved license.
All automation — build, test, lint, release, and deployment — MUST run in GitHub Actions.
No local-only or machine-specific release path is permitted.

CI MUST, at minimum: build the Android app, run unit tests, run static analysis on both
clients, type-check and test the Worker and web app, and validate that the Worker/Pages
deployment configuration is well-formed. A red pipeline blocks merge to `main`.

Rationale: For an open-source health project, reproducibility from a clean checkout is a
credibility requirement, not a convenience.

### VI. Complete Data Fidelity

The Android client MUST be able to ingest 100% of the record types exposed by the Health
Connect API — all 80+ — without silent loss, truncation, or lossy rounding of samples.

Unsupported or newly-added record types MUST fail loudly (logged, surfaced in a sync
report) rather than being silently dropped. Sync MUST be incremental via Health Connect
change tokens, resumable, and safe to retry: re-delivering the same records MUST NOT create
duplicates.

Rationale: The product's premise is that it captures everything Google exposes; partial
ingestion makes every downstream analysis untrustworthy.

### VII. Modular by Construction

The Android application MUST be built as a set of independently buildable Gradle modules,
not a monolith. A new capability — social feed, clubs, challenges, segments, coaching,
third-party integrations — MUST be addable as a new feature module without editing the
internals of existing feature modules.

The module contract is fixed:

- `core:*` modules (design system, Health Connect access, sync, storage, networking,
  model) hold no feature-specific logic and MUST NOT depend on any `feature:*` module.
- `feature:*` modules depend on `core:*` and never on each other. Cross-feature needs are
  expressed through interfaces owned by `core:`, resolved by dependency injection.
- Navigation, the home feed's content, and the app's entry points MUST be assembled from
  contributions registered by feature modules, so that adding a module wires up its screens
  without a central file becoming a bottleneck.
- Every feature module MUST be able to be removed from the build without breaking the
  compilation of the remaining modules.

The web client MUST mirror this separation with feature-scoped directories over shared
core packages, so the two clients stay conceptually aligned.

Rationale: Social features are explicitly planned but out of scope for the MVP. Deciding
the seams now costs little; retrofitting them into a shipped monolith costs a rewrite.

## Technology Constraints

- **Android**: Kotlin, Jetpack Compose, Material 3 Expressive, WorkManager for background
  sync, Health Connect SDK, local staging store for offline buffering. The Android target
  is a full application — feed, map, session detail, health dashboards — not a headless
  sync agent.
- **Web**: a static single-page application served by the Worker itself through Workers
  static assets — not a separate Pages project. Material 3 Expressive design tokens;
  open-source map rendering (MapLibre-class) with tiles that do not require a proprietary
  account.
- **Edge**: Cloudflare Workers only, with D1 and R2 bindings, managed via Wrangler and a
  versioned `wrangler.jsonc` in the repository. The deployment target is the free
  `*.workers.dev` hostname; no custom domain is required to run the product.
- **Product shape**: the experience is Strava-class — an activity feed, maps, and social-
  style session detail — not a bare analytics dashboard.
- **Device testing**: physical-device verification is performed on the connected Samsung
  SM-G780F over ADB. Emulator-only verification does not satisfy a device test gate.

## Development Workflow & Quality Gates

- Every feature MUST progress through the Spec Kit flow: constitution → specify → plan →
  tasks → implement. Implementation MUST NOT begin before a plan exists.
- Every plan MUST include a Constitution Check. A violation MUST be either removed or
  recorded with an explicit justification in the plan's complexity-tracking section.
- Every feature that touches data storage MUST state its D1-vs-R2 classification.
- Every feature that adds UI MUST state how it satisfies Principle III.
- Every feature that adds Android functionality MUST state which Gradle module it lands in
  and MUST NOT introduce a `feature:*` → `feature:*` dependency.
- Changes MUST reach `main` through a green GitHub Actions pipeline.
- Commits MUST follow Conventional Commits 1.0.0.

## Governance

This constitution supersedes ad-hoc practice. Where a tool default, a library convention,
or an expedient shortcut conflicts with a principle here, the principle wins.

**Amendment procedure**: amendments are proposed as a pull request that edits this file,
states the rationale, and updates the Sync Impact Report. Merging the pull request ratifies
the amendment. Dependent templates and command files MUST be re-validated in the same pull
request.

**Versioning policy**: this document is versioned with semantic versioning.
MAJOR — a principle is removed or redefined in a backward-incompatible way.
MINOR — a principle or section is added, or guidance is materially expanded.
PATCH — clarification, wording, or typo fixes that do not change obligations.

**Compliance review**: the Constitution Check in every plan is the enforcement point.
Principles I, II, and VI are additionally verifiable by inspection of the codebase — the
presence of a database engine, of server-side analytics, or of a dropped record type is a
detectable violation and MUST be treated as a defect.

**Version**: 1.0.0 | **Ratified**: 2026-07-27 | **Last Amended**: 2026-07-27
