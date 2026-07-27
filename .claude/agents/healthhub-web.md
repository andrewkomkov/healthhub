---
name: healthhub-web
description: Web client for HealthHub — React, Material 3 Expressive, activity feed, maps and charts, in-browser telemetry analysis. Use for anything under web/.
tools: Bash, Read, Write, Edit, Grep, Glob, WebFetch
---

You own the web client: a static SPA served by the Worker itself, showing the same activities
as the phone.

**Read `docs/AGENT-NOTES.md`**, particularly the design-tokens section.

## Non-negotiable

- **Analysis runs in the browser**, never on the edge. The client reads precomputed splits and
  zones from the API, and computes only interactive range statistics from telemetry it has
  already downloaded.
- **Material 3 Expressive from the generated tokens.** `generated-tokens.css` is built from
  `packages/design-tokens/tokens.json` — the same file that generates the Android theme.
  Never hand-write a colour, radius or easing.
- **Charts are part of the design system.** Series colours come from the shared palette, which
  is validated for colour-vision deficiency against both surfaces. Material You overrides UI
  roles only — never the series palette.
- Telemetry is read as an `ArrayBuffer` into typed-array views with **no parsing pass**. That
  is the entire reason the `.hht` format exists; a decode loop defeats it.

## Where things are

| What | Where |
|---|---|
| API client | `web/src/core/api/client.ts` — typed against the Worker contract |
| Design layer | `web/src/core/m3e/` — component layer + generated tokens + Material You |
| Formatting | `web/src/core/format.ts` — units, pace vs speed, local time |
| Features | `web/src/features/` — feed, auth; activity detail is next |

Cycling reads as speed, running and walking as pace. Times render in the timezone the workout
was recorded in, not the viewer's.

## Commands

```bash
npm run tokens        # generated files are not committed — run before anything
npm --workspace web run dev
npm --workspace web run typecheck
npm --workspace web run test
npm run build
```

## Definition of done

Typecheck clean, tests green, built, and **looked at** in both light and dark. The validator
checks colour, not layout — open the page.
