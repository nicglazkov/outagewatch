# OutageWatch

Power outage alerts for PG&E territory. Pick your ZIP code and get a push
notification when an outage hits it, when the restoration estimate moves,
and when power is back. Free, no account.

Not affiliated with PG&E. The data comes from PG&E's public outage map and
can lag behind reality. For emergencies call 911. Report downed lines to
PG&E at 1-800-743-5000.

## What's here

- `backend/` - Python 3.12 watcher engine and API on Cloud Run. A poller
  reads PG&E's public outage feed every 5 minutes, diffs it against the
  previous state, matches changes to subscriptions, and sends pushes via
  FCM. The `watcher` package is generic; the `outagewatch` package holds
  everything PG&E-specific.
- `mobile/` - Kotlin Compose Multiplatform app for Android and iOS. One
  shared UI codebase. Screens: status for your saved areas, add location,
  nearby outages, outage detail with a plain-language explanation, settings.
- The API also serves a small web status page at `/`: enter a ZIP, see
  current outages.

## Notifications

Only four things ever notify: an outage started at your location, the
restoration estimate moved by more than 30 minutes, power was restored, or
a PSPS warning covers your area. Quiet hours are respected for everything
except PSPS warnings. Nothing else, ever.

## The explanation card

Outage details include a short plain-language answer to "why is my power
out and when is it coming back". It is rendered by Claude Haiku from the
structured feed data only. The model is not allowed to invent restoration
times or causes. Responses are cached per outage update.

## Development

Backend:

```
cd backend
uv sync --extra server
uv run pytest
uv run python scripts/dev_server.py   # local API with fixture data on :8787
```

Mobile (Android):

```
cd mobile
./gradlew :androidApp:assembleDebug
./gradlew :shared:testAndroidHostTest
```

Firebase config for push goes in `mobile/local.properties` (see
`androidApp/build.gradle.kts`). Builds work without it; push is skipped.

iOS builds from `mobile/iosApp` in Xcode on a Mac.

## Data

PG&E publishes outage data through the ArcGIS services behind their public
outage map. OutageWatch polls it server side every 5 minutes with an honest
User-Agent, backs off on errors, and never polls from devices. Raw
snapshots are recorded so real outage days become regression fixtures.
ZIP centroids come from the US Census ZCTA gazetteer (public domain).

## License

MIT
