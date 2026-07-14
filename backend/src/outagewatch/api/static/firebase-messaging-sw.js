/* OutageWatch background push service worker.
 * The Firebase config is passed in the registration URL query (?c=...) by
 * app.js, so it lives in one place (firebase-config.js) and stays in sync. */
importScripts("https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js");
importScripts("https://www.gstatic.com/firebasejs/10.12.2/firebase-messaging-compat.js");

try {
  var cfg = JSON.parse(new URL(self.location).searchParams.get("c") || "{}");
  if (cfg && cfg.projectId) {
    firebase.initializeApp(cfg);
    var messaging = firebase.messaging();
    messaging.onBackgroundMessage(function (payload) {
      var n = (payload && payload.notification) || {};
      self.registration.showNotification(n.title || "OutageWatch", {
        body: n.body || "",
        icon: "/static/icons/icon-192.png",
        badge: "/static/icons/icon-192.png",
        data: (payload && payload.data) || {},
        tag: (payload && payload.data && payload.data.outage_id) || "outagewatch"
      });
    });
  }
} catch (e) { /* config not set yet; push stays inactive */ }

self.addEventListener("notificationclick", function (event) {
  event.notification.close();
  var oid = event.notification.data && event.notification.data.outage_id;
  var url = "/";
  event.waitUntil(clients.openWindow(url));
});
