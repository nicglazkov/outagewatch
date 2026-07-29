import FirebaseCore
import FirebaseMessaging
import Shared
import UIKit
import UserNotifications

/// The iOS counterpart of Android's `MainActivity.onCreate`: it fills in the
/// platform singletons the shared Compose UI reads, wires push, and heals any
/// subscription that was saved before a token existed.
final class AppDelegate: NSObject, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        AppInfo.shared.version =
            Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        ExternalLinks.shared.opener = { url in
            guard let target = URL(string: url) else { return }
            UIApplication.shared.open(target)
        }
        // Setting the finder is also what reveals "Use my current location":
        // DeviceLocation.available is simply finder != null.
        DeviceLocation.shared.finder = IosLocationFinder()
        PushTokens.shared.provider = IosPushTokenProvider()

        configurePush(application)

        // A notification tap that cold-launched the app.
        if let payload = launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
            openOutage(from: payload)
        }

        Task {
            // Same order as MainActivity: make existing places address-only,
            // then re-register anything that never got a subscription.
            try? await AppGraph.shared.locations.migrateAreaAlertsOff()
            try? await AppGraph.shared.locations.retryMissingSubscriptions()
        }
        return true
    }

    /// Firebase aborts the process when its config is absent, so a missing
    /// plist is treated the way Android treats a missing google-services.json:
    /// skip push entirely and leave the app fully usable read-only.
    private func configurePush(_ application: UIApplication) {
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else {
            NSLog("OutageWatch: no GoogleService-Info.plist, push disabled")
            return
        }
        FirebaseApp.configure()
        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
                guard granted else { return }
                DispatchQueue.main.async { application.registerForRemoteNotifications() }
            }
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Messaging.messaging().apnsToken = deviceToken
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        // Expected until the Push Notifications capability is enabled, which
        // needs a paid Apple Developer team. No token means no alerts, and the
        // rest of the app carries on.
        NSLog("OutageWatch: remote registration failed, %@", error.localizedDescription)
    }

    /// The push payload carries the outage id in its data block; handing it to
    /// PendingOutage is what makes the shared App() navigate to that outage.
    private func openOutage(from payload: [AnyHashable: Any]) {
        guard let outageId = payload["outage_id"] as? String else { return }
        PendingOutage.shared.open(outageId: outageId)
    }
}

extension AppDelegate: MessagingDelegate {
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        IosPush.shared.token = fcmToken
        // A place saved before the token arrived has no subscription yet. Retry
        // now rather than leaving the user unalerted until the next launch.
        Task { try? await AppGraph.shared.locations.retryMissingSubscriptions() }
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    /// Android posts a high-importance notification even while in use, so show
    /// the banner here too instead of swallowing it.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        openOutage(from: response.notification.request.content.userInfo)
        completionHandler()
    }
}
