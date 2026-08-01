# Distributing OutageWatch on iOS (TestFlight)

Current as of July 29, 2026. **The decision: no App Store listing.** iOS ships
through TestFlight with a public link, the twin of how Android ships as a free
APK from GitHub Releases. The material for a store listing lives in the appendix
in case that ever changes; nothing needs to be undone to use it.

## What is already true

- Push is verified end to end on a real iPhone: APNs key (`Y5F979AJHA`)
  validated against Apple, FCM token issued, `platform: "ios"` subscription in
  Firestore, delivery to the lock screen, tap-through to the right outage.
- Xcode holds the developer account; signing works; the App ID and provisioning
  exist. The dev install on Nic's iPhone keeps working for about a year without
  TestFlight at all.
- Secrets flow through `~/Desktop/outagewatch.env` and
  `sh mobile/scripts/sync-secrets.sh`.

## How TestFlight-only works, and the one real cost

- **External testing with a public link**: up to 10,000 installs, shareable URL
  for the website, no store listing and no pricing. The first external build
  gets a one-time light Beta App Review (typically a day); later uploads usually
  clear automatically within minutes.
- **Builds expire 90 days after upload.** This is the price of never shipping to
  the store: a re-upload roughly quarterly, forever. With the ASC API key below,
  the refresh is one command and can be automated. If a build lapses, testers'
  installed apps stop launching until a new build is up.
- Internal testing (App Store Connect team members only) needs no review of any
  kind, but caps at the people added to the ASC account.

## Remaining steps

### 1. Create the app record in App Store Connect (Nic, once)

1. Go to <https://appstoreconnect.apple.com/apps>, click **+**, **New App**.
2. Platform **iOS**; Name **OutageWatch**; Primary language **English (U.S.)**;
   Bundle ID **com.glazkov.outagewatch** (it is in the menu because the App ID
   exists); SKU **outagewatch-ios** (internal only, never shown).
3. Full Access for user access.

No listing metadata is needed for TestFlight. Under **TestFlight > Test
Information**, it will eventually want a beta description, a feedback email, and
the privacy policy URL; take them from the appendix.

### 2. Create an App Store Connect API key (Nic, once, enables automated uploads)

1. <https://appstoreconnect.apple.com/access/integrations/api> (Users and
   Access, Integrations, App Store Connect API), **Team Keys**, **+**.
2. Name `outagewatch-upload`, role **App Manager**.
3. Download the `.p8` (again: downloads exactly once, keep it outside the repo)
   and note the **Key ID** and the **Issuer ID** shown at the top of the page.
4. Record all three in `~/Desktop/outagewatch.env` (`ASC_KEY_ID`,
   `ASC_ISSUER_ID`, `ASC_KEY_PATH`).

With those set, Claude can archive, sign, and upload builds non-interactively,
including the quarterly refresh.

### 3. First upload (done July 29, 2026; recipe for every refresh)

Build 1 (0.2.5) is uploaded, processed VALID, and attached to the `Public`
group. **The public link: <https://testflight.apple.com/join/YqeVwyat>** (goes
on the website once Beta App Review clears).

The working recipe, learned the hard way: an **App Manager** API key cannot
cloud-sign (that needs Admin), so the archive and upload authenticate with the
Xcode account session, and the API key does everything else (compliance, groups,
localizations, status polling). From `mobile/iosApp`:

```bash
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release   -destination 'generic/platform=iOS' -archivePath /tmp/OutageWatch.xcarchive   archive -allowProvisioningUpdates
xcodebuild -exportArchive -archivePath /tmp/OutageWatch.xcarchive   -exportOptionsPlist exportOptions.plist -exportPath /tmp/export   -allowProvisioningUpdates
```

with `exportOptions.plist` containing method `app-store-connect`, destination
`upload`, the team id, and `manageAppVersionAndBuildNumber` true. Export
compliance is pre-answered per build via the ASC API
(`usesNonExemptEncryption=false`, HTTPS only); adding
`ITSAppUsesNonExemptEncryption=false` to Info.plist would answer it permanently.

If the account session ever goes stale (signing errors out of nowhere), open
Xcode, Settings, Accounts, and sign in again.

### 4. Submit for Beta App Review (Nic, once)

The group, public link, build attachment, beta description, and feedback email
are all done via the API. What the API cannot supply is the review contact
phone number, so in App Store Connect, TestFlight tab:

1. **Test Information**, fill the Beta App Review contact fields (name, phone,
   email); review notes: "Not affiliated with PG&E; reads only PG&E's public
   outage map. No account or sign-in." Save.
2. **External Testing > Public**: build 0.2.5 (1) shows **Ready to Submit**;
   click **Submit for Review**.
3. Done: Beta App Review **approved July 30, 2026**. The public link is open
   to everyone and wired into the landing page's iPhone card.

### 5. The refresh is CI now

`.github/workflows/testflight.yml` uploads a fresh build **monthly** (the 3rd,
16:23 UTC) on a free public-repo macOS runner, so the live build is never
older than about a month against the 90-day expiry. Build numbers come from
`manageAppVersionAndBuildNumber`, signing is Apple cloud signing driven by an
**Admin**-role ASC API key (App Manager cannot cloud-sign), and
`.github/scripts/testflight_release.py` attaches the processed build to the
Public group and submits it (auto-cleared after the first review).

Repo secrets it needs: `ASC_ADMIN_KEY_P8` (base64 `.p8`), `ASC_ADMIN_KEY_ID`,
`ASC_ISSUER_ID`, `GOOGLE_SERVICE_INFO_PLIST` (base64; push must be compiled
into TestFlight builds). GitHub emails on workflow failure, and the step 3 manual
recipe remains the fallback. Proven end to end August 1, 2026: build 4 was
archived, uploaded, attached, and submitted with zero human steps, after two
real-service bugs (ASC upload lag, and offset-format timestamps compared as
strings) were found and fixed by the first cycles.

## Appendix: store material, kept in case the decision ever changes

Everything below was prepared for a full App Store listing and doubles as the
TestFlight Test Information source. Screenshots at Apple's 6.9-inch size are in
[`appstore/iphone-6.9/`](appstore/iphone-6.9/).

### Listing fields

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
| Support / feedback URL | `https://github.com/nicglazkov/outagewatch/issues` |

In any review notes (Beta App Review included): the app is not affiliated with
PG&E and reads only PG&E's public outage map. The disclaimer is on the home
screen and the outage detail screen.

### Copy

**Subtitle** (30 max, currently 18):

```
PG&E outage alerts
```

**Promotional text** (170 max):

```
Know the moment an outage reaches your address, not just your neighborhood. Free, no account, no ads.
```

**Keywords** (100 max, no spaces after commas):

```
power,blackout,pge,psps,electricity,grid,utility,storm,alerts,california,restoration
```

**Description** (also the TestFlight beta description):

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

### App Privacy questionnaire

Tracking: **No**. No analytics or advertising SDK, so no App Tracking
Transparency prompt.

Three data types, each **App Functionality**, **not linked to identity**, **not
used for tracking** (there is no account to link anything to):

| Data type | Why |
|---|---|
| Location > Precise Location | Read only on "Use my current location", stored as the point a subscription watches |
| Identifiers > Device ID | The FCM token, the only way to address a push to this install |
| User Content > Other User Content | The nickname given to a place, stored so an alert can name it |

Everything else unticked. The same declaration ships in the app as
`mobile/iosApp/iosApp/PrivacyInfo.xcprivacy`, together with the two
required-reason APIs (`NSUserDefaults` CA92.1, system boot time 35F9.1). Keep
the manifest and any questionnaire in step.
