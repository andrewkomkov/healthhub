# HealthHub

Strava-class analytics over Google Health Connect. Android app + web app, no backend:
one Cloudflare Worker, D1 for small rows, R2 for large objects.

## Read these first

| Document | Why |
|---|---|
| [docs/AGENT-NOTES.md](docs/AGENT-NOTES.md) | **Platform traps that cost real time.** Read the section for whatever you are touching, before you touch it |
| [docs/ROADMAP.md](docs/ROADMAP.md) | What to build next, grouped into sessions, assigned to agents |
| [.specify/memory/constitution.md](.specify/memory/constitution.md) | The eight non-negotiable principles every change is checked against |
| [specs/001-workout-sync-feed/](specs/001-workout-sync-feed/) | Spec, plan, research decisions, data model, API contract |

Specialist agents live in `.claude/agents/`: `healthhub-edge`, `healthhub-android`,
`healthhub-web`, `healthhub-device-qa`.

## Layout

```
android/   Kotlin, Compose, Material 3 Expressive. All metrics computed here
worker/    The whole server side: Hono on Workers, D1 + R2. No analysis lives here
web/       React SPA, served by the Worker as static assets
packages/design-tokens/   One token source → Kotlin theme + CSS custom properties
```

## Commands

```bash
npm run tokens                     # generated files are NOT committed — run this first
npm run typecheck && npm test
npm run build && npx wrangler deploy --dry-run --config worker/wrangler.jsonc
cd android && ./gradlew :app:assembleDebug && ./gradlew test
```

## Shipping

Nothing is released from a laptop (Constitution Principle V). Both paths are one push:

```bash
git push origin main               # deploy.yml: worker + web, then d1 migrations --remote
git tag v0.2.0 && git push --tags  # release.yml: tests, signed APK, checksum, GitHub Release
```

The installed app finds that release by itself — `feature:updates` asks GitHub twice a day and
installs after the athlete confirms. `gh workflow run deploy` re-runs a deployment; the local
`wrangler deploy` is for emergencies, and it skips the migration step that goes with it.

## Environment

`.env.example` documents every credential and where it lives. Real values are in
`.env.local` (git-ignored). Runtime secrets live in Wrangler secrets and GitHub Actions
secrets, never in the repository.

## Two rules that explain most of the design

1. **Analysis happens on the athlete's device**, never on the edge. That is why there is no
   backend to run and no bill to pay.
2. **Nothing is ever deleted.** Duplicate recordings are archived and restorable; a manual
   decision outranks the automatic one permanently.
