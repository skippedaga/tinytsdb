// tinytsdb console. Talks only to the console's JSON API under ./api/. No dependencies; every node is built with
// textContent, never HTML from data.
(function () {
  "use strict";

  var TEXT = {
    show: "Show",
    metric: "Metric",
    windows: "Windows",
    calendar: "Calendar",
    sinceStart: "Since it began",
    rolling: "Rolling windows",
    history: "History",
    days: "Days",
    hours: "Hours",
    byLabel: "By label",
    corrections: "Corrections",
    correctionsNote: "Each value is what was added minus what was taken back later: refunds, reversals, status changes.",
    added: "Added",
    retracted: "Retracted",
    net: "Net",
    window: "Window",
    counted: "Counted",
    from: "From",
    value: "Value",
    today: "Today",
    allTime: "All time",
    empty: "Fill in the series and choose Show.",
    notOpened: "Not started yet",
    open: "running",
    closed: "ended",
    inexact: "partly outside the kept history",
    autoOn: "Auto-refresh on",
    autoOff: "Auto-refresh off",
    dark: "Dark",
    light: "Light",
    updated: "Updated",
    reads: "Reads",
    writes: "Writes",
    noLabels: "No values yet.",
    firstAt: "first",
    source_BUCKETS: "buckets",
    source_SNAPSHOT: "stored aggregate",
    source_ADVANCED: "stored aggregate, moved forward",
    source_RECOMPUTED: "recomputed",
    grain_m: "This minute (UTC)",
    grain_h: "This hour (UTC)",
    grain_d: "Today",
    grain_w: "This week",
    grain_mo: "This month",
    grain_y: "This year",
    store: "Store"
  };

  var desc = null;
  var state = { labels: {}, metric: "", windows: [], grain: "d", label: "", auto: true };
  var timer = null;
  var $ = function (id) { return document.getElementById(id); };

  function t(key) { return (desc && desc.text && desc.text[key]) || TEXT[key] || key; }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = String(text); }
    return e;
  }

  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } }

  // ---- state in the URL hash ---------------------------------------------------------------------------------------

  function readHash() {
    var params = new URLSearchParams(location.hash.slice(1));
    state.labels = {};
    (desc.seriesLabels || []).forEach(function (l) {
      if (params.get(l)) { state.labels[l] = params.get(l); }
    });
    state.metric = params.get("metric") || desc.metrics[0].name;
    var w = params.get("windows");
    state.windows = w ? w.split(",") : desc.windows.map(function (x) { return x.key; });
    state.grain = params.get("grain") === "h" ? "h" : "d";
    state.label = params.get("label") || (desc.browseLabels[0] || "");
    state.auto = params.get("auto") !== "off";
  }

  function writeHash() {
    var params = new URLSearchParams();
    Object.keys(state.labels).forEach(function (k) { params.set(k, state.labels[k]); });
    params.set("metric", state.metric);
    params.set("windows", state.windows.join(","));
    params.set("grain", state.grain);
    if (state.label) { params.set("label", state.label); }
    if (!state.auto) { params.set("auto", "off"); }
    history.replaceState(null, "", "#" + params.toString());
  }

  function seriesQuery() {
    var params = new URLSearchParams();
    Object.keys(state.labels).forEach(function (k) { params.set(k, state.labels[k]); });
    return params;
  }

  // ---- fetching ----------------------------------------------------------------------------------------------------

  function api(path, params) {
    var url = "api/" + path + (params ? "?" + params.toString() : "");
    return fetch(url, { headers: { "Accept": "application/json" }, credentials: "same-origin" })
      .then(function (r) {
        return r.json().then(function (body) {
          if (!r.ok) { throw new Error(body.error || ("HTTP " + r.status)); }
          return body;
        });
      });
  }

  // ---- formatting --------------------------------------------------------------------------------------------------

  var counts = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
  var money = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  var dayTime = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
  var timeOnly = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" });
  var clock = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });

  function metricInfo(name) {
    for (var i = 0; i < desc.metrics.length; i++) { if (desc.metrics[i].name === name) { return desc.metrics[i]; } }
    return { name: name, title: name, unit: "COUNT" };
  }

  function fmt(value, metric) {
    var m = metricInfo(metric);
    if (m.unit === "MINOR_UNITS") {
      var currency = m.currencyLabel && state.labels[m.currencyLabel] ? " " + state.labels[m.currencyLabel] : "";
      return money.format(value / 100) + currency;
    }
    return counts.format(value);
  }

  var dateOnly = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" });
  var dateYear = new Intl.DateTimeFormat(undefined, { year: "numeric", month: "short", day: "numeric" });

  function midnight(d) { return d.getHours() === 0 && d.getMinutes() === 0 && d.getSeconds() === 0; }

  function span(from, to) {
    if (!to) { return ""; }
    var end = new Date(to);
    if (!from) { return dayTime.format(end); }
    var start = new Date(from);
    var last = new Date(end.getTime() - 1);
    if (midnight(start) && midnight(end)) {
      // whole days: dates only, the last day inclusive
      var f = start.getFullYear() === new Date().getFullYear() && last.getFullYear() === start.getFullYear()
        ? dateOnly : dateYear;
      return start.toDateString() === last.toDateString() ? f.format(start) : f.format(start) + " – " + f.format(last);
    }
    var sameDay = start.toDateString() === last.toDateString();
    return dayTime.format(start) + " – " + (sameDay ? timeOnly.format(end) : dayTime.format(end));
  }

  function split(added, retracted) {
    var bar = el("div", "split");
    bar.setAttribute("aria-hidden", "true");
    var total = added + retracted;
    if (total > 0) {
      var a = el("span", "a");
      a.style.flexBasis = (100 * added / total) + "%";
      var r = el("span", "r");
      r.style.flexBasis = (100 * retracted / total) + "%";
      bar.appendChild(a);
      bar.appendChild(r);
    }
    return bar;
  }

  function parts(item, metric) {
    var p = el("p", "parts");
    p.appendChild(el("span", "a", t("added") + " " + fmt(item.added, metric)));
    p.appendChild(document.createTextNode(", "));
    p.appendChild(el("span", "r", t("retracted").toLowerCase() + " " + fmt(item.retracted, metric)));
    return p;
  }

  // ---- panels ------------------------------------------------------------------------------------------------------

  function renderTiles(data) {
    var tiles = $("tiles");
    clear(tiles);
    data.items.forEach(function (item) {
      var tile = el("article", "tile");
      tile.appendChild(el("h3", null, item.title));
      tile.appendChild(el("p", "net", fmt(item.net, item.metric)));
      tile.appendChild(split(item.added, item.retracted));
      tile.appendChild(parts(item, item.metric));
      var when = el("p", "span" + (item.exact ? "" : " inexact"), span(item.from, item.to)
        + (item.exact ? "" : ", " + t("inexact")));
      tile.appendChild(when);
      tiles.appendChild(tile);
    });
    renderCorrections(data);
  }

  function renderCorrections(data) {
    var list = $("corrections-list");
    clear(list);
    data.items.forEach(function (item) {
      var line = el("div", "line");
      line.appendChild(el("span", "what", item.title));
      line.appendChild(el("span", "value", fmt(item.net, item.metric)));
      var p = parts(item, item.metric);
      p.className = "parts";
      line.appendChild(p);
      list.appendChild(line);
    });
  }

  function renderOverview(data) {
    var anchored = $("anchored");
    clear(anchored);
    if (data.anchored.length) {
      anchored.appendChild(el("h3", "sr-only", t("sinceStart")));
      data.anchored.forEach(function (a) {
        var line = el("div", "line");
        var what = el("span", "what", a.title);
        if (a.opened) { what.appendChild(el("span", "badge" + (a.open ? " open" : ""), a.open ? t("open") : t("closed"))); }
        line.appendChild(what);
        line.appendChild(el("span", "value", a.opened ? fmt(a.net, data.metric) : "–"));
        line.appendChild(el("span", "when", a.opened ? span(a.from, a.to) : t("notOpened")));
        anchored.appendChild(line);
      });
    }
    var calendar = $("calendar");
    clear(calendar);
    data.calendar.forEach(function (c) {
      var line = el("div", "line");
      line.appendChild(el("span", "what", t("grain_" + c.grain)));
      line.appendChild(el("span", "value", fmt(c.net, data.metric)));
      line.appendChild(el("span", "when", span(c.from, c.to) + (c.exact ? "" : ", " + t("inexact"))));
      calendar.appendChild(line);
    });
    var all = el("div", "line");
    all.appendChild(el("span", "what", t("allTime")));
    all.appendChild(el("span", "value", fmt(data.all.net, data.metric)));
    all.appendChild(el("span", "when", data.all.firstAt ? t("firstAt") + " " + dayTime.format(new Date(data.all.firstAt)) : ""));
    calendar.appendChild(all);
  }

  function renderRolling(data) {
    var body = $("rolling-table").tBodies[0];
    clear(body);
    data.rows.forEach(function (r) {
      var tr = el("tr");
      var name = el("td", null, r.title);
      name.appendChild(el("span", "source", t("source_" + r.source)));
      tr.appendChild(name);
      tr.appendChild(el("td", "num", fmt(r.net, data.metric)));
      tr.appendChild(el("td", "num added", fmt(r.added, data.metric)));
      tr.appendChild(el("td", "num retracted", fmt(r.retracted, data.metric)));
      tr.appendChild(el("td", "when", span(r.from, r.to)));
      body.appendChild(tr);
    });
  }

  var SVG = "http://www.w3.org/2000/svg";

  function svg(tag, attrs, text) {
    var e = document.createElementNS(SVG, tag);
    Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    if (text !== undefined) { e.textContent = text; }
    return e;
  }

  function renderHistory(data) {
    var box = $("chart");
    clear(box);
    var bars = data.bars;
    var W = Math.max(480, box.clientWidth || 960), H = 220, top = 10, bottom = 24, axis = (H - bottom + top) / 2;
    var max = 1;
    bars.forEach(function (b) { max = Math.max(max, b.added, b.retracted); });
    var half = axis - top;
    var step = W / bars.length;
    var chart = svg("svg", { viewBox: "0 0 " + W + " " + H, role: "img",
      "aria-label": t("history") + ": " + t("added") + " / " + t("retracted") });
    chart.appendChild(svg("line", { "class": "grid", x1: 0, x2: W, y1: top, y2: top }));
    chart.appendChild(svg("line", { "class": "grid", x1: 0, x2: W, y1: 2 * axis - top, y2: 2 * axis - top }));
    var every = Math.ceil(bars.length / 10);
    bars.forEach(function (b, i) {
      var x = i * step + step * 0.15, w = step * 0.7;
      var g = svg("g", {});
      g.appendChild(svg("title", {}, b.id + ": " + t("added") + " " + fmt(b.added, data.metric) + ", "
        + t("retracted").toLowerCase() + " " + fmt(b.retracted, data.metric)));
      var ha = half * b.added / max, hr = half * b.retracted / max;
      if (ha > 0) { g.appendChild(svg("rect", { "class": "bar-a", x: x, y: axis - ha, width: w, height: ha, rx: 1 })); }
      if (hr > 0) { g.appendChild(svg("rect", { "class": "bar-r", x: x, y: axis, width: w, height: hr, rx: 1 })); }
      chart.appendChild(g);
      if (i % every === 0) {
        var label = b.id.slice(b.id.indexOf(":") + 1);
        label = data.grain === "h" ? label.slice(5).replace("T", " ") + ":00" : label.slice(5);
        chart.appendChild(svg("text", { x: x, y: H - 6 }, label));
      }
    });
    chart.appendChild(svg("line", { "class": "axis", x1: 0, x2: W, y1: axis, y2: axis }));
    chart.appendChild(svg("text", { x: 2, y: top + 10 }, fmt(max, data.metric)));
    box.appendChild(chart);
  }

  function renderLabels(data) {
    var body = $("labels-table").tBodies[0];
    clear(body);
    if (!data.rows.length) {
      var tr = el("tr");
      var td = el("td", null, t("noLabels"));
      td.colSpan = 3;
      tr.appendChild(td);
      body.appendChild(tr);
      return;
    }
    data.rows.forEach(function (r) {
      var tr = el("tr");
      tr.appendChild(el("td", null, r.value));
      tr.appendChild(el("td", "num", fmt(r.today, data.metric)));
      tr.appendChild(el("td", "num", fmt(r.all, data.metric)));
      body.appendChild(tr);
    });
  }

  function renderStatus(s) {
    var line = [t("updated") + " " + clock.format(new Date(s.asOf)) + "."];
    var m = s.metrics;
    if (m) {
      var reads = m.reads.range.count ? m.reads.range : m.reads.rolling;
      if (reads && reads.count) {
        line.push(t("reads") + ": p50 " + ms(reads.p50Ms) + ", p99 " + ms(reads.p99Ms) + ".");
      }
      if (m.writes.count) {
        line.push(t("writes") + ": p50 " + ms(m.writes.p50Ms) + ", p99 " + ms(m.writes.p99Ms) + ".");
      }
    }
    $("status").textContent = line.join(" ");
    var store = s.store;
    $("store-line").textContent = t("store") + ": " + store.type
      + (store.capabilities.length ? " (" + store.capabilities.join(", ") + ")" : "")
      + (store.series !== undefined ? ", " + counts.format(store.series) + " series" : "") + ".";
  }

  function ms(v) { return v === null || v === undefined ? "–" : (v < 1 ? v.toFixed(2) : v.toFixed(1)) + " ms"; }

  // ---- loading -----------------------------------------------------------------------------------------------------

  function hasSeries() { return Object.keys(state.labels).length > 0; }

  function load() {
    $("error").hidden = true;
    var shown = hasSeries();
    $("empty").hidden = shown;
    ["explorer", "overview", "rolling", "history", "corrections"].forEach(function (id) { $(id).hidden = !shown; });
    $("labels").hidden = !shown || !desc.browseLabels.length;
    api("status").then(renderStatus).catch(function () { /* the strip just stays as it was */ });
    if (!shown) { return Promise.resolve(); }

    var q = seriesQuery();
    q.set("metric", state.metric);
    var qw = new URLSearchParams(q);
    qw.set("window", state.windows.join(","));
    var qh = new URLSearchParams(q);
    qh.set("grain", state.grain);
    qh.set("count", state.grain === "h" ? "48" : "30");
    var jobs = [
      api("query", qw).then(renderTiles),
      api("overview", q).then(renderOverview),
      api("rolling", q).then(renderRolling),
      api("history", qh).then(renderHistory)
    ];
    if (desc.browseLabels.length && state.label) {
      var ql = new URLSearchParams(q);
      ql.set("label", state.label);
      jobs.push(api("labels", ql).then(renderLabels));
    }
    return Promise.all(jobs).catch(function (e) {
      $("error").textContent = e.message;
      $("error").hidden = false;
    });
  }

  function schedule() {
    if (timer) { clearInterval(timer); timer = null; }
    if (state.auto && desc.refreshSeconds > 0) {
      timer = setInterval(function () {
        if (document.visibilityState === "visible") { load(); }
      }, desc.refreshSeconds * 1000);
    }
    var b = $("refresh-toggle");
    b.textContent = state.auto ? t("autoOn") : t("autoOff");
    b.setAttribute("aria-pressed", state.auto ? "true" : "false");
  }

  // ---- building the form from describe -----------------------------------------------------------------------------

  function build() {
    document.title = desc.title;
    $("title").textContent = desc.title;
    $("apply").textContent = t("show");
    $("metric-label").textContent = t("metric");
    $("explorer-title").textContent = t("windows");
    $("overview-title").textContent = t("calendar");
    $("rolling-title").textContent = t("rolling");
    $("history-title").textContent = t("history");
    $("history-days").textContent = t("days");
    $("history-hours").textContent = t("hours");
    $("labels-title").textContent = t("byLabel");
    $("corrections-title").textContent = t("corrections");
    $("corrections-note").textContent = t("correctionsNote");
    $("legend-added").textContent = t("added");
    $("legend-retracted").textContent = t("retracted");
    $("empty").textContent = t("empty");
    [["rt-window", "window"], ["rt-net", "net"], ["rt-added", "added"], ["rt-retracted", "retracted"],
      ["rt-span", "counted"], ["lt-value", "value"], ["lt-today", "today"],
      ["lt-all", "allTime"]].forEach(function (p) { $(p[0]).textContent = t(p[1]); });

    var fields = $("series-fields");
    clear(fields);
    desc.seriesLabels.forEach(function (l) {
      var label = el("label", "field");
      label.appendChild(el("span", null, t("label_" + l) === "label_" + l ? l : t("label_" + l)));
      var input = el("input");
      input.name = l;
      input.value = state.labels[l] || "";
      input.spellcheck = false;
      label.appendChild(input);
      fields.appendChild(label);
    });

    var metric = $("metric");
    clear(metric);
    desc.metrics.forEach(function (m) {
      var o = el("option", null, m.title);
      o.value = m.name;
      o.selected = m.name === state.metric;
      metric.appendChild(o);
    });

    var picker = $("window-picker");
    Array.prototype.slice.call(picker.querySelectorAll("button")).forEach(function (b) { picker.removeChild(b); });
    desc.windows.concat(desc.anchored).forEach(function (w) {
      var b = el("button", "chip", w.title);
      b.type = "button";
      b.setAttribute("aria-pressed", state.windows.indexOf(w.key) >= 0 ? "true" : "false");
      b.addEventListener("click", function () {
        var i = state.windows.indexOf(w.key);
        if (i >= 0) { state.windows.splice(i, 1); } else { state.windows.push(w.key); }
        var order = desc.windows.concat(desc.anchored).map(function (x) { return x.key; });
        state.windows.sort(function (a, z) { return order.indexOf(a) - order.indexOf(z); });
        b.setAttribute("aria-pressed", i >= 0 ? "false" : "true");
        writeHash();
        load();
      });
      picker.appendChild(b);
    });

    var pick = $("label-pick");
    clear(pick);
    desc.browseLabels.forEach(function (l) {
      var o = el("option", null, l);
      o.value = l;
      o.selected = l === state.label;
      pick.appendChild(o);
    });
  }

  function wire() {
    $("series").addEventListener("submit", function (e) {
      e.preventDefault();
      state.labels = {};
      Array.prototype.forEach.call($("series-fields").querySelectorAll("input"), function (i) {
        if (i.value.trim()) { state.labels[i.name] = i.value.trim(); }
      });
      state.metric = $("metric").value;
      writeHash();
      load();
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-grain]"), function (b) {
      b.addEventListener("click", function () {
        state.grain = b.getAttribute("data-grain");
        Array.prototype.forEach.call(document.querySelectorAll("[data-grain]"), function (o) {
          o.setAttribute("aria-pressed", o === b ? "true" : "false");
        });
        writeHash();
        load();
      });
    });
    $("label-pick").addEventListener("change", function () {
      state.label = $("label-pick").value;
      writeHash();
      load();
    });
    $("refresh-toggle").addEventListener("click", function () {
      state.auto = !state.auto;
      writeHash();
      schedule();
    });
    $("theme-toggle").addEventListener("click", function () {
      var dark = document.documentElement.getAttribute("data-theme") === "dark"
        || (!document.documentElement.getAttribute("data-theme") && matchMedia("(prefers-color-scheme: dark)").matches);
      document.documentElement.setAttribute("data-theme", dark ? "light" : "dark");
      try { localStorage.setItem("tinytsdb-theme", dark ? "light" : "dark"); } catch (ignored) { /* private mode */ }
      themeLabel();
    });
  }

  function themeLabel() {
    var dark = document.documentElement.getAttribute("data-theme") === "dark"
      || (!document.documentElement.getAttribute("data-theme") && matchMedia("(prefers-color-scheme: dark)").matches);
    $("theme-toggle").textContent = dark ? t("light") : t("dark");
  }

  function start() {
    try {
      var saved = localStorage.getItem("tinytsdb-theme");
      if (saved === "dark" || saved === "light") { document.documentElement.setAttribute("data-theme", saved); }
    } catch (ignored) { /* private mode */ }
    api("describe").then(function (d) {
      desc = d;
      readHash();
      build();
      wire();
      themeLabel();
      schedule();
      load();
    }).catch(function (e) {
      $("error").textContent = e.message;
      $("error").hidden = false;
    });
  }

  if (document.readyState === "loading") { document.addEventListener("DOMContentLoaded", start); } else { start(); }
})();
