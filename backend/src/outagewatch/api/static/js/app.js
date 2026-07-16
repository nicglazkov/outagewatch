/* OutageWatch web app. Talks to the same-origin public API. No framework. */
(function () {
  "use strict";
  var API = "";
  var $ = function (s) { return document.querySelector(s); };
  var state = { area: null, outages: [], statewide: [], center: null, territory: null, map: null, mapReady: false, timer: null, lastFetch: null };

  // Map colors read on both light (positron) and dark tiles.
  var C_OUT = "#dd4b2e", C_PSPS = "#e0913a", C_ME = "#2b5fd0";
  // PG&E territory overview, shown before you search so the panel is never empty.
  var HOME = { center: [-121.7, 38.85], zoom: 5.6 };

  // ---------- helpers ----------
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function empty() { return { type: "FeatureCollection", features: [] }; }
  function fmtEta(iso) {
    if (!iso) return "No restoration estimate yet";
    var d = new Date(iso);
    if (isNaN(d)) return "No restoration estimate yet";
    var s = d.toLocaleString("en-US", { timeZone: "America/Los_Angeles", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
    return (d < new Date() ? "Estimate passed: " : "Estimated restoration: ") + s;
  }
  function label(o) { return o.is_psps ? "PSPS shutoff" : "Power outage"; }
  function ago(ts) {
    if (!ts) return "";
    var m = Math.round((Date.now() - ts) / 60000);
    if (m < 1) return "just now";
    if (m === 1) return "1 minute ago";
    if (m < 60) return m + " minutes ago";
    return "over an hour ago";
  }
  function save(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }
  function load(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }

  // Does an outage actually reach a specific point? A polygon must contain it; a
  // coordinate-only outage counts only within ~250m. This mirrors the app's
  // precise-address rule, so an outage that is merely nearby never reads as
  // "your power is out".
  function haversineKm(aLat, aLon, bLat, bLon) {
    var R = 6371, rad = Math.PI / 180;
    var dLat = (bLat - aLat) * rad, dLon = (bLon - aLon) * rad;
    var s = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(aLat * rad) * Math.cos(bLat * rad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
  }
  function pointInRing(lon, lat, ring) {
    var inside = false;
    for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      var xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
      if (((yi > lat) !== (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) inside = !inside;
    }
    return inside;
  }
  function polyCovers(rings, lon, lat) {
    if (!rings.length || !pointInRing(lon, lat, rings[0])) return false;
    for (var k = 1; k < rings.length; k++) { if (pointInRing(lon, lat, rings[k])) return false; }
    return true;
  }
  function impacts(o, lat, lon) {
    var g = o.geometry;
    if (g && g.type === "Polygon") return polyCovers(g.coordinates, lon, lat);
    if (g && g.type === "MultiPolygon") return g.coordinates.some(function (p) { return polyCovers(p, lon, lat); });
    if (o.lat != null && o.lon != null) return haversineKm(lat, lon, o.lat, o.lon) <= 0.25;
    return false;
  }

  // ---------- mode tabs ----------
  document.querySelectorAll(".tab").forEach(function (t) {
    t.addEventListener("click", function () {
      document.querySelectorAll(".tab").forEach(function (x) { x.setAttribute("aria-selected", "false"); });
      t.setAttribute("aria-selected", "true");
      var zip = t.dataset.mode === "zip";
      $("#zip-form").classList.toggle("hidden", !zip);
      $("#addr-form").classList.toggle("hidden", zip);
    });
  });

  // ---------- ZIP ----------
  $("#zip-form").addEventListener("submit", function (e) {
    e.preventDefault();
    var z = $("#zip-input").value.trim();
    if (/^\d{5}$/.test(z)) { checkZip(z); }
  });

  function checkZip(zip, isAuto) {
    setLoading();
    save("ow_last", JSON.stringify({ zip: zip }));
    state.area = { zip: zip };
    state.center = null;
    state.territory = null;
    // The centroid says where to fly; the outages fill the list. They load
    // independently, and either order is fine: the map already shows the
    // statewide set and only flies once the centroid lands.
    fetch(API + "/v1/zips/" + zip).then(function (r) { return r.ok ? r.json() : null; }).then(function (info) {
      state.center = info ? { lat: info.lat, lon: info.lon } : null;
      state.territory = info;
      drawOutages();
      if (!isAuto) flyToSearch();
      renderTerritoryNotice();
    }).catch(function () {});
    fetch(API + "/v1/outages?zip=" + zip)
      .then(function (r) {
        if (r.status === 404) throw { code: 404 };
        if (!r.ok) throw { code: r.status };
        return r.json();
      })
      .then(function (list) { render(list); })
      .catch(function (err) {
        if (err && err.code === 404) showMsg("That is not a California ZIP we cover.");
        else showMsg("Could not reach the outage feed. Try again shortly.");
      });
  }

  // ---------- Address autocomplete ----------
  var acTimer, acItems = [], acSel = -1;
  $("#addr-input").addEventListener("input", function () {
    var q = this.value.trim();
    clearTimeout(acTimer);
    if (q.length < 3) { hideAc(); return; }
    acTimer = setTimeout(function () {
      fetch(API + "/v1/geocode/autocomplete?q=" + encodeURIComponent(q))
        .then(function (r) { return r.ok ? r.json() : []; })
        .then(showAc).catch(function () { hideAc(); });
    }, 220);
  });
  function showAc(items) {
    acItems = items || []; acSel = -1;
    var box = $("#addr-list");
    box.textContent = "";
    if (!acItems.length) { hideAc(); return; }
    acItems.forEach(function (s, i) {
      var it = el("div", "ac-item");
      it.appendChild(el("div", "t", s.title));
      it.appendChild(el("div", "s", s.subtitle + (s.pge ? "" : "  (not PG&E territory)")));
      it.addEventListener("click", function () { pickAddr(i); });
      box.appendChild(it);
    });
    box.classList.remove("hidden");
  }
  function hideAc() { $("#addr-list").classList.add("hidden"); }
  function pickAddr(i) {
    var s = acItems[i]; if (!s) return;
    $("#addr-input").value = s.title;
    hideAc();
    // An exact address: alerts should fire only if an outage covers it.
    state.area = { lat: s.lat, lon: s.lon, name: s.title, zip: s.zip, precise: true };
    checkPoint(s.lat, s.lon, s.zip);
  }
  $("#addr-form").addEventListener("submit", function (e) {
    e.preventDefault();
    if (acItems.length) pickAddr(acSel >= 0 ? acSel : 0);
  });
  document.addEventListener("keydown", function (e) {
    if ($("#addr-list").classList.contains("hidden")) return;
    if (e.key === "ArrowDown") { acSel = Math.min(acSel + 1, acItems.length - 1); markSel(); e.preventDefault(); }
    else if (e.key === "ArrowUp") { acSel = Math.max(acSel - 1, 0); markSel(); e.preventDefault(); }
  });
  function markSel() {
    var items = document.querySelectorAll(".ac-item");
    items.forEach(function (x, i) { x.classList.toggle("sel", i === acSel); });
  }

  // ---------- Geolocation ----------
  $("#locate-btn").addEventListener("click", function () {
    if (!navigator.geolocation) { showMsg("Location is not available in this browser."); return; }
    var btn = this; btn.textContent = "Locating...";
    navigator.geolocation.getCurrentPosition(function (pos) {
      btn.textContent = "Use my current location";
      state.area = { lat: pos.coords.latitude, lon: pos.coords.longitude, name: "Your location", precise: true };
      checkPoint(pos.coords.latitude, pos.coords.longitude, null);
    }, function () {
      btn.textContent = "Use my current location";
      showMsg("Could not get your location. Try a ZIP code instead.");
    }, { timeout: 10000, maximumAge: 60000 });
  });

  function checkPoint(lat, lon, zip, isAuto) {
    setLoading();
    state.center = { lat: lat, lon: lon };
    state.territory = null;
    drawOutages();
    if (!isAuto) flyToSearch();
    save("ow_last", JSON.stringify({ lat: lat, lon: lon, name: state.area && state.area.name }));
    fetch(API + "/v1/outages?lat=" + lat + "&lon=" + lon + "&radius_km=8&include_geometry=true")
      .then(function (r) { if (!r.ok) throw 0; return r.json(); })
      .then(function (list) { render(list); })
      .catch(function () { showMsg("Could not reach the outage feed. Try again shortly."); });
  }

  // ---------- Status line + render ----------
  function setStatus(parts, sub) {
    var l = $("#statusline"); l.textContent = "";
    parts.forEach(function (p) { l.appendChild(el("span", p.cls || null, p.t)); });
    $("#statussub").textContent = sub || "";
  }
  function areaName() {
    return (state.area && (state.area.name || (state.area.zip ? "ZIP " + state.area.zip : ""))) || "your area";
  }
  function setLoading() { setStatus([{ t: "Checking..." }], ""); $("#result").textContent = ""; }
  function showMsg(m) {
    setStatus([{ t: "Is your power out?" }], "");
    $("#result").textContent = "";
    $("#result").appendChild(el("div", "notice", m));
    state.outages = [];
    drawOutages();
  }

  function renderTerritoryNotice() {
    var r = $("#result");
    var ex = r.querySelector(".notice.territory");
    if (ex) ex.remove();
    if (state.territory && state.territory.pge === false) {
      var n = el("div", "notice territory", "This ZIP is served by " + (state.territory.served_by || "another utility") + ", not PG&E. Outage data may be limited.");
      r.insertBefore(n, r.firstChild);
    }
  }

  function render(list) {
    state.outages = list || [];
    state.lastFetch = Date.now();
    var name = areaName();
    var out = state.outages.length;
    // "Power is out" is only honest for an exact address an outage actually
    // covers. A ZIP is an area, and an outage that is merely nearby is not your
    // outage, so those read as "likely on, outage nearby" and steer you to the
    // precise address check.
    var precise = !!(state.area && state.area.precise && state.center);
    var covering = precise
      ? state.outages.filter(function (o) { return impacts(o, state.center.lat, state.center.lon); })
      : [];
    if (precise && covering.length) {
      setStatus([{ t: "Power is " }, { t: "out", cls: "out" }, { t: " at " + name + "." }],
        covering.length === 1 ? "An outage is affecting this address." : covering.length + " outages are affecting this address.");
    } else if (precise) {
      setStatus([{ t: "Power is likely " }, { t: "on", cls: "clear" }, { t: " at " + name + "." }],
        out ? out + (out === 1 ? " outage" : " outages") + " reported nearby, but none reaching this address." : "No outages near this address right now.");
    } else if (out) {
      setStatus([{ t: "Power is likely " }, { t: "on", cls: "clear" }, { t: " near " + name + "." }],
        out + (out === 1 ? " outage" : " outages") + " reported nearby. Enter your street address to see if yours is affected.");
    } else {
      setStatus([{ t: "No outages", cls: "clear" }, { t: " near " + name + "." }],
        "PG&E reports nothing in this area right now.");
    }

    var r = $("#result"); r.textContent = "";
    if (out) {
      var box = el("div", "outages");
      box.appendChild(el("h2", null, (precise && covering.length) ? (covering.length === 1 ? "The outage" : "The outages") : "Nearby outages"));
      state.outages.slice(0, 20).forEach(function (o) {
        var row = el("button", "orow" + (o.is_psps ? " psps" : ""));
        row.type = "button";
        row.appendChild(el("div", "sev"));
        var g = el("div", "g");
        g.appendChild(el("div", "title", label(o) + (o.city ? " in " + o.city : "")));
        var bits = [];
        if (o.cause) bits.push(o.cause);
        if (o.est_customers) bits.push(o.est_customers + (o.est_customers === 1 ? " customer" : " customers"));
        g.appendChild(el("div", "meta", bits.join(", ")));
        g.appendChild(el("div", "meta", fmtEta(o.eta)));
        row.appendChild(g);
        row.addEventListener("click", function () { openDetail(o); });
        box.appendChild(row);
      });
      r.appendChild(box);
      r.appendChild(el("p", "freshness", "Updated " + ago(state.lastFetch) + ". Data from PG&E, can lag."));
    }
    renderTerritoryNotice();
    drawOutages();
    scheduleRefresh();
  }

  // ---------- Map (one persistent instance) ----------
  function initMap() {
    if (typeof maplibregl === "undefined") return;
    var dark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
    var map = new maplibregl.Map({
      container: "map",
      style: dark ? "https://tiles.openfreemap.org/styles/dark" : "https://tiles.openfreemap.org/styles/positron",
      center: HOME.center, zoom: HOME.zoom, attributionControl: false
    });
    map.addControl(new maplibregl.AttributionControl({ compact: true }));
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    state.map = map;
    map.on("load", function () {
      map.addSource("p", { type: "geojson", data: empty() });
      map.addLayer({ id: "pf", type: "fill", source: "p", paint: { "fill-color": ["get", "color"], "fill-opacity": 0.22 } });
      map.addLayer({ id: "pl", type: "line", source: "p", paint: { "line-color": ["get", "color"], "line-width": 1.5 } });
      map.addSource("pt", { type: "geojson", data: empty() });
      map.addLayer({
        id: "ptc", type: "circle", source: "pt", paint: {
          // Small when zoomed out to the whole territory, larger up close, so the
          // statewide view reads like the full statewide map, not a wall of blobs.
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 5, 3, 12, 7],
          "circle-color": ["get", "color"], "circle-opacity": 0.85,
          "circle-stroke-width": 1.5, "circle-stroke-color": "#fff"
        }
      });
      map.addSource("me", { type: "geojson", data: empty() });
      map.addLayer({ id: "mec", type: "circle", source: "me", paint: { "circle-radius": 6, "circle-color": C_ME, "circle-stroke-width": 3, "circle-stroke-color": "#fff" } });
      map.on("click", "ptc", function (e) { var o = findOutage(e.features[0].properties.id); if (o) openDetail(o); });
      map.on("mouseenter", "ptc", function () { map.getCanvas().style.cursor = "pointer"; });
      map.on("mouseleave", "ptc", function () { map.getCanvas().style.cursor = ""; });
      state.mapReady = true;
      drawOutages();
      loadStatewide();
      if (state.center) flyToSearch();
    });
  }

  // Every current outage across PG&E territory, so the mini map shows the same
  // set as the full statewide map when you zoom out. A search flies in on top.
  function loadStatewide() {
    fetch(API + "/v1/statewide?include_geometry=true")
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) { state.statewide = list || []; drawOutages(); })
      .catch(function () {});
  }
  function findOutage(id) {
    var all = state.statewide.concat(state.outages);
    for (var i = 0; i < all.length; i++) { if (all[i].id === id) return all[i]; }
    return null;
  }
  // Draw the statewide outages plus the you-are-here marker. Never moves the
  // camera, so the periodic refresh can't yank the map while you are panning it.
  function drawOutages() {
    var map = state.map;
    if (!map || !state.mapReady) return;
    var polys = [], pts = [];
    state.statewide.forEach(function (o) {
      var color = o.is_psps ? C_PSPS : C_OUT;
      if (o.geometry) polys.push({ type: "Feature", properties: { color: color }, geometry: o.geometry });
      if (o.lat != null && o.lon != null) pts.push({ type: "Feature", properties: { color: color, id: o.id }, geometry: { type: "Point", coordinates: [o.lon, o.lat] } });
    });
    map.getSource("p").setData({ type: "FeatureCollection", features: polys });
    map.getSource("pt").setData({ type: "FeatureCollection", features: pts });
    map.getSource("me").setData(state.center ? { type: "Feature", geometry: { type: "Point", coordinates: [state.center.lon, state.center.lat] } } : empty());
  }
  // Fly to a searched area. Only on an explicit search, never on auto-refresh.
  function flyToSearch() {
    if (state.map && state.mapReady && state.center) {
      state.map.flyTo({ center: [state.center.lon, state.center.lat], zoom: 11, duration: 700 });
    }
  }

  // ---------- Detail modal + AI explanation ----------
  var lastFocus = null;
  function openDetail(o) {
    lastFocus = document.activeElement;
    var m = $("#modal");
    m.textContent = "";
    var close = el("button", "x", "×");
    close.setAttribute("aria-label", "Close");
    close.addEventListener("click", closeModal);
    m.appendChild(close);
    var h = el("h2", null, label(o));
    h.id = "modal-title";
    m.appendChild(h);
    m.appendChild(el("p", "eta", fmtEta(o.eta)));
    [["Cause", o.cause], ["Crew status", o.crew_status], ["Customers affected", o.est_customers], ["City", o.city]].forEach(function (kv) {
      if (kv[1] == null || kv[1] === "") return;
      var row = el("div", "kv");
      row.appendChild(el("span", "k", kv[0]));
      row.appendChild(el("span", "v", String(kv[1])));
      m.appendChild(row);
    });
    m.appendChild(el("div", "explain-h", "What is going on?"));
    var ex = el("div", "explain loading", "Writing a plain-language summary...");
    m.appendChild(ex);
    $("#modal-back").classList.add("open");
    close.focus();
    fetch(API + "/v1/outages/" + encodeURIComponent(o.id) + "/explain")
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) { ex.classList.remove("loading"); ex.textContent = (d && d.explanation) || "No explanation available for this outage."; })
      .catch(function () { ex.classList.remove("loading"); ex.textContent = "Could not load the explanation."; });
  }
  function closeModal() {
    $("#modal-back").classList.remove("open");
    if (lastFocus && lastFocus.focus) { lastFocus.focus(); lastFocus = null; }
  }
  $("#modal-back").addEventListener("click", function (e) { if (e.target === this) closeModal(); });
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") closeModal(); });

  // ---------- Auto refresh ----------
  function scheduleRefresh() {
    clearTimeout(state.timer);
    state.timer = setTimeout(function () {
      loadStatewide();
      if (state.area && state.area.zip) checkZip(state.area.zip, true);
      else if (state.area && state.area.lat != null) checkPoint(state.area.lat, state.area.lon, state.area.zip, true);
    }, 5 * 60 * 1000);
  }

  // ---------- Web push ----------
  var pushBtn = $("#push-btn"), pushStatus = $("#push-status");
  pushBtn.addEventListener("click", function () {
    if (!window.OW_PUSH_ENABLED) { pushStatus.textContent = "Browser alerts are launching soon. In the meantime, use the Android app for push."; return; }
    if (!state.area) { pushStatus.textContent = "Check an area first, then turn on alerts for it."; return; }
    if (!("Notification" in window) || !("serviceWorker" in navigator)) { pushStatus.textContent = "This browser does not support web notifications."; return; }
    pushBtn.disabled = true; pushStatus.textContent = "Requesting permission...";
    Notification.requestPermission().then(function (perm) {
      if (perm !== "granted") { pushStatus.textContent = "Notifications were not allowed. You can enable them in your browser settings."; pushBtn.disabled = false; return; }
      enablePush();
    });
  });
  function loadScript(src) { return new Promise(function (res, rej) { var s = document.createElement("script"); s.src = src; s.onload = res; s.onerror = rej; document.head.appendChild(s); }); }
  function enablePush() {
    var V = "https://www.gstatic.com/firebasejs/10.12.2/";
    Promise.all([loadScript(V + "firebase-app-compat.js"), loadScript(V + "firebase-messaging-compat.js")])
      .then(function () { return firebase.messaging.isSupported(); })
      .then(function (supported) {
        // Safari private mode and a few browsers report unsupported here.
        if (!supported) throw { kind: "unsupported" };
        if (!firebase.apps.length) firebase.initializeApp(window.OW_FIREBASE);
        // Register the messaging worker on its OWN scope so it never clobbers the
        // PWA service worker (both would otherwise claim "/", replacing each other
        // on every load and dropping background notifications).
        return navigator.serviceWorker.register(
          "/firebase-messaging-sw.js?c=" + encodeURIComponent(JSON.stringify(window.OW_FIREBASE)),
          { scope: "/firebase-cloud-messaging-push-scope" }
        );
      })
      .then(function (reg) {
        // PushManager.subscribe needs an ACTIVE worker. A brand-new registration
        // on its own scope is still installing, so wait for it to activate before
        // getToken (skipping this is the "no active Service Worker" error).
        if (reg.active) return reg;
        return new Promise(function (resolve, reject) {
          var w = reg.installing || reg.waiting;
          if (!w) { reject({ kind: "noworker" }); return; }
          w.addEventListener("statechange", function () {
            if (w.state === "activated") resolve(reg);
            else if (w.state === "redundant") reject({ kind: "noworker" });
          });
        });
      })
      .then(function (reg) {
        return firebase.messaging().getToken({ vapidKey: window.OW_VAPID_KEY, serviceWorkerRegistration: reg });
      })
      .then(function (token) {
        if (!token) throw { kind: "notoken" };
        var body = { token: token, platform: "web" };
        if (state.area.precise && state.area.lat != null) {
          // Exact address or current location: only alert when an outage covers it.
          body.lat = state.area.lat; body.lon = state.area.lon; body.precise = true;
        } else if (state.area.zip) {
          body.zip_code = state.area.zip;  // ZIP area: alert on anything nearby
        } else if (state.area.lat != null) {
          body.lat = state.area.lat; body.lon = state.area.lon;
        }
        return fetch(API + "/v1/subscriptions", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      })
      .then(function (r) {
        if (!r.ok) throw { kind: "server", status: r.status };
        pushStatus.textContent = "Alerts are on for this area. You can close this tab.";
        pushBtn.textContent = "Alerts on";
      })
      .catch(function (e) {
        // Log the real reason (for debugging) and tell the user something useful.
        try { console.error("web push failed:", e); } catch (x) {}
        pushBtn.disabled = false;
        var code = (e && (e.code || e.kind || "")) + "";
        if (/unsupported/i.test(code)) {
          pushStatus.textContent = "This browser can't do web alerts. Try Chrome or Edge, or use the Android app.";
        } else if (e && e.kind === "server") {
          pushStatus.textContent = "The server couldn't save the alert just now. Please try again in a moment.";
        } else {
          pushStatus.textContent = "Couldn't turn on alerts. Private/incognito windows and some ad or privacy blockers block web push — open this page in a normal window and try again.";
        }
      });
  }

  // ---------- Deep links + restore ----------
  function boot() {
    initMap();
    var p = new URLSearchParams(location.search);
    if (/^\d{5}$/.test(p.get("zip") || "")) { $("#zip-input").value = p.get("zip"); checkZip(p.get("zip")); return; }
    if (p.get("lat") && p.get("lon")) { state.area = { lat: +p.get("lat"), lon: +p.get("lon") }; checkPoint(+p.get("lat"), +p.get("lon"), null); return; }
    var last = load("ow_last");
    if (last) {
      try {
        var o = JSON.parse(last);
        if (o.zip) { $("#zip-input").value = o.zip; checkZip(o.zip); return; }
        if (o.lat != null && o.lon != null) { state.area = { lat: o.lat, lon: o.lon, name: o.name }; checkPoint(o.lat, o.lon, null); return; }
      } catch (e) {}
    }
  }
  // register the app service worker (PWA shell) regardless of push
  if ("serviceWorker" in navigator) { navigator.serviceWorker.register("/sw.js").catch(function () {}); }
  boot();
})();
