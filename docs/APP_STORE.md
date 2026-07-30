# Shipping OutageWatch on iOS

Current as of July 29, 2026. The code side is finished and merged; see
[`IOS_HANDOFF.md`](IOS_HANDOFF.md) for what is wired and how it was verified.
What remains is account work only Nic can do, spelled out below, followed by
the reference material for App Store Connect.

## Already done

- **Apple Developer enrolment (Individual).** The Team ID is in
  `~/Desktop/outagewatch.env` and flows into the build through the secrets
  pipeline; `DEVELOPMENT_TEAM` resolves in the Xcode build settings. Converting
  the account to an Organization once the LLC exists is a supported Apple
  Support request that keeps the apps and bundle ids in place.
- **Push Notifications capability.** `iosApp.entitlements`
  (`aps-environment=development`) is committed and wired into both build
  configurations. Xcode's automatic signing swaps it to production when
  archiving for the App Store.
- **Firebase iOS app** (`1:468206169285:ios:10cb2c0f7f60dd46b994ac`, bundle id
  `com.glazkov.outagewatch`) exists, and `GoogleService-Info.plist` is on this
  Mac, gitignored. To refetch on a new machine:
  `firebase --project outagewatch apps:sdkconfig IOS 1:468206169285:ios:10cb2c0f7f60dd46b994ac --out mobile/iosApp/iosApp/GoogleService-Info.plist`
- **Secrets pipeline.** Everything sensitive goes in `~/Desktop/outagewatch.env`
  (outside the repo), and `sh mobile/scripts/sync-secrets.sh` fans it out to the
  gitignored files the builds read.
- **Submission kit.** Screenshots at Apple's 6.9-inch size in
  [`appstore/iphone-6.9/`](appstore/iphone-6.9/), the privacy manifest in the
  app bundle, and the full listing copy and privacy answers below.

## Your remaining steps, in order

### 1. Sign in to Xcode (one minute, one time)

This Mac has no code-signing identity yet, so a device build cannot sign until
Xcode holds your account.

1. Open Xcode.
2. **Xcode > Settings > Accounts**, click **+**, choose **Apple ID**.
3. Sign in with the Apple ID you enrolled with.

Xcode mints the Apple Development certificate automatically on the first device
build; there is nothing else to click.

### 2. Create the APNs auth key

This is what lets Firebase deliver to Apple. There is no API for it; the portal
is the only way.

1. Go to <https://developer.apple.com/account/resources/authkeys/list>.
2. Click **+** (Create a key).
3. Name it something like `OutageWatch APNs`.
4. Tick **Apple Push Notifications service (APNs)**. Two required fields appear:
   - **Environment**: pick **Sandbox & Production**. A sandbox-only key works in
     development then silently fails once you ship.
   - **Key Restriction**: pick **Team Scoped (All Topics)**. Firebase's upload
     form does not handle topic-scoped keys well, and team scope covers any
     future app too.
5. Click **Continue**, then **Register**.
6. Click **Download**. You get a `.p8` file.

**The `.p8` downloads exactly once.** Save it outside this repo, somewhere you
keep permanently, not Downloads. `*.p8` is gitignored as a backstop, but the key
can send push as us, so it does not belong anywhere near the tree.

Apple allows only **two active APNs auth keys per team**, so do not create spares
while experimenting. If you lose one, revoke it and register a new one.

Then record it in the env and sync, so the machine knows where it lives:

```bash
# in ~/Desktop/outagewatch.env set:
#   APNS_KEY_ID=<the 10-character id shown next to the key>
#   APNS_KEY_PATH=<absolute path to the saved .p8>
sh mobile/scripts/sync-secrets.sh
```

### 3. Give the key to Firebase

1. Go to <https://console.firebase.google.com/project/outagewatch/settings/cloudmessaging>.
2. Under **Apple app configuration**, find the `com.glazkov.outagewatch` app.
3. Under **APNs Authentication Key**, click **Upload**.
4. Upload the `.p8`, and enter the **Key ID** and the **Team ID** (both are in
   your env file).

A typo in the Key ID fails silently: FCM simply never issues a token. The key can
be checked locally, without it leaving the Mac, by minting an APNs JWT from it and
calling Apple's endpoint; ask Claude once the env has the key id and path.

### 4. Test on your own iPhone

No TestFlight needed for this, and there is no review. Plug the phone in and say
so; the checklist is:

1. Plug your iPhone into the Mac. Unlock it and trust the computer.
2. In Xcode, pick your iPhone from the device menu at the top, and press Run.
3. First run only: on the phone, **Settings > General > VPN & Device Management**,
   and trust your developer certificate.
4. In the app, add a place, then confirm a `platform: "ios"` subscription reaches
   the backend.
5. Send a test push from
   <https://console.firebase.google.com/project/outagewatch/notification>, or wait
   for a real outage to reach your saved place.

This is the single remaining unverified link: everything downstream of the FCM
token is proven in the simulator, but no token can exist until steps 2 and 3 are
done.

### 5. TestFlight, then the App Store

- **Internal testing** (you and up to 100 people on your team) skips Beta App
  Review. This is the fastest way to try a real build.
- **External testing** (up to 10,000) needs a one-time Beta App Review, usually a
  day or two.
- Then create the listing in App Store Connect using the material below, and
  submit for review.

## App Store Connect answers

### Listing

| Field | Value |
|---|---|
| Name | OutageWatch |
| Subtitle | PG&E outage alerts |
| Bundle ID | `com.glazkov.outagewatch` |
| Primary category | Utilities |
| Secondary category | Weather |
| Age rating | 4+ |
| Price | Free |
| Privacy Policy URL | `https://github.com/nicglazkov/outagewatch/blob/main/PRIVACY.md` |
| Support URL | `https://github.com/nicglazkov/outagewatch/issues` |

Screenshots for the required 6.9-inch size are in
[`appstore/iphone-6.9/`](appstore/iphone-6.9/). The app ships iPhone-only, so no
iPad set is needed.

**Say in the review notes that the app is not affiliated with PG&E and reads only
PG&E's public outage map.** Reviewers are wary of apps that appear to represent a
utility. The disclaimer is already on the home screen and the outage detail
screen.

### Listing copy

**Subtitle** (30 characters max, currently 18):

```
PG&E outage alerts
```

**Promotional text** (170 max, editable without a new build):

```
Know the moment an outage reaches your address, not just your neighborhood. Free, no account, no ads.
```

**Keywords** (100 characters max, comma separated, no spaces after commas). Do
not repeat the app name or subtitle here; Apple indexes those separately.

```
power,blackout,pge,psps,electricity,grid,utility,storm,alerts,california,restoration
```

**Description**:

```
OutageWatch tells you when a PG&E power outage reaches the places you care about.

Add your home by address and you only hear about outages that actually cover it.
Add a whole ZIP code and you hear about anything in that area. Either way you get
a push notification the moment PG&E reports it, and another when the estimate
changes or the power comes back.

What you get

- Live outage status for every place you save
- A map of what is out around you
- Cause, crew status, customers affected, and the estimated restoration time
- A plain-language summary of what is actually going on, instead of utility jargon
- Public Safety Power Shutoff warnings
- Quiet hours, so an overnight outage does not wake you. PSPS warnings always
  come through

No account. No sign-up. No ads. No tracking. You never give us a name, an email,
or a phone number.

OutageWatch is an independent app. It is not affiliated with, endorsed by, or
operated by PG&E. Data comes from PG&E's public outage map and can lag behind
what is happening on the ground. For emergencies call 911. Report downed power
lines to PG&E at 1-800-743-5000.
```

**What's New** (first release):

```
First release.
```

### App Privacy questionnaire

Answer **No** to "Do you or your third-party partners use data for tracking?".
The app has no analytics or advertising SDK, so no App Tracking Transparency
prompt is needed.

Then declare three data types. Every one is **App Functionality**, **not linked
to the user's identity**, and **not used for tracking**, because there is no
account to link anything to.

| Data type | Why it is collected |
|---|---|
| Location > Precise Location | Read only when the user taps "Use my current location", and stored as the point a subscription watches |
| Identifiers > Device ID | The Firebase Cloud Messaging token, the only way to address a push to this install |
| User Content > Other User Content | The nickname given to a place ("Home", "Mom's place"), stored so an alert can name it |

Not collected, so leave unticked: contact info, health, financial, contacts,
browsing history, search history, purchases, diagnostics, product interaction,
advertising data, and any other identifier.

The same declaration ships in the app as
`mobile/iosApp/iosApp/PrivacyInfo.xcprivacy`, which Apple has required since 2024.
It also declares the two required-reason APIs the app touches: `NSUserDefaults`
(`CA92.1`, saved places and preferences) and system boot time (`35F9.1`, elapsed
time in the Kotlin/Native runtime). Keep the manifest and the questionnaire in
step with each other.
