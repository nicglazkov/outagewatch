# iOS build handoff

What is wired, what is verified, and the short list of account-gated steps left
before OutageWatch can ship on iOS. Read `../CLAUDE.md` for the whole-project map
first.

## Current state (verified 2026-07-29)

The shared Compose UI, the four platform singletons, and the Xcode project are
all wired. `mobile/iosApp` builds and runs in the simulator.

**Verified end to end on this machine** (Xcode 26.3, iPhone 17 simulator, iOS 26.3),
by driving the running app, not by inspection:
- Builds, installs, launches, and renders the shared Compose UI.
- **Location:** "Use my current location" appears (it only renders when
  `DeviceLocation.available`), the permission prompt shows the `Info.plist`
  string, and with the simulator positioned in San Francisco the flow geocodes
  the point, saves it as a place, and the home screen shows live outage data.
- **Map:** the `WKWebView` map renders tiles and outage markers, so
  `OutageMapView.ios.kt` works.
- **Version:** Settings shows 0.2.5 from `AppInfo.version`, and no update row,
  since the updater is correctly gated off for iOS.
- **Links:** Privacy Policy opens `PRIVACY.md` in Safari via `ExternalLinks`.
- **Push handling:** `xcrun simctl push` with a real outage id shows the
  foreground banner (so `willPresent` is wired) and tapping it navigates to that
  outage's detail screen (so `didReceive` to `PendingOutage` to shared `App()`
  navigation is wired).
- Android is unaffected: `:shared:testAndroidHostTest` and
  `:androidApp:assembleDebug` still pass.

**Push verified end to end on a real device (July 29, 2026, iPhone 17 Pro,
iOS 26.5):** the APNs key authenticated with Apple, the FCM token was issued, a
`platform: "ios"` subscription reached Firestore, an FCM v1 push was delivered
over APNs to the lock screen, and tapping it navigated to the right outage. The
self-heal path was exercised for real too: the place was saved before a token
existed, and the token-arrival retry in `AppDelegate` registered the
subscription on the next launch.

**The four singletons, all now set** in `iosApp/iosApp/AppDelegate.swift`
(the iOS counterpart of Android's `MainActivity.onCreate`):

| Singleton | iOS implementation |
|---|---|
| `AppInfo.version` | `CFBundleShortVersionString`, kept in step with the Android `versionName` |
| `ExternalLinks.opener` | `UIApplication.shared.open` |
| `DeviceLocation.finder` | `IosLocationFinder` (Kotlin/Native, `shared/src/iosMain/.../location/`) |
| `PushTokens.provider` | `IosPushTokenProvider` reading `IosPush.token`, which Swift fills from FCM |

`AppDelegate` also mirrors `MainActivity`'s launch work: `migrateAreaAlertsOff()`
then `retryMissingSubscriptions()`. Both are `suspend`, which Kotlin/Native
exports to Swift as `async`, so they are plain `try await` calls in a `Task`.

## What is left, and why

Everything remaining is gated on an **Apple Developer Program membership**
($99/yr). Nothing in the repo blocks it.

1. **Enroll**, then put the Team ID in `mobile/iosApp/Configuration/Config.xcconfig`
   (`TEAM_ID=`, currently empty). Simulator builds do not need it; device builds,
   push, and TestFlight do.
2. **Push Notifications capability.** In Xcode, Signing and Capabilities, add
   *Push Notifications*. This writes an `aps-environment` entitlement, which only
   a paid team can provision, so it is deliberately **not** committed. Without it
   `registerForRemoteNotifications()` fails, `AppDelegate` logs it, and the app
   runs fine without alerts. `UIBackgroundModes: remote-notification` is already
   in `Info.plist` and needs no entitlement.
3. **APNs auth key.** Create a `.p8` key in the Apple Developer portal and upload
   it to Firebase, Project Settings, Cloud Messaging, Apple app configuration.
   This is what lets FCM reach APNs. There is no API for this; it is portal-only.
4. **TestFlight only, no App Store listing.** Bundle id
   `com.glazkov.outagewatch`; the whole plan, including the quarterly build
   refresh TestFlight demands, is in [`TESTFLIGHT.md`](TESTFLIGHT.md).

## Firebase config

`GoogleService-Info.plist` belongs at `mobile/iosApp/iosApp/` and is
**gitignored on purpose**, matching how Android keeps its Firebase values out of
the repo.

It is deliberately **not** a build input of the Xcode target. An "Embed Firebase
config" build phase copies it into the bundle when present and emits a warning
when absent, so a fresh clone still builds; it just runs without push. That
mirrors `OutageWatchApp.initFirebase()` on Android, which skips Firebase when its
config is missing. `AppDelegate.configurePush` makes the same check at runtime
before calling `FirebaseApp.configure()`, which would otherwise abort the process.

To create the iOS app and fetch the plist:

The iOS app already exists in Firebase (`1:468206169285:ios:10cb2c0f7f60dd46b994ac`,
bundle id `com.glazkov.outagewatch`), so fetching the config is all that is
needed on a new machine:

```bash
npm install -g firebase-tools
firebase login
firebase --project outagewatch apps:sdkconfig IOS 1:468206169285:ios:10cb2c0f7f60dd46b994ac \
  --out mobile/iosApp/iosApp/GoogleService-Info.plist
```

Always pass `--project outagewatch` rather than running `firebase use`, which
would write a `.firebaserc` into the repo.

## Build notes

```bash
cd mobile
sh gradlew :shared:compileKotlinIosSimulatorArm64        # sanity-compile the iOS target
open iosApp/iosApp.xcodeproj
```

- Invoke Gradle as `sh gradlew`. `gradlew` is committed without its executable
  bit, so `./gradlew` fails; CI and the Xcode build phase both use `sh`.
- The Xcode "Compile Kotlin Framework" phase resolves `JAVA_HOME` itself via
  `/usr/libexec/java_home`, because a build phase inherits neither `JAVA_HOME`
  nor a JDK on its `PATH`.
- Firebase comes in through Swift Package Manager (`firebase-ios-sdk`, pinned to
  12.x for its iOS 15 floor, matching the project's 15.3 deployment target).

## Gotchas found the hard way

- **`PendingOutage.id.value = x` does not compile in Swift.** Kotlin/Native
  exports `MutableStateFlow` as a protocol inheriting `StateFlow`, and the
  Objective-C bridge drops the setter the subtype adds, so Swift sees a get-only
  `value`. Call `PendingOutage.shared.open(outageId:)` instead. Android goes
  through the same helper.
- **`WKNavigationType` is not a Kotlin enum class.** Its last case is
  `Other = -1`, which breaks the contiguous sequence cinterop needs, so it
  degrades to a typealias over `NSInteger` with top-level constants. Import
  `WKNavigationTypeLinkActivated` directly rather than qualifying it.
- **`LocationResult.Found` carries `(lat, lon, zip)`**, not just a ZIP. The
  current-location flow saves a precise address, so the finder returns the real
  GPS point.
- Never wire an in-app "download update" path on iOS. The GitHub-Releases
  updater is gated to Android in `shared/.../ui/App.kt`; TestFlight (or the
  App Store, if that ever changes) owns iOS updates.
- Keep the Compass design system and the house style. The UI is shared, so this
  mostly takes care of itself; just do not add iOS chrome that breaks it.

## Testing without a paid account

`xcrun simctl push` injects a notification straight into the simulator, so the
whole tap-through path can be exercised with no APNs key and no device:

```bash
xcrun simctl push <device> com.glazkov.outagewatch payload.json
```

with a payload carrying `"outage_id"` alongside the `aps` block, matching what
`push.py` sends:

```json
{
  "Simulator Target Bundle": "com.glazkov.outagewatch",
  "aps": { "alert": { "title": "Outage near Home", "body": "..." }, "sound": "default" },
  "outage_id": "317288"
}
```

The notification delegate is only installed when Firebase config is present, so
this needs `GoogleService-Info.plist` in place first.

Driving the UI from a script needs no extra tooling beyond `cliclick` and
Accessibility permission for the terminal. The Simulator exposes the device
screen as its window's first `AXGroup`, so the origin can be read live and
device points map 1:1 onto screen coordinates:

```sh
osascript -e 'tell application "System Events" to tell process "Simulator" \
  to tell window 1 to get position of group 1'
```
