import SwiftUI
import Shared

@main
struct iOSApp: App {
    // Push registration (FCM over APNs) gets wired here on the Mac build:
    // configure Firebase iOS SDK, then set PushTokens.provider to feed the
    // shared code its token. The app is fully functional read-only without it.
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
