/* OutageWatch web app. Talks to the same-origin public API. No framework. */
(function () {
  "use strict";
  var API = "";
  var $ = function (s) { return document.querySelector(s); };
  var state = { area: null, outages: [], center: null, map: null, timer: null, lastFetch: null };

  // ---------- helpers ----------
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
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

  // ---------- mode tabs ----------
  document.querySelectorAll(".tab").forEach(function (t) {
    t.addEventListener("click", function () {
      document.querySelectorAll(".tab").forEach(function (x) { x.classList.remove("active"); });
      t.classList.add("active");
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

  function checkZip(zip) {
    setLoading();
    save("ow_last", JSON.stringify({ zip: zip }));
    state.area = { zip: zip };
    // territory note + outages in parallel
    fetch(API + "/v1/zips/" + zip).then(function (r) { return r.ok ? r.json() : null; }).then(function (info) {
      state.center = info ? { lat: info.lat, lon: info.lon } : null;
      state.territory = info;
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

  function checkPoint(lat, lon, zip) {
    setLoading();
    state.center = { lat: lat, lon: lon };
    save("ow_last", JSON.stringify({ lat: lat, lon: lon, name: state.area && state.area.name }));
    fetch(API + "/v1/outages?lat=" + lat + "&lon=" + lon + "&radius_km=8&include_geometry=true")
      .then(function (r) { if (!r.ok) throw 0; return r.json(); })
      .then(function (list) { render(list); })
      .catch(function () { showMsg("Could not reach the outage feed. Try again shortly."); });
  }

  // ---------- Render ----------
  function setLoading() { $("#result").innerHTML = '<p class="freshness"><span class="spin"></span> Checking...</p>'; }
  function showMsg(m) { $("#result").innerHTML = ""; $("#result").appendChild(el("div", "notice", m)); clearMap(); }

  function render(list) {
    state.outages = list || [];
    state.lastFetch = Date.now();
    var r = $("#result");
    r.textContent = "";

    var name = state.area && (state.area.name || (state.area.zip ? "ZIP " + state.area.zip : ""));
    if (state.territory && state.territory.pge === false) {
      r.appendChild(el("div", "notice", "This ZIP is served by " + (state.territory.served_by || "another utility") + ", not PG&E. Outage data may be limited."));
    }

    var sum = el("div", "summary");
    var out = state.outages.length;
    var pill = el("div", "pill " + (out ? "out" : "clear"), out ? String(out) : "OK");
    sum.appendChild(pill);
    sum.appendChild(el("span", null, out ? (out + (out === 1 ? " outage" : " outages") + (name ? " near " + name : "")) : ("No outages reported" + (name ? " near " + name : ""))));
    r.appendChild(sum);

    state.outages.slice(0, 20).forEach(function (o) {
      var card = el("div", "card");
      var body = el("div", "body");
      var h = el("h3", null, label(o) + (o.city ? "  -  " + o.city : ""));
      body.appendChild(h);
      var bits = [];
      if (o.cause) bits.push(o.cause);
      if (o.crew_status) bits.push(o.crew_status);
      if (o.est_customers) bits.push(o.est_customers + (o.est_customers === 1 ? " customer" : " customers"));
      body.appendChild(el("div", "meta", bits.join("  .  ")));
      body.appendChild(el("div", "meta", fmtEta(o.eta)));
      card.appendChild(body);
      card.appendChild(el("div", "chev", ">"));
      card.addEventListener("click", function () { openDetail(o); });
      r.appendChild(card);
    });

    r.appendChild(el("p", "freshness", "Updated " + ago(state.lastFetch) + ". Data from PG&E, can lag."));
    drawMap();
    scheduleRefresh();
  }

  // ---------- Map ----------
  function clearMap() { var m = $("#map"); if (state.map) { state.map.remove(); state.map = null; } m.innerHTML = ""; }
  function drawMap() {
    if (typeof maplibregl === "undefined" || !state.center) return;
    clearMap();
    var dark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
    var map = new maplibregl.Map({
      container: "map",
      style: dark ? "https://tiles.openfreemap.org/styles/dark" : "https://tiles.openfreemap.org/styles/positron",
      center: [state.center.lon, state.center.lat], zoom: 11, attributionControl: false
    });
    map.addControl(new maplibregl.AttributionControl({ compact: true }));
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    state.map = map;
    map.on("load", function () {
      var polys = [], pts = [], bounds = new maplibregl.LngLatBounds();
      bounds.extend([state.center.lon, state.center.lat]);
      state.outages.forEach(function (o) {
        var color = o.is_psps ? "#c62828" : "#e65100";
        if (o.geometry) { polys.push({ type: "Feature", properties: { color: color }, geometry: o.geometry }); walk(o.geometry, function (x, y) { bounds.extend([x, y]); }); }
        if (o.lat != null && o.lon != null) { pts.push({ type: "Feature", properties: { color: color, id: o.id }, geometry: { type: "Point", coordinates: [o.lon, o.lat] } }); bounds.extend([o.lon, o.lat]); }
      });
      map.addSource("p", { type: "geojson", data: { type: "FeatureCollection", features: polys } });
      map.addLayer({ id: "pf", type: "fill", source: "p", paint: { "fill-color": ["get", "color"], "fill-opacity": 0.3 } });
      map.addLayer({ id: "pl", type: "line", source: "p", paint: { "line-color": ["get", "color"], "line-width": 2 } });
      map.addSource("pt", { type: "geojson", data: { type: "FeatureCollection", features: pts } });
      map.addLayer({ id: "ptc", type: "circle", source: "pt", paint: { "circle-radius": 7, "circle-color": ["get", "color"], "circle-opacity": 0.7, "circle-stroke-width": 2, "circle-stroke-color": "#fff" } });
      map.addSource("me", { type: "geojson", data: { type: "Feature", geometry: { type: "Point", coordinates: [state.center.lon, state.center.lat] } } });
      map.addLayer({ id: "mec", type: "circle", source: "me", paint: { "circle-radius": 5, "circle-color": "#1a73e8", "circle-stroke-width": 2, "circle-stroke-color": "#fff" } });
      map.on("click", "ptc", function (e) { var id = e.features[0].properties.id; var o = state.outages.filter(function (x) { return x.id === id; })[0]; if (o) openDetail(o); });
      if (state.outages.length) { try { map.fitBounds(bounds, { padding: 50, maxZoom: 14, duration: 0 }); } catch (e) {} }
    });
  }
  function walk(g, cb) {
    var c = g.coordinates;
    if (g.type === "Polygon") c.forEach(function (r) { r.forEach(function (p) { cb(p[0], p[1]); }); });
    else if (g.type === "MultiPolygon") c.forEach(function (poly) { poly.forEach(function (r) { r.forEach(function (p) { cb(p[0], p[1]); }); }); });
    else if (g.type === "Point") cb(c[0], c[1]);
  }

  // ---------- Detail modal + AI explanation ----------
  function openDetail(o) {
    var m = $("#modal");
    m.textContent = "";
    var close = el("button", "close", "×");
    close.addEventListener("click", closeModal);
    m.appendChild(close);
    m.appendChild(el("h2", null, label(o)));
    m.appendChild(el("p", "eta", fmtEta(o.eta)));
    [["Cause", o.cause], ["Crew status", o.crew_status], ["Customers affected", o.est_customers], ["City", o.city]].forEach(function (kv) {
      if (kv[1] == null || kv[1] === "") return;
      var row = el("div", "kv");
      row.appendChild(el("span", "k", kv[0]));
      row.appendChild(el("span", "v", String(kv[1])));
      m.appendChild(row);
    });
    m.appendChild(el("div", "explain-label", "What is going on?"));
    var ex = el("div", "explain loading", "Writing a plain-language summary...");
    m.appendChild(ex);
    $("#modal-back").classList.add("open");
    fetch(API + "/v1/outages/" + encodeURIComponent(o.id) + "/explain")
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) { ex.classList.remove("loading"); ex.textContent = (d && d.explanation) || "No explanation available for this outage."; })
      .catch(function () { ex.classList.remove("loading"); ex.textContent = "Could not load the explanation."; });
  }
  function closeModal() { $("#modal-back").classList.remove("open"); }
  $("#modal-back").addEventListener("click", function (e) { if (e.target === this) closeModal(); });
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") closeModal(); });

  // ---------- Auto refresh ----------
  function scheduleRefresh() {
    clearTimeout(state.timer);
    state.timer = setTimeout(function () {
      if (state.area && state.area.zip) checkZip(state.area.zip);
      else if (state.area && state.area.lat != null) checkPoint(state.area.lat, state.area.lon, state.area.zip);
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
      .then(function () {
        firebase.initializeApp(window.OW_FIREBASE);
        return navigator.serviceWorker.register("/firebase-messaging-sw.js?c=" + encodeURIComponent(JSON.stringify(window.OW_FIREBASE)));
      })
      .then(function (reg) {
        var messaging = firebase.messaging();
        return messaging.getToken({ vapidKey: window.OW_VAPID_KEY, serviceWorkerRegistration: reg });
      })
      .then(function (token) {
        if (!token) throw 0;
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
        if (r.ok) { pushStatus.textContent = "Alerts are on for this area. You can close this tab."; pushBtn.textContent = "Alerts on"; }
        else { throw 0; }
      })
      .catch(function () { pushStatus.textContent = "Could not turn on alerts. Please try again."; pushBtn.disabled = false; });
  }

  // ---------- Deep links + restore ----------
  function boot() {
    var p = new URLSearchParams(location.search);
    if (/^\d{5}$/.test(p.get("zip") || "")) { $("#zip-input").value = p.get("zip"); checkZip(p.get("zip")); return; }
    if (p.get("lat") && p.get("lon")) { state.area = { lat: +p.get("lat"), lon: +p.get("lon") }; checkPoint(+p.get("lat"), +p.get("lon"), null); return; }
    var last = load("ow_last");
    if (last) { try { var o = JSON.parse(last); if (o.zip) { $("#zip-input").value = o.zip; } } catch (e) {} }
  }
  // register the app service worker (PWA shell) regardless of push
  if ("serviceWorker" in navigator) { navigator.serviceWorker.register("/sw.js").catch(function () {}); }
  boot();
})();
