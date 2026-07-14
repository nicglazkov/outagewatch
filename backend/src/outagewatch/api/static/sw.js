/* OutageWatch app shell service worker (offline-friendly, PWA install).
   Served at /sw.js so its scope is the whole site. */
var CACHE = "ow-shell-v1";
var SHELL = [
  "/",
  "/static/css/app.css",
  "/static/js/app.js",
  "/static/js/firebase-config.js",
  "/static/icons/icon-192.png",
  "/static/manifest.webmanifest"
];

self.addEventListener("install", function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(SHELL); }).then(function () { return self.skipWaiting(); }));
});

self.addEventListener("activate", function (e) {
  e.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener("fetch", function (e) {
  if (e.request.method !== "GET") return;
  var url = new URL(e.request.url);
  // Never cache API responses; always go to the network for live data.
  if (url.pathname.indexOf("/v1/") === 0 || url.pathname === "/healthz") return;
  // Only handle same-origin GETs; let the CDN (maps) and cross-origin pass through.
  if (url.origin !== location.origin) return;
  e.respondWith(
    caches.match(e.request).then(function (hit) {
      var net = fetch(e.request).then(function (res) {
        var copy = res.clone();
        caches.open(CACHE).then(function (c) { c.put(e.request, copy); });
        return res;
      }).catch(function () { return hit; });
      return hit || net;
    })
  );
});
