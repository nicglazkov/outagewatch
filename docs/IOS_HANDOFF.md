# iOS build handoff

Everything you need to take OutageWatch's iOS target from "renders read-only" to
"shippable." The shared Kotlin UI and the backend are done and iOS-ready; the
work is a bounded set of **platform wiring on the Mac** plus Firebase/signing
setup. Read `../CLAUDE.md` for the whole-project map first.

## Current state (verified 2026-07-29)

**Works today on iOS** (the whole Compose UI runs via `iosApp` -> `MainViewController()` -> `App()`):
- Browsing outages, the statewide map, ZIP lookup, and address search **via autocomplete** (backend geocoder) — all read-only paths that only need the public API.
- The live map: `shared/src/iosMain/.../ui/map/OutageMapView.ios.kt` is a complete `WKWebView` implementation (mirrors Android). No work needed.
- `platformName()` returns `"ios"`.

**Not wired yet** — these four platform singletons are `null`/empty on iOS because
nothing sets them (Android sets all four in `androidApp/.../MainActivity.kt`):

| Singleton (shared Kotlin) | What it does | Android reference to mirror | User-visible gap on iOS today |
|---|---|---|---|
| `AppInfo.version` | Settings "Version" row; gates the (Android-only) updater | `MainActivity`: `AppInfo.version = BuildConfig.VERSION_NAME` | Version shows blank |
| `ExternalLinks.opener` | Opens URLs (Privacy, Terms, Send feedback, Download links) | `MainActivity`: `ExternalLinks.opener = { startActivity(ACTION_VIEW) }` | Those taps do nothing |
| `DeviceLocation.finder` (`LocationFinder`) | "Use my current location" + on-device free-text geocode | `androidApp/.../location/AndroidLocationFinder.kt` | "Use my current location" and raw-address save fail; autocomplete + ZIP still work |
| `PushTokens.provider` (`PushTokenProvider`) | Supplies the FCM token so alerts can be delivered | `androidApp/.../push/AndroidPushTokenProvider.kt` + `OutagePushService.kt` | No push notifications at all |

All four are plain settable singletons (`object PushTokens { var provider }`, etc.),
so from Swift you set e.g. `PushTokens.shared.provider = ...`, `AppInfo.shared.version = ...`.

**Already handled for you (do not redo):**
- Backend accepts `platform: "ios"` and already sends APNs (`SubscriptionIn.platform` regex + `APNSConfig` in `backend/src/outagewatch/push.py`). No backend change needed.
- The notification model: a subscription is **address-only by default** (alerts only when an outage covers its point); area alerts are per-place opt-in. This is entirely in shared code + backend — iOS inherits it for free. See `watcher/matcher.py` and the memory in `CLAUDE.md`.
- The GitHub-Releases self-updater is now **gated to Android** (`shared/.../ui/App.kt`) — iOS updates via the App Store, so never wire an in-app APK updater.

## Setup steps (Mac)

### 1. Toolchain + first build
- Xcode (recent), an Apple Developer account, and a JDK 17+ (`JAVA_HOME`).
- Build the shared framework, then open the Xcode project:
  ```bash
  cd mobile
  ./gradlew :shared:compileKotlinIosSimulatorArm64   # sanity-compile the iOS target
  open iosApp/iosApp.xcodeproj
  ```
- The Xcode project embeds the `Shared` framework produced by Gradle. Confirm it
  builds and runs the UI in the simulator **before** wiring anything — that
  proves the read-only path end to end.

### 2. Firebase iOS app + config
- In the Firebase console for project **`outagewatch`**, add an **iOS app** with
  bundle id `com.glazkov.outagewatch`.
- Download **`GoogleService-Info.plist`** into `mobile/iosApp/iosApp/` and add it
  to the Xcode target. It is **gitignored on purpose** (matches the Android
  `google-services.json` handling) — do not commit it.
- For push: create an **APNs auth key (.p8)** in the Apple Developer portal and
  upload it under Firebase -> Project Settings -> Cloud Messaging -> Apple app
  configuration. This is what lets FCM deliver to APNs.
- Add Firebase iOS SDK (Swift Package Manager is simplest): at minimum
  `FirebaseMessaging`.

### 3. Signing + capabilities
- Set `TEAM_ID` in `mobile/iosApp/Configuration/Config.xcconfig` (currently empty)
  to your Apple Developer Team ID. Bundle id / app name are already set there.
- In Xcode "Signing & Capabilities" add: **Push Notifications**, and **Background
  Modes -> Remote notifications**.
- `Info.plist`: add `NSLocationWhenInUseUsageDescription` (a short reason string)
  for the location permission prompt.

## The wiring work

Do all four in the iOS entry point so they're set before the UI needs them. The
natural home is a `UIApplicationDelegate` (create `AppDelegate.swift`, attach via
`@UIApplicationDelegateAdaptor` in `iOSApp.swift`) plus the existing
`ContentView`. Mirror what `MainActivity.onCreate` does on Android.

### A. Trivial two (start here)
```swift
// In app startup (Swift), before/at first render:
AppInfo.shared.version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
ExternalLinks.shared.opener = { url in
    if let u = URL(string: url) { UIApplication.shared.open(u) }
}
```

### B. Location (`DeviceLocation.finder`)
Contract to satisfy (from `shared/.../data/LocationFinder.kt`):
- `currentZip(): LocationResult` — must return `LocationResult.Found(lat, lon, zip)`
  with the **exact GPS coordinates** (not just the ZIP). The current-location flow
  now saves a precise address, so the raw lat/lon matter. Return
  `PermissionDenied` / `Unavailable` as appropriate.
- `geocodeAddress(query): GeoResult?` — forward-geocode free text to
  `GeoResult(lat, lon, zip, name)`, or null.

Recommended: implement in **Kotlin/Native** (`shared/src/iosMain/.../data/`) using
`platform.CoreLocation.CLLocationManager` + `CLGeocoder`, to keep parity with the
Android Kotlin finder — `suspend` is natural there. (Alternative: a Swift
implementation bridged with a continuation; fine, but you must bridge the two
`suspend` functions.) Then set `DeviceLocation.finder` at startup. Note that
`DeviceLocation.available` becomes true automatically once `finder` is non-null,
which is what reveals the "Use my current location" button.

### C. Push (`PushTokens.provider`) — the biggest piece
Firebase + APNs live naturally in Swift. Recommended split that avoids
implementing a `suspend` Kotlin interface from Swift: Swift owns Firebase and
writes the token into a tiny Kotlin holder; Kotlin's provider just returns it.

Add to `shared/src/iosMain/.../push/` (Kotlin):
```kotlin
object IosPush { @kotlin.concurrent.Volatile var token: String? = null }
class IosPushTokenProvider : PushTokenProvider {
    override suspend fun getToken(): String? = IosPush.token
}
```
Swift `AppDelegate` (starting point — complete + compile on the Mac):
```swift
import FirebaseCore
import FirebaseMessaging
import UserNotifications
import Shared

class AppDelegate: NSObject, UIApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate {
  func application(_ app: UIApplication, didFinishLaunchingWithOptions o: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
    FirebaseApp.configure()
    Messaging.messaging().delegate = self
    UNUserNotificationCenter.current().delegate = self
    PushTokens.shared.provider = IosPushTokenProvider()   // shared code reads IosPush.token
    // request permission, then registerForRemoteNotifications() on grant
    return true
  }
  func messaging(_ m: Messaging, didReceiveRegistrationToken token: String?) {
    IosPush.shared.token = token                          // hand the FCM token to shared code
  }
  // On notification tap, open the outage the same way Android does:
  //   PendingOutage.shared.id.value = userInfo["outage_id"] as? String
  // The shared App() observes PendingOutage.id and navigates to the detail screen.
}
```
Key parity points with Android (`OutagePushService.kt` + `MainActivity`):
- The push payload carries `data["outage_id"]`; a tap must set
  `PendingOutage.shared.id.value` to it so `App()` navigates to that outage.
- Foreground presentation: implement `willPresent` to show the banner (Android
  shows a high-importance notification).
- The subscription itself is created by the existing shared repository once a
  token exists — no iOS-specific subscribe code needed; it self-heals on launch
  (`retryMissingSubscriptions`). Just make sure the token is available.

## Testing plan

1. **Read-only (simulator, no Firebase):** UI, statewide map, ZIP + autocomplete
   address search, saved-place status. Confirms the shared path is healthy.
2. **Location (simulator or device):** "Use my current location" saves a precise
   address; the permission prompt uses your `Info.plist` string.
3. **Push (real device or a push-capable simulator):** grant notifications, add a
   place, confirm a token registers (backend stores a `platform:"ios"` sub), and
   that a delivered push taps through to the right outage. You can send a test
   push from the Firebase console to the device token.

## Store logistics (task still open)

- Bundle id `com.glazkov.outagewatch`; ship via **TestFlight**, then App Store.
  The app's own copy targets **August 2026** for the App Store listing.
- Reuse the existing `PRIVACY.md` / `TERMS.md` (rendered on the public GitHub repo
  and linked in-app) for the store privacy/terms.

## Gotchas

- **`LocationResult.Found` now carries `(lat, lon, zip)`** — an older mental model
  had it as just a ZIP. The iOS finder must return real coordinates.
- Keep the **Compass** design system and the house style (no emoji / em-dashes /
  middle-dots). The UI is shared, so you mostly inherit this — just don't add iOS
  chrome that breaks it.
- Don't wire any in-app "download update" path on iOS (the updater is gated off
  for good reason — App Store owns updates).
- The public API needs no auth and no CORS handling for a native client; the app
  already points at the production base URL.
