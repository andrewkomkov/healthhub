# HealthHub

A Strava-class analytics platform for the workout, biometric and sleep data that Google
Health Connect keeps locked on your Android phone — with no backend to run.

HealthHub is an Android app plus a web app. The Android app reads your data out of Health
Connect, computes every derived metric on the phone, and pushes it to a single Cloudflare
Worker. Small rows go to **D1**; large telemetry goes to **R2**. The web app is served as
static assets by that same Worker and does its analysis in your browser.

There is no server to operate, no database to run, and nothing to pay for at personal scale.

## Why

Google shut down Google Fit Web and never replaced it. Health Connect holds 80+ types of
health data on-device with no way to look at any of it properly — no route maps, no
multi-series charts, no split analysis, no history you own. HealthHub is that missing
interface, and it is yours: clone it, deploy it to your own Cloudflare account, and the data
never touches anyone else's infrastructure.

## Architecture

```
Android app  ──HTTPS──►  Cloudflare Worker  ──►  D1   (accounts, activity summaries,
(Health Connect,          (auth, ownership,            splits, zones, route polylines)
 metric computation,       byte routing —         ──►  R2   (telemetry blobs, previews,
 offline buffering)        no analytics)                     exports)
                                 │
                                 └── serves the web SPA as static assets
                                     (feed, maps, charts — analysis in-browser)
```

Four rules hold this together:

1. **Analysis runs on your device**, never on a server. Splits, heart-rate zones, moving
   time and smoothing are computed on the phone at ingest; interactive range statistics are
   computed in your browser.
2. **No database engine.** D1 for small queryable rows, R2 for large objects read whole.
   PostgreSQL and ClickHouse are explicitly out.
3. **One design language.** Android and web both render Material 3 Expressive from a single
   shared token source — including the charts and map overlays.
4. **Nothing that needs an account.** The map draws OpenStreetMap data through OpenFreeMap,
   which asks for no registration and no key; point `VITE_MAP_TILES_URL` (or `hh_map_tiles_url`)
   at your own tiles if you would rather, or at `none` to talk to nobody at all.

These are not preferences; they are enforced by the
[project constitution](.specify/memory/constitution.md), which every change is checked
against.

## Status

Early, but running. The first slice — workout sync, the activity feed, route maps over an
openly licensed basemap, scrubbable charts, splits and zones — works on a real phone against a
real deployment: a year of Health Connect history, GPS tracks filled in a workout at a time, and
every figure computed on the device. Sleep, HRV and recovery are built on Android and have no
web half yet. Power curve and training load, privacy zones and GPX/FIT export are specified and
queued behind that.

What is *not* yet true is written down rather than glossed over — see the known open items at
the end of [the roadmap](docs/ROADMAP.md).

## Getting started

See [the quickstart](specs/001-workout-sync-feed/quickstart.md) for the full path from
clone to running on a phone. In short:

```bash
npx wrangler d1 create healthhub
npx wrangler r2 bucket create healthhub-data
npm install && npm run build && npx wrangler deploy

cd android && ./gradlew :app:assembleDebug
# --user 0, and never -g: a second profile hides the data, and pre-granting breaks the
# in-app permission request outright. See docs/AGENT-NOTES.md.
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

## Development

The project is built spec-first with [Spec Kit](https://github.com/github/spec-kit). Before
any code was written there was a constitution, a specification, and a plan — they live in
the repository and stay current:

| Document | What it is |
|----------|------------|
| [Constitution](.specify/memory/constitution.md) | The non-negotiable principles |
| [Specification](specs/001-workout-sync-feed/spec.md) | What the product does, in user terms |
| [Plan](specs/001-workout-sync-feed/plan.md) | How it is built, and the Constitution Check |
| [Research](specs/001-workout-sync-feed/research.md) | Every technical decision, and what was rejected |
| [Data model](specs/001-workout-sync-feed/data-model.md) | D1 schema, R2 layout, telemetry codec |
| [API contract](specs/001-workout-sync-feed/contracts/api.md) | The Worker's HTTP surface |

The Android app is a Gradle multi-module build in which feature modules never depend on each
other, so planned capabilities — social features in particular — attach without touching
existing code.

## Contributing

Issues and pull requests are welcome. A change should be consistent with the constitution;
if it cannot be, the constitution is amendable — by a pull request that says why.

## License

[Apache-2.0](LICENSE)
