# OutageWatch

Free PG&E power-outage alerts. A person checks any ZIP or address (or their
saved places) and gets notified when an outage reaches them. No account.

**If you are here to build the iOS app, read [`docs/IOS_HANDOFF.md`](docs/IOS_HANDOFF.md) first** — it is the
authoritative, current-state guide. This file is the whole-project map.

## What's live

- **Backend API** (public): `https://outagewatch-api-7bi2fdpqrq-uw.a.run.app`
- **Web app**: served by that same service at `/` (landing), `/statewide`, `/widget`, `/privacy`, `/terms`
- **Android app**: shipped as a signed APK via GitHub Releases (latest `v0.2.5`). In-app updater pulls new releases.
- **iOS app**: wired, running, and verified screen by screen in the simulator
  (all four platform singletons, Firebase via SPM). iPhone-only for v1. Shipping
  is gated on Apple Developer enrollment: see [`docs/APP_STORE.md`](docs/APP_STORE.md)
  for the exact remaining steps, and `docs/IOS_HANDOFF.md` for what is wired.

## Repo layout

```
backend/            FastAPI service (public API + poller) and the web app it serves
  src/outagewatch/  API (api/main.py), explain (Claude), push (FCM), store (Firestore/GCS), config
  src/watcher/      poll pipeline: service.py, matcher.py, differ, dispatch, feeds/pge.py
  src/outagewatch/api/static/   the vanilla-JS web app (index.html, css/app.css, js/app.js, statewide, widget)
  tests/            pytest; scripts/deploy.sh deploys to Cloud Run
mobile/             Kotlin Compose Multiplatform (Android + iOS from one shared UI)
  shared/           commonMain = all UI + logic; androidMain / iosMain = platform actuals
  androidApp/       Android host (MainActivity wires platform bits); ships the APK
  iosApp/           Xcode project (SwiftUI shell hosting the Compose UI)
docs/               design specs and the iOS handoff
```

## Architecture in one paragraph

One FastAPI app runs as two Cloud Run services from `backend/scripts/deploy.sh`:
the **public API** (`outagewatch-api`, allow-unauthenticated) and a private
**poller** (`outagewatch-poller`, IAM-locked to the scheduler SA). Cloud Scheduler
hits the poller `/internal/poll` every 5 min; it fetches PG&E's ArcGIS feed,
diffs against the last snapshot (GCS), and pushes FCM notifications to matching
subscriptions (Firestore). The apps and web are thin clients of the public API.
Push matching is **address-only by default**: a subscription only fires when an
outage's polygon actually covers its point unless the user opts into area alerts
(see the notification model in the handoff doc / `watcher/matcher.py`).

## Conventions (please follow)

- **House style:** no emoji, no em-dashes or en-dashes, no middle-dot separators
  in any repo or user-visible text. Plain, understated voice. Commas, not dashes.
- **Design:** derive the look from the subject (power grid / utility), avoid
  generic "AI SaaS" defaults. Mobile uses the in-repo **Compass** design system
  (`shared/.../ui/theme`); the web uses the **V2** schematic-led design. Keep any
  on-screen "schematic" honest — PG&E's feed gives outages, not circuit topology,
  so never invent feeder/substation/node ids.
- **Git:** branch, PR, let CI pass (checks `backend` + `android` are required on
  `main`; force-push and deletion are blocked), then squash-merge. Don't commit
  to `main` directly.
- **Secrets:** none live in the repo. The master copy is `~/Desktop/outagewatch.env`
  (outside the repo); `sh mobile/scripts/sync-secrets.sh` fans it out to the
  gitignored files each tool reads: `mobile/local.properties` (keystore +
  Firebase keys) and `mobile/iosApp/Configuration/Local.xcconfig` (TEAM_ID).
  `GoogleService-Info.plist` is gitignored too. The Anthropic key
  is in GCP Secret Manager, injected as an env var at deploy (see the secret flow
  in `backend/scripts/deploy.sh` + `explain.py`). The repo is **public** and has
  been audited clean of secrets.

## Common commands

Backend (from `backend/`, a `uv`/venv Python 3.12 project):
```bash
.venv/Scripts/python.exe -m pytest -q          # tests (Windows path; use .venv/bin on macOS)
bash scripts/deploy.sh                          # deploy both Cloud Run services (needs gcloud auth)
```
GCP runs in project `outagewatch`. Prefer the named gcloud config so you never
clobber a global default: `gcloud config configurations activate outagewatch`
(or prefix commands with `CLOUDSDK_ACTIVE_CONFIG_NAME=outagewatch`).

Mobile (from `mobile/`; set `JAVA_HOME` to an Android Studio JBR / a JDK 17+):
```bash
sh gradlew :androidApp:assembleDebug            # compile the shared + Android app
sh gradlew :shared:testAndroidHostTest          # shared unit tests (Coverage, AppUpdate, ...)
sh gradlew :shared:compileKotlinIosSimulatorArm64  # sanity-compile the iOS target
sh gradlew :androidApp:assembleRelease          # signed APK (needs keystore in local.properties)
```
Invoke Gradle as `sh gradlew`: the wrapper is committed without its executable
bit, so `./gradlew` fails. CI and the Xcode build phase do the same.
iOS: open `mobile/iosApp/iosApp.xcodeproj` in Xcode after a shared build. See the
handoff doc for the full setup (Firebase, signing, wiring).

## Health / cost

Audited 2026-07-23: scales to zero, ~$0.50-1/month idle, no open security doors,
a $25 GCP budget alert exists. Firestore is server-access-only (client reads are
default-denied). Details live in the maintainer's notes; the short version is
"safe and cheap to leave running."
