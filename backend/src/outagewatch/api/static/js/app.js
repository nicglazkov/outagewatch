/* OutageWatch web app. Talks to the same-origin public API. No framework.
   Direction "V2": a calm forecast-led answer, a restoration timeline, and an
   honest one-line schematic built from the real outages (no invented feeder or
   node ids, since PG&E's public feed gives outages, not circuit topology). */
(function () {
  "use strict";
  var API = "";
  var $ = function (s) { return document.querySelector(s); };
  var state = { area: null, outages: [], statewide: [], center: null, territory: null, map: null, mapReady: false, timer: null, lastFetch: null };

  var C_OUT = "#c0562a", C_PSPS = "#e0913a", C_ME = "#2b5fd0";
  var HOME = { center: [-121.7, 38.85], zoom: 5.6 };
  var F = "system-ui,-apple-system,'Segoe UI',Roboto,sans-serif";
  var MONO = "ui-monospace,'Cascadia Mono',Consolas,'SFMono-Regular',Menlo,monospace";

  // ---------- helpers ----------
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function empty() { return { type: "FeatureCollection", features: [] }; }
  function esc(s) { return String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }
  function fmtEta(iso) {
    if (!iso) return "No restoration estimate yet";
    var d = new Date(iso);
    if (isNaN(d)) return "No restoration estimate yet";
    var s = d.toLocaleString("en-US", { timeZone: "America/Los_Angeles", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
    return (d < new Date() ? "Estimate passed: " : "Estimated restoration: ") + s;
  }
  function fmtClock(v) {
    if (v == null) return null;
    var d = new Date(v);
    if (isNaN(d)) return null;
    return d.toLocaleString("en-US", { timeZone: "America/Los_Angeles", hour: "numeric", minute: "2-digit" });
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

  // Does an outage actually reach a point? A polygon must contain it; a
  // coordinate-only outage counts within ~250m. Mirrors the app's precise rule.
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
  function distMi(o) {
    if (!state.center || o.lat == null || o.lon == null) return null;
    return haversineKm(state.center.lat, state.center.lon, o.lat, o.lon) * 0.621371;
  }
  function distLabel(o, isCov) {
    if (isCov) return "0.0 mi";
    var mi = distMi(o);
    return mi == null ? null : (mi < 0.1 ? "under 0.1 mi" : mi.toFixed(1) + " mi");
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
    fetch(API + "/v1/zips/" + zip).then(function (r) { return r.ok ? r.json() : null; }).then(function (info) {
      state.center = info ? { lat: info.lat, lon: info.lon } : null;
      state.territory = info;
      drawOutages();
      if (!isAuto) flyToSearch();
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
      state.area = { lat: pos.coords.latitude, lon: pos.coords.longitude, name: "your location", precise: true };
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

  // ---------- status block ----------
  function setCond(parts) {
    var h = $("#cond-h"); h.textContent = "";
    parts.forEach(function (p) { h.appendChild(el("span", p.cls || null, p.t)); });
  }
  function areaName() {
    return (state.area && (state.area.name || (state.area.zip ? "ZIP " + state.area.zip : ""))) || "your area";
  }
  function setLoading() {
    $("#asof").textContent = "";
    setCond([{ t: "Checking..." }]);
    $("#place").textContent = "";
    $("#detail").textContent = "";
    $("#detail-block").textContent = "";
    $("#nearby-block").textContent = "";
  }
  function showMsg(m) {
    $("#asof").textContent = "";
    setCond([]);
    $("#place").textContent = "";
    $("#detail").textContent = "";
    $("#detail-block").textContent = "";
    var nb = $("#nearby-block"); nb.textContent = "";
    nb.appendChild(el("div", "notice", m));
    state.outages = [];
    drawOutages();
  }
  function territoryNotice() {
    if (state.territory && state.territory.pge === false) {
      return el("div", "notice", "This ZIP is served by " + (state.territory.served_by || "another utility") + ", not PG&E. Outage data may be limited.");
    }
    return null;
  }

  function coverDetail(cov) {
    var homes = cov.est_customers;
    var s = (cov.cause || "An outage") + " is affecting " +
      (homes ? (homes + " home" + (homes === 1 ? "" : "s")) : "your service") + " at this address.";
    var clk = fmtClock(cov.eta);
    s += clk ? (" Crews estimate power back at " + clk + "." ) : " No restoration estimate yet.";
    return s;
  }

  function render(list) {
    state.outages = list || [];
    state.lastFetch = Date.now();
    var name = areaName();
    var out = state.outages.length;
    var precise = !!(state.area && state.area.precise && state.center);
    var covering = precise ? state.outages.filter(function (o) { return impacts(o, state.center.lat, state.center.lon); }) : [];
    var cov = covering[0] || null;

    if (precise && cov) {
      setCond([{ t: "Power is " }, { t: "out", cls: "em-out" }]);
      $("#place").textContent = "at your address, " + name;
      $("#detail").textContent = coverDetail(cov);
    } else if (precise) {
      setCond([{ t: "Power is likely " }, { t: "on", cls: "em-on" }]);
      $("#place").textContent = "at " + name;
      $("#detail").textContent = out
        ? (out + " outage" + (out === 1 ? "" : "s") + " reported nearby, but none reaching this address.")
        : "No outages near this address right now.";
    } else if (out) {
      setCond([{ t: "Power is likely " }, { t: "on", cls: "em-on" }]);
      $("#place").textContent = "near " + name;
      $("#detail").textContent = out + " outage" + (out === 1 ? "" : "s") + " reported nearby. Enter your street address to see if yours is affected.";
    } else {
      setCond([{ t: "No outages", cls: "em-on" }]);
      $("#place").textContent = "near " + name;
      $("#detail").textContent = "PG&E reports nothing in this area right now.";
    }
    $("#asof").textContent = "Updated " + (fmtClock(Date.now()) || "just now");

    var db = $("#detail-block"); db.textContent = "";
    if (precise && cov && cov.eta) {
      var tl = buildTimeline(cov.eta);
      if (tl) db.appendChild(tl);
    }
    if (out) { db.appendChild(buildSchematic(cov)); }
    if (precise && cov) { db.appendChild(buildFacts(cov)); }

    var nb = $("#nearby-block"); nb.textContent = "";
    var note = territoryNotice(); if (note) nb.appendChild(note);
    if (out) { nb.appendChild(buildNearby(precise, cov)); }

    drawOutages();
    scheduleRefresh();
  }

  // ---------- restoration timeline (signature) ----------
  function buildTimeline(etaIso) {
    var nowMs = Date.now(), etaMs = Date.parse(etaIso);
    if (!etaMs || isNaN(etaMs)) return null;
    var clk = fmtClock(etaIso);
    var passed = etaMs <= nowMs;
    var outSpan = Math.max(etaMs - nowMs, 0);
    var tail = Math.max(outSpan * 0.26, 45 * 60000);
    var outPct = passed ? 100 : Math.round((outSpan / (outSpan + tail)) * 1000) / 10;

    var fig = el("figure", "forecast");
    var cap = el("figcaption", "fc-cap");
    cap.appendChild(el("h2", null, "Restoration timeline"));
    cap.appendChild(el("p", null, passed
      ? ("The estimate of " + clk + " has passed. Crews may still be working.")
      : ("Estimated back at " + clk + ".")));
    fig.appendChild(cap);

    var strip = el("div", "fc-strip");
    var callout = el("div", "fc-callout"); callout.style.left = outPct + "%";
    callout.appendChild(el("span", "fc-callout-time", clk));
    callout.appendChild(el("span", "fc-callout-note", passed ? "estimate passed" : "power back"));
    strip.appendChild(callout);

    var bar = el("div", "fc-bar"); bar.setAttribute("aria-hidden", "true");
    var so = el("div", "fc-seg fc-out"); so.style.width = outPct + "%"; bar.appendChild(so);
    if (!passed) { var sb = el("div", "fc-seg fc-back"); sb.style.width = (100 - outPct) + "%"; bar.appendChild(sb); }
    var stop = el("div", "fc-stop"); stop.style.left = outPct + "%"; bar.appendChild(stop);
    strip.appendChild(bar);

    var hours = el("div", "fc-hours"); hours.setAttribute("aria-hidden", "true");
    var hn = el("span", "h-now", "Now"); hn.style.left = "0"; hours.appendChild(hn);
    var hb = el("span", "h-back", clk); hb.style.left = outPct + "%"; hours.appendChild(hb);
    if (!passed) { var he = el("span", "h-end", "later"); he.style.left = "100%"; hours.appendChild(he); }
    strip.appendChild(hours);
    fig.appendChild(strip);

    var lg = el("div", "fc-legend"); lg.setAttribute("aria-hidden", "true");
    lg.appendChild(el("span", "lg lg-out", "Out"));
    lg.appendChild(el("span", "lg lg-back", "Power back"));
    fig.appendChild(lg);

    fig.appendChild(el("p", "visually-hidden",
      "Power is out now and is estimated to return at " + clk + ". The timeline is orange while out and green once power is back."));
    return fig;
  }

  // ---------- annotated one-line schematic, built from the real outages ----------
  function shownOutages(cov) {
    return state.outages.slice().sort(function (a, b) {
      var ac = a === cov, bc = b === cov;
      if (ac !== bc) return ac ? -1 : 1;
      var ad = distMi(a), bd = distMi(b);
      ad = ad == null ? 1e9 : ad; bd = bd == null ? 1e9 : bd;
      return ad - bd;
    });
  }
  function svLine(x1, y1, x2, y2, stroke, w) {
    return '<line x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '" stroke="' + stroke + '" stroke-width="' + w + '" stroke-linecap="round"/>';
  }
  function svDot(cx, cy, r, fill) { return '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="' + fill + '"/>'; }
  function svRing(cx, cy, r, stroke, w, op) { return '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="none" stroke="' + stroke + '" stroke-width="' + w + '"' + (op != null ? ' stroke-opacity="' + op + '"' : '') + '/>'; }
  function svText(x, y, fill, size, family, text, anchor, weight) {
    return '<text x="' + x + '" y="' + y + '"' + (anchor ? ' text-anchor="' + anchor + '"' : '') +
      ' font-family="' + family + '" font-size="' + size + '"' + (weight ? ' font-weight="' + weight + '"' : '') +
      ' fill="' + fill + '">' + esc(text) + '</text>';
  }
  function svTag(x, y, w, txt) {
    return '<rect x="' + x + '" y="' + y + '" width="' + w + '" height="18" rx="5" fill="rgba(192,86,42,0.10)"/>' +
      svText(x + w / 2, y + 13, '#c0562a', 11, F, txt, 'middle', 600);
  }
  function schSvg(rows, cov) {
    var busX = 100, gx0 = 103, breakX = 250, deEnd = 350, nodeX = 352, cardX = 362, cardW = 262;
    var rowY = function (i) { return 120 + i * 130; };
    var n = rows.length;
    var H = rowY(n - 1) + 80;
    var s = '<svg viewBox="0 0 640 ' + H + '" role="img" aria-label="One-line view of the outages near you" preserveAspectRatio="xMidYMin meet">';
    s += svLine(busX, rowY(0), busX, rowY(n - 1), '#20242a', 8);
    s += svText(busX, rowY(0) - 30, '#20242a', 14, F, 'the grid near you', 'middle', 700);
    rows.forEach(function (o, i) {
      var y = rowY(i), isCov = (o === cov), psps = !!o.is_psps;
      s += svDot(busX, y, 3.5, '#20242a');
      s += svLine(gx0, y, breakX, y, '#2f7d5b', 5);
      if (psps) {
        s += svLine(breakX, y, breakX + 14, y - 16, '#5c6069', 3.2);
        s += svDot(breakX, y, 2.6, '#5c6069') + svDot(breakX + 20, y, 2.6, '#5c6069');
        s += svText(breakX + 9, y + 26, '#5c6069', 11.5, MONO, 'planned open', 'middle');
        s += svLine(breakX + 20, y, deEnd, y, '#c0562a', 5);
      } else {
        s += svLine(breakX, y - 14, breakX, y + 14, '#c0562a', 3.2);
        s += svLine(breakX + 17, y - 14, breakX + 17, y + 14, '#c0562a', 3.2);
        s += svText(breakX + 8, y + 30, '#c0562a', 11.5, MONO, 'open point', 'middle');
        s += svLine(breakX + 17, y, deEnd, y, '#c0562a', 5);
      }
      if (isCov) { s += svRing(nodeX, y, 10, '#20242a', 1.5, 0.55) + svDot(nodeX, y, 6, '#c0562a'); }
      else if (psps) { s += svRing(nodeX, y, 6, '#c0562a', 3, null); }
      else { s += svDot(nodeX, y, 6, '#c0562a'); }
      var cy = y - 54;
      s += '<rect x="' + cardX + '" y="' + cy + '" width="' + cardW + '" height="108" rx="11" fill="#ffffff" stroke="#e3e5e9" stroke-width="1"/>';
      var title = isCov ? (o.city || "Your address") : (o.city || (psps ? "Planned shutoff" : "Power outage"));
      s += svText(378, y - 30, '#20242a', 16, F, title, 'start', 700);
      if (isCov) { s += svTag(452, y - 42, 94, 'your address'); }
      else if (psps) { s += svTag(452, y - 42, 62, 'planned'); }
      s += svText(378, y - 10, '#20242a', 13, F, o.cause || (psps ? "Planned safety shutoff" : "Cause not stated"), 'start');
      var homes = o.est_customers ? (o.est_customers + " home" + (o.est_customers === 1 ? "" : "s")) : "homes not stated";
      var dl = distLabel(o, isCov);
      s += svText(378, y + 9, '#5c6069', 12, MONO, homes + (dl ? (", " + dl) : ""), 'start');
      var clk = fmtClock(o.eta);
      s += '<text x="378" y="' + (y + 28) + '" font-family="' + MONO + '" font-size="12" fill="#5c6069">' +
        (clk ? ('de-energized, back <tspan fill="#276b4e" font-weight="700">' + esc(clk) + '</tspan>') : 'de-energized, no estimate') + '</text>';
    });
    s += '</svg>';
    return s;
  }
  function buildSchematic(cov) {
    var shown = shownOutages(cov);
    var capped = shown.slice(0, 4);
    var more = shown.length - capped.length;
    var sec = el("section", "why");
    var h = el("h2", null, "Why it is out"); sec.appendChild(h);
    sec.appendChild(el("p", "why-lead", cov
      ? "The grid near you is live in green up to an open point on your street, then de-energized past it."
      : "The outages near you, drawn on one line. Your address is not on an out segment right now."));
    var scroll = el("div", "sch-scroll");
    scroll.innerHTML = schSvg(capped, cov);
    sec.appendChild(scroll);
    sec.appendChild(el("p", "sch-hint", "Scroll the diagram sideways on a small screen. Every value here is repeated in the list below."));
    var lg = el("div", "sc-legend"); lg.setAttribute("aria-hidden", "true");
    lg.innerHTML =
      '<span class="sc sc-line sc-live">Energized</span>' +
      '<span class="sc sc-line sc-out">De-energized</span>' +
      '<span class="sc sc-icon"><svg viewBox="0 0 20 14"><line x1="6" y1="1" x2="6" y2="13" stroke="#c0562a" stroke-width="2.4" stroke-linecap="round"/><line x1="14" y1="1" x2="14" y2="13" stroke="#c0562a" stroke-width="2.4" stroke-linecap="round"/></svg>Open point</span>' +
      '<span class="sc sc-icon"><svg viewBox="0 0 20 14"><line x1="4" y1="12" x2="13" y2="2" stroke="#5c6069" stroke-width="2.4" stroke-linecap="round"/><circle cx="4" cy="12" r="1.8" fill="#5c6069"/><circle cx="16" cy="12" r="1.8" fill="#5c6069"/></svg>Planned shutoff</span>' +
      (cov ? '<span class="sc sc-icon"><svg viewBox="0 0 20 14"><circle cx="10" cy="7" r="5.5" fill="none" stroke="#20242a" stroke-width="1.4" stroke-opacity="0.55"/><circle cx="10" cy="7" r="3" fill="#c0562a"/></svg>Your address</span>' : '');
    sec.appendChild(lg);
    var noteTxt = cov
      ? ("The break upstream of your street is what crews are working to close" + (fmtClock(cov.eta) ? (", estimated by " + fmtClock(cov.eta) + ".") : ".") + " Distances are straight-line from your address.")
      : "These are the outages near the area you checked, with straight-line distances.";
    if (more > 0) noteTxt += " " + more + " more " + (more === 1 ? "outage is" : "outages are") + " listed below.";
    sec.appendChild(el("p", "why-note", noteTxt));
    return sec;
  }

  // ---------- service facts (covered address only) ----------
  function buildFacts(cov) {
    var sec = el("section", "facts");
    sec.appendChild(el("h2", null, "This service, in detail"));
    var dl = el("dl", "facts-grid");
    function row(k, v, cls) {
      if (v == null || v === "") return;
      var d = el("div");
      d.appendChild(el("dt", null, k));
      d.appendChild(el("dd", cls || null, String(v)));
      dl.appendChild(d);
    }
    row("Status", "Out, de-energized", "v-out");
    row("Cause", cov.cause || "Not stated");
    row("Homes affected", cov.est_customers != null ? cov.est_customers : null);
    row("Estimated restoration", fmtClock(cov.eta) || "None yet", "v-back");
    row("Distance to outage", "0.0 mi, it reaches you");
    row("City", cov.city);
    row("Crew status", cov.crew_status);
    row("Last updated", fmtClock(Date.now()));
    sec.appendChild(dl);
    return sec;
  }

  // ---------- nearby outages ----------
  function buildNearby(precise, cov) {
    var sec = el("section", "nearby");
    var head = el("div", "sec-head");
    head.appendChild(el("h2", null, "Nearby outages"));
    head.appendChild(el("p", null, (precise && cov)
      ? "Every outage near your address, nearest first."
      : precise ? "Outages near you. None reach this address."
        : "Outages reported near this area, nearest first."));
    sec.appendChild(head);
    var ul = el("ul", "nb-list");
    shownOutages(cov).slice(0, 20).forEach(function (o) {
      var isCov = (o === cov), psps = !!o.is_psps;
      var li = el("button", "nb-row"); li.type = "button";
      li.appendChild(el("span", "nb-dot " + (psps ? "dot-plan" : "dot-out")));
      var main = el("div", "nb-main");
      var place = el("p", "nb-place");
      place.appendChild(document.createTextNode(o.city || (psps ? "Planned shutoff" : "Power outage")));
      if (isCov) place.appendChild(el("span", "nb-tag", "your address"));
      main.appendChild(place);
      main.appendChild(el("p", "nb-cause", (psps ? "Planned safety shutoff. " : "Power out. ") +
        (o.cause ? o.cause + ", " : "") + (o.est_customers ? o.est_customers + " home" + (o.est_customers === 1 ? "" : "s") + "." : "")));
      var dl = distLabel(o, isCov);
      main.appendChild(el("p", "nb-meta", (dl ? dl + ", " : "") + "de-energized" + (psps ? " (planned)" : "")));
      li.appendChild(main);
      var back = el("div", "nb-back");
      back.appendChild(el("span", "nb-back-time", fmtClock(o.eta) || "No estimate"));
      back.appendChild(el("span", "nb-back-lbl", "back"));
      li.appendChild(back);
      li.addEventListener("click", function () { openDetail(o); });
      ul.appendChild(li);
    });
    sec.appendChild(ul);
    sec.appendChild(el("p", "freshness", "Updated " + ago(state.lastFetch) + ". Data from PG&E, can lag."));
    return sec;
  }

  // ---------- Map (one persistent instance) ----------
  function initMap() {
    if (typeof maplibregl === "undefined") return;
    var map = new maplibregl.Map({
      container: "map",
      style: "https://tiles.openfreemap.org/styles/positron",
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
    var h = el("h2", null, label(o) + (o.city ? " in " + o.city : ""));
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
    // Load app-compat FIRST, then messaging-compat. In parallel they race:
    // messaging-compat can run before app-compat registers, which throws an
    // internal error and leaves firebase.messaging undefined.
    loadScript(V + "firebase-app-compat.js")
      .then(function () { return loadScript(V + "firebase-messaging-compat.js"); })
      .then(function () {
        return (firebase.messaging && firebase.messaging.isSupported) ? firebase.messaging.isSupported() : true;
      })
      .then(function (supported) {
        if (!supported) throw { kind: "unsupported" };
        if (!firebase.apps.length) firebase.initializeApp(window.OW_FIREBASE);
        // Register the messaging worker on its OWN scope so it never clobbers the
        // PWA service worker (both would otherwise claim "/").
        return navigator.serviceWorker.register(
          "/firebase-messaging-sw.js?c=" + encodeURIComponent(JSON.stringify(window.OW_FIREBASE)),
          { scope: "/firebase-cloud-messaging-push-scope" }
        );
      })
      .then(function (reg) {
        // PushManager.subscribe needs an ACTIVE worker; a brand-new registration
        // on its own scope is still installing, so wait for activation.
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
          body.lat = state.area.lat; body.lon = state.area.lon; body.precise = true;
        } else if (state.area.zip) {
          body.zip_code = state.area.zip;
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
        try { console.error("web push failed:", e); } catch (x) {}
        pushBtn.disabled = false;
        var code = (e && (e.code || e.kind || "")) + "";
        if (/unsupported/i.test(code)) {
          pushStatus.textContent = "This browser can't do web alerts. Try Chrome or Edge, or use the Android app.";
        } else if (e && e.kind === "server") {
          pushStatus.textContent = "The server couldn't save the alert just now. Please try again in a moment.";
        } else {
          pushStatus.textContent = "Couldn't turn on alerts. Private/incognito windows and some ad or privacy blockers block web push. Open this page in a normal window and try again.";
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
  if ("serviceWorker" in navigator) { navigator.serviceWorker.register("/sw.js").catch(function () {}); }
  boot();
})();
