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

### 3. First upload (Claude, one command once 1 and 2 are done)

Archive with the release configuration, export with method `app-store-connect`
and destination `upload`, authenticated by the ASC API key. Then in App Store
Connect, TestFlight tab: the build appears after processing (minutes).

### 4. Turn on external testing (Nic, once)

1. TestFlight tab, **External Testing**, create a group (say `Public`), enable
   **Public Link**.
2. Add the build to the group and submit for Beta App Review, using the Test
   Information from the appendix.
3. When it clears, put the public link on the website: the iPhone card on the
   landing page has a placeholder button waiting for it
   (`backend/src/outagewatch/api/static/index.html`).

### 5. Quarterly refresh

Bump `CFBundleVersion`, re-run the step 3 upload, add the build to the group.
Existing testers auto-update. This is the recurring cost of TestFlight-only;
ask Claude to automate it on a schedule once the API key exists.

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
