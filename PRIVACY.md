# OutageWatch Privacy Policy

**Last updated: July 11, 2026**

OutageWatch ("the app," "we," "us") sends power-outage alerts for PG&E's
Northern and Central California territory. This policy explains what the app
collects, why, and who it is shared with. OutageWatch is an independent app and
is **not affiliated with, endorsed by, or operated by PG&E**.

We built OutageWatch to collect as little as possible. There are **no accounts,
no sign-up, and no name, email, or phone number required** to use it.

## What we collect

- **Areas you add.** When you add a ZIP code, type an address, or tap "Use my
  current location," we store that area (its coordinates and, for a ZIP, the ZIP
  code) so we can watch it for outages. Addresses you type are turned into
  coordinates ("geocoded"); your device does part of this, and the address or
  ZIP is also sent to our server to look up the location and check which utility
  serves it.
- **A notification token.** To deliver push alerts we use Firebase Cloud
  Messaging, which assigns your app install an anonymous device token. We store
  this token with your saved areas so we can notify the right device.
- **Basic request data.** Like any internet service, our servers briefly receive
  your device's IP address when the app makes a request. We use it only to
  operate and protect the service (for example, rate limiting) and do not build
  a profile from it.

We do **not** collect your name, email, phone number, contacts, photos, or a
continuous location history. GPS is read **only** in the moment you tap "Use my
current location," to find your ZIP.

## How your information is used

- To show outages in and near the areas you save.
- To send you a push notification when an outage affects one of your areas, its
  restoration estimate changes materially, power is restored, or a Public Safety
  Power Shutoff is planned for your area.
- To keep the service running and prevent abuse.

We do **not** sell your information, show ads, or use third-party advertising or
analytics/tracking SDKs.

## Who your information is shared with

To provide the service, limited data is processed by these providers:

- **Google** — Firebase Cloud Messaging delivers push notifications (using your
  anonymous token); Google Cloud (Firestore, Cloud Run, Cloud Storage) hosts our
  servers and database.
- **Anthropic** — powers the optional plain-language "What's going on?"
  explanation on an outage. We send only the outage's facts (cause, estimated
  restoration, customer count, etc.). We do **not** send your location, saved
  areas, or device token.
- **Geocoding services** — when you type an address or ZIP, it is sent to the
  U.S. Census Bureau geocoder and Photon (an OpenStreetMap service) to find its
  coordinates.
- **OpenFreeMap / OpenMapTiles / OpenStreetMap** — provide the map tiles the app
  draws. Your approximate map view is requested from them.
- **PG&E** — we read PG&E's public outage map to get outage data. We do **not**
  send PG&E any of your information.

## Data retention

Your saved areas and notification token are kept until you remove the area in the
app (which deletes it from our server) or your notification token becomes
invalid (we prune dead tokens automatically). Uninstalling the app removes the
data stored on your device.

## Your choices

- Remove any saved area in the app, or in Settings, at any time.
- Turn off notifications for OutageWatch in your phone's system settings.
- Uninstall the app to stop all collection and remove local data.

## Security

Data is sent over encrypted connections (HTTPS) and stored on Google Cloud. No
system is perfectly secure, but we keep the data we hold minimal and anonymous.

## Children

OutageWatch is not directed to children under 13, and we do not knowingly
collect personal information from them.

## Changes to this policy

We may update this policy; we will change the "Last updated" date above.
Continued use of the app after an update means you accept the revised policy.

## Contact

Questions about this policy or your data: open an issue at
https://github.com/nicglazkov/outagewatch/issues.
