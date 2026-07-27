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

| Step | Agent | Work |
|---|---|---|
| 1 | web | `.hht` reader: `ArrayBuffer` → typed-array views, no parse pass. Validate against the golden fixture the Kotlin codec is tested with |
| 2 | web | µPlot wrapper themed entirely from generated tokens: stacked aligned series, time↔distance axis switch, shared cursor, range selection |
| 3 | web | MapLibre GL with a token-derived style; route drawn with gaps preserved, marker driven by the chart cursor |
| 4 | web | Detail route: summary, map, charts, splits table, zone distribution. Preview telemetry paints immediately, full resolution swaps in |
| 5 | android | Same surface with MapLibre Native and a Compose canvas chart from `core:designsystem` primitives |
| 6 | device-qa | Open a real ride on the Pixel and in the browser; confirm identical figures and smooth panning |

**Done when**: a 100k-sample activity is interactive within 3 s, chart cursor moves the map
marker, a range selection reports statistics, and an indoor workout shows charts with an
explanatory state instead of an empty map.

**Watch for**: the codec is implemented twice on purpose. If the TypeScript reader disagrees
with the Kotlin writer, the shared fixture must fail — fix the fixture first if it does not.

---

## Session 2 — Archive and source priority, with screens

**Why**: the model, the API and the sync logic all landed already; there is no UI for any of
it. Right now the athlete cannot see the archive or reorder their sources.

**Agents**: `healthhub-web` + `healthhub-android` in parallel → `healthhub-device-qa`

| Step | Agent | Work |
|---|---|---|
| 1 | web | Archive view (`GET /api/activities?view=archive`), restore action, "also recorded by N apps" on cards |
| 2 | web | Source settings: drag-to-reorder priority, enable/disable, activity counts, via `GET/PUT /api/sources` |
| 3 | android | The same two screens as a new `feature:sources` module — this is the modularity proof (SC-012): it must attach without editing any existing feature |
| 4 | android | Per-activity archive/restore from the detail screen |
| 5 | device-qa | Reorder sources, re-sync, confirm the representative changed and nothing was deleted |

**Done when**: changing priority changes which recording represents a workout on the next
sync; a manually restored workout survives every later sync (`visibility_locked`); nothing is
ever deleted.

---

## Session 3 — GPS routes, per activity

**Why**: no activity currently has a track, and the reason is subtle — see R-015. Bulk route
access is signature-level and unavailable to third-party apps.

**Agents**: `healthhub-android` → `healthhub-device-qa`

| Step | Agent | Work |
|---|---|---|
| 1 | android | "Import route" on activity detail → `REQUEST_EXERCISE_ROUTE` with the session id |
| 2 | android | Ingest the returned track, recompute polyline, bounds and distance, re-upload |
| 3 | android | Surface honestly when a source wrote no route at all — that is data absence, not a permission problem |
| 4 | device-qa | Import a route on a real ride; confirm the polyline appears on both clients |

**Done when**: a ride with a recorded track shows its route, and one without says so clearly
rather than looking broken.

**Do not**: re-add `READ_EXERCISE_ROUTE` to the manifest. It cannot be granted to us.

---

## Session 4 — Sleep, recovery and the rest of Health Connect

**Why**: the PRD's other four categories. The ingestion layer was built as a registry
precisely so this is additive.

**Agents**: `healthhub-android` (lead) → `healthhub-edge` → `healthhub-web`

| Step | Agent | Work |
|---|---|---|
| 1 | android | Extend the record-type registry: sleep stages, HRV, resting HR, SpO2, weight, body fat, blood pressure |
| 2 | android | Narrow permission requests — only what the enabled features need (Principle IV) |
| 3 | edge | Schema and routes for daily-grain health data; classify each new class as D1 or R2 explicitly |
| 4 | android+web | Health & recovery surfaces: sleep stages, HRV trend, readiness |
| 5 | device-qa | Verify against Health Connect's own numbers, category by category |

**Done when**: every record type the device holds is either ingested or named in the sync
report. Silence is the failure mode Principle VI exists to prevent.

---

## Session 5 — The analytical archive

**Why**: whole-history questions ("distance by sport across 2026") over years of data.
Designed in R-013 and R-014, not yet built.

**Agents**: `healthhub-edge` → `healthhub-web`

| Step | Agent | Work |
|---|---|---|
| 1 | edge | Cron-triggered compaction of closed months into Hive-partitioned Parquet + zstd, 128–512 MB parts. Assembly only — no aggregation in the Worker |
| 2 | edge | Multipart upload above 100 MB; re-running a month replaces its parts atomically |
| 3 | edge | Scoped read-only R2 credentials the athlete generates for their own prefix |
| 4 | web | DuckDB-Wasm **in the browser**, loaded lazily, never on the feed path |
| 5 | — | README: a working DuckDB query against the athlete's own archive |

**Done when**: a year of history answers an aggregate query in the browser at zero egress
cost. DuckDB does **not** go in the Worker — see R-014.

---

## Session 6 — Hardening and the claims we have made

**Agents**: `healthhub-edge` + `healthhub-android`, `healthhub-device-qa` throughout

- Client-side password pre-hash, so total KDF work is not capped by the Worker's 100,000
  iterations (R-006 amendment).
- ADB coverage audit: every action and screen reachable; release build contains none of it.
- Performance verification against the numbers in the spec: 1M-sample import without an OOM
  kill, 100k-sample activity interactive in 3 s, feed first screen in 2 s.
- Accessibility: large font sizes, contrast on charts, touch targets, content descriptions.
- `CLOUDFLARE_API_TOKEN` in GitHub secrets so `deploy` goes green — it is the one red
  workflow, and it is red only for that.
- Run the quickstart from a clean clone and fix whatever does not work.

---

## Known open items

| Item | Where |
|---|---|
| `deploy` workflow red — missing `CLOUDFLARE_API_TOKEN` | GitHub → Secrets → Actions |
| Activity detail is a placeholder on both clients | Session 1 |
| Archive and source screens exist only as API | Session 2 |
| No activity has a GPS track yet | Session 3 |
| Password KDF capped by the platform | Session 6 |
| `feature:settings` module is empty | Session 2 or 6 |
