import SwiftUI

@main
struct iOSApp: App {
    // AppDelegate is where the shared Kotlin singletons get filled in, the same
    // job MainActivity.onCreate does on Android.
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
