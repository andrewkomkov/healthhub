# Quickstart: from clone to running on a phone

## Prerequisites

| Tool | Version used | Notes |
|------|--------------|-------|
| JDK | 21 | Android Gradle toolchain |
| Android SDK | platform 36, build-tools 37 | `ANDROID_HOME` must be set |
| Node | 26+ | Worker and web tooling |
| Wrangler | 4.x | `npx wrangler login` once |
| A Cloudflare account | free tier | D1 + R2 + Workers are all free-tier sufficient |

## 1. Cloudflare resources

```bash
npx wrangler d1 create healthhub
npx wrangler r2 bucket create healthhub-data
```

Copy the returned `database_id` into `worker/wrangler.jsonc`, then apply the schema:

```bash
npx wrangler d1 migrations apply healthhub --remote
npx wrangler d1 migrations apply healthhub --local   # for local development
```

## 2. Run locally

```bash
npm install
npm run dev        # vite on :5173, wrangler dev on :8787 with local D1 and R2
```

`wrangler dev` serves the built SPA from `web/dist` through the `assets` binding; during
development Vite proxies `/api/*` to the Worker so the two behave as one origin, exactly as
they do in production.

## 3. Deploy

```bash
npm run build      # builds web/dist, type-checks the Worker
npx wrangler deploy
```

The Worker and the web app go out together as one deployment, reachable at
`https://healthhub.<your-subdomain>.workers.dev`. No custom domain is needed.

Set the one required secret before the first deploy:

```bash
npx wrangler secret put SESSION_PEPPER   # random 32+ byte string
```

### Optional: Auth0 sign-in

HealthHub works without it — local password accounts are always available, so a fork can be
deployed without registering anywhere. To enable Auth0 as well, create a **Regular Web
Application** in your tenant (not a SPA — this flow uses a client secret) and set:

| Auth0 setting | Value |
|---|---|
| Allowed Callback URLs | `https://<worker>.workers.dev/api/auth/auth0/callback`, `http://localhost:8787/api/auth/auth0/callback` |
| Allowed Logout URLs | `https://<worker>.workers.dev/` |
| Allowed Web Origins | `https://<worker>.workers.dev` |

Then upload the three secrets:

```bash
npx wrangler secret put AUTH0_DOMAIN         # tenant.us.auth0.com
npx wrangler secret put AUTH0_CLIENT_ID
npx wrangler secret put AUTH0_CLIENT_SECRET
```

The sign-in button appears by itself once `GET /api/auth/providers` reports `auth0: true`.
If the callback URL is not registered, Auth0 answers the redirect with **“Callback URL
mismatch”** rather than a sign-in page.

## 4. Build and install the Android app

The test device is the Samsung SM-G780F (Android 13 / API 33), which has Health Connect
installed and Samsung Health writing into it.

```bash
cd android
./gradlew :app:assembleDebug

# NOTE: this device has a Secure Folder profile (user 150). Installing without
# --user 0 puts the app in the wrong profile and it will not see Health Connect data.
adb -s RZ8R21EG0DJ install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

Point the app at your deployment by setting `HEALTHHUB_BASE_URL` in
`android/local.properties` (defaults to the production `workers.dev` URL):

```properties
HEALTHHUB_BASE_URL=https://healthhub.<your-subdomain>.workers.dev
```

## 5. First run

1. Launch the app and register an account (or sign in).
2. Grant the Health Connect permissions when prompted — the app requests only the
   workout-related set.
3. Tap **Sync now**, or wait for the scheduled background sync.
4. Watch the sync report: it states sessions and samples transferred, plus anything it could
   not handle.
5. Open the feed, then any activity, to see the map and charts.
6. Sign in at your `workers.dev` URL — the same activities are there.

## Verifying on device

```bash
adb -s RZ8R21EG0DJ logcat -c
adb -s RZ8R21EG0DJ shell am start -n dev.healthhub/.MainActivity
adb -s RZ8R21EG0DJ logcat -s HealthHubSync:V HealthHubApi:V
```

Health Connect state can be inspected directly:

```bash
adb -s RZ8R21EG0DJ shell dumpsys package com.google.android.apps.healthdata | grep versionName
```

## Tests

```bash
cd android && ./gradlew test           # codec, metric maths, sync engine
npm test                               # Worker + web, against local D1/R2 via Miniflare
npm run test:e2e                       # Playwright: sign in → feed → activity detail
```

All of the above run in GitHub Actions on every push; `main` additionally deploys.
