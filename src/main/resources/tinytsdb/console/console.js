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
    cube: "Cube",
    cubeNote: "The metric sliced by its labels: a row for each label value, a column for each window picked above. "
      + "Each cell adds up every series in it. Empty filter fields match anything.",
    cubeExamples: "Examples",
    cubeExamplePick: "Choose an example",
    cubeRows: "Rows",
    cubeThen: "Then by",
    cubeNone: "None",
    cubeFilter: "Filter",
    cubeAny: "any",
    cubeTotal: "Total",
    cubeTotalMixed: "Not added up: the rows are in different currencies.",
    cubeBy: "by",
    cubeWhere: "where",
    cubeShown: "Showing {shown} of {groups} groups, largest in the last column first; the total counts them all.",
    cubeEmpty: "Nothing matches the filter.",
    cubeMixed: "These amounts add up several currencies ({list}). Filter or group by {label} to compare them.",
    cubePromql: "The same as PromQL, for the {window} column:",
    apiTitle: "API: requests and responses",
    apiNote: "Every call this page makes, newest first. Your own code can make the same GET requests.",
    apiHeld: "{n} newer calls wait until you close the open response.",
    corrections: "Corrections",
    correctionsNote: "Each value is what was added minus what was taken back later: refunds, reversals, status changes.",
    added: "Added",
    retracted: "Retracted",
    net: "Net",
    window: "Window",
    counted: "Counted",
    from: "From",
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
  var state = { labels: {}, metric: "", windows: [], grain: "d", auto: true, by: "", then: "", filter: {}, example: -1 };
  var timer = null;
  var $ = function (id) { return document.getElementById(id); };

  function t(key) { return (desc && desc.text && desc.text[key]) || TEXT[key] || key; }

  /** A text with {name} placeholders filled in. */
  function tf(key, values) {
    return t(key).replace(/\{(\w+)\}/g, function (all, name) { return values[name] !== undefined ? values[name] : all; });
  }

  function labelTitle(l) { return t("label_" + l) === "label_" + l ? l : t("label_" + l); }

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
    state.auto = params.get("auto") !== "off";
    var dims = desc.dimensions || [];
    var by = params.get("by");
    state.by = dims.indexOf(by) >= 0 ? by : (desc.browseLabels[0] || dims[0] || "");
    var then = params.get("then");
    state.then = dims.indexOf(then) >= 0 && then !== state.by ? then : "";
    state.filter = {};
    dims.forEach(function (l) {
      if (params.get("f." + l)) { state.filter[l] = params.get("f." + l); }
    });
    var ex = parseInt(params.get("ex"), 10);
    state.example = ex >= 0 && ex < (desc.examples || []).length ? ex : -1;
    if (state.example >= 0 && !params.get("by")) { // a short link, "#ex=2": the example decides the slice
      applyExample(desc.examples[state.example]);
    }
  }

  function applyExample(e) {
    state.metric = e.metric;
    state.by = e.by[0];
    state.then = e.by[1] || "";
    state.filter = Object.assign({}, e.filter);
  }

  function writeHash() {
    var params = new URLSearchParams();
    Object.keys(state.labels).forEach(function (k) { params.set(k, state.labels[k]); });
    params.set("metric", state.metric);
    params.set("windows", state.windows.join(","));
    params.set("grain", state.grain);
    if (state.by) { params.set("by", state.by); }
    if (state.then) { params.set("then", state.then); }
    Object.keys(state.filter).forEach(function (k) { params.set("f." + k, state.filter[k]); });
    if (state.example >= 0) { params.set("ex", String(state.example)); }
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
    var started = performance.now();
    return fetch(url, { headers: { "Accept": "application/json" }, credentials: "same-origin" })
      .then(function (r) {
        return r.text().then(function (text) {
          logCall(url, r.status, performance.now() - started, text);
          var body;
          try { body = JSON.parse(text); } catch (e) { body = { error: text }; }
          if (!r.ok) { throw new Error(body.error || ("HTTP " + r.status)); }
          return body;
        });
      }, function (e) {
        logCall(url, null, performance.now() - started, e.message);
        throw e;
      });
  }

  // ---- the API box: every request and its answer --------------------------------------------------------------------

  var MAX_CALLS = 40;
  var held = []; // calls made while a response is open, so the one being read does not move

  function logCall(url, status, ms, text) {
    var item = callItem(url, status, ms, text);
    if ($("api-entries").querySelector("details[open]")) {
      held.push(item);
      showHeld();
      return;
    }
    addCalls([item]);
  }

  function addCalls(items) {
    var list = $("api-entries");
    items.forEach(function (item) { list.insertBefore(item, list.firstChild); });
    while (list.children.length > MAX_CALLS) { list.removeChild(list.lastChild); }
  }

  function showHeld() {
    var line = $("api-held");
    line.hidden = !held.length;
    line.textContent = tf("apiHeld", { n: held.length });
  }

  function callItem(url, status, ms, text) {
    var item = el("li", "api-call" + (status === null || status >= 400 ? " failed" : ""));
    var box = el("details");
    var head = el("summary");
    head.appendChild(el("code", null, "GET " + new URL(url, location.href).href));
    head.appendChild(el("span", "api-meta", (status === null ? "failed" : status) + " · "
      + (ms < 10 ? ms.toFixed(1) : Math.round(ms)) + " ms · " + clock.format(new Date())));
    box.appendChild(head);
    var pre = el("pre");
    box.appendChild(pre);
    box.addEventListener("toggle", function () { // pretty-print only what someone opens
      if (box.open && !pre.firstChild) {
        var shown;
        try { shown = JSON.stringify(JSON.parse(text), null, 2); } catch (e) { shown = text; }
        pre.textContent = shown.length > 50000 ? shown.slice(0, 50000) + "\n…" : shown;
      }
      if (!box.open && held.length && !$("api-entries").querySelector("details[open]")) {
        addCalls(held);
        held = [];
        showHeld();
      }
    });
    item.appendChild(box);
    return item;
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

  /** @param currency for amounts: the currency to print; left out, the series form's */
  function fmt(value, metric, currency) {
    var m = metricInfo(metric);
    if (m.unit === "MINOR_UNITS") {
      if (currency === undefined) { currency = m.currencyLabel ? state.labels[m.currencyLabel] : null; }
      return money.format(value / 100) + (currency ? " " + currency : "");
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

  function cubeCell(td, c, metric, currency, max) {
    td.appendChild(el("span", "cell-net", fmt(c.net, metric, currency)));
    if (c.retracted) {
      var p = el("span", "cell-parts");
      p.appendChild(el("span", "a", "+" + fmt(c.added, metric, null)));
      p.appendChild(document.createTextNode(" "));
      p.appendChild(el("span", "r", "−" + fmt(c.retracted, metric, null)));
      td.appendChild(p);
    }
    if (max > 0) { // how this cell compares with the largest in its column
      var bar = el("span", "cell-bar");
      bar.setAttribute("aria-hidden", "true");
      var fill = el("span");
      fill.style.width = Math.max(0, 100 * c.net / max) + "%";
      bar.appendChild(fill);
      td.appendChild(bar);
    }
    td.title = t("added") + " " + fmt(c.added, metric, currency) + ", " + t("retracted").toLowerCase() + " "
      + fmt(c.retracted, metric, currency);
  }

  function renderCube(data) {
    var m = metricInfo(data.metric);
    var by = data.by;
    var curLabel = m.unit === "MINOR_UNITS" ? m.currencyLabel : null;
    var mixed = data.currencies && data.currencies.length > 1;
    var shared = data.currencies && data.currencies.length === 1 ? data.currencies[0] : null;

    var where = Object.keys(data.filter).map(function (k) { return labelTitle(k) + " = " + data.filter[k]; });
    $("cube-summary").textContent = m.title + " " + t("cubeBy") + " " + by.map(labelTitle).join(", ")
      + (where.length ? ", " + t("cubeWhere") + " " + where.join(", ") : "") + ". "
      + (data.groups > data.rows.length ? tf("cubeShown", { shown: data.rows.length, groups: data.groups }) : "");

    var table = $("cube-table");
    var head = table.tHead;
    clear(head);
    var hr = el("tr");
    by.forEach(function (l) { var th = el("th", null, labelTitle(l)); th.scope = "col"; hr.appendChild(th); });
    data.columns.forEach(function (c) { var th = el("th", "num", c.title); th.scope = "col"; hr.appendChild(th); });
    head.appendChild(hr);

    var max = data.columns.map(function (c, i) {
      return data.rows.reduce(function (x, r) { return Math.max(x, r.cells[i].net); }, 0);
    });
    var body = table.tBodies[0];
    clear(body);
    if (!data.rows.length) {
      var er = el("tr");
      var etd = el("td", "note", t("cubeEmpty"));
      etd.colSpan = by.length + data.columns.length;
      er.appendChild(etd);
      body.appendChild(er);
    }
    data.rows.forEach(function (r) {
      var tr = el("tr");
      by.forEach(function (l) { var th = el("th", "dim", r.labels[l] || "–"); th.scope = "row"; tr.appendChild(th); });
      var currency = mixed ? null : (curLabel && r.labels[curLabel]) || shared;
      r.cells.forEach(function (c, i) {
        var td = el("td", "num cell");
        cubeCell(td, c, data.metric, currency, max[i]);
        tr.appendChild(td);
      });
      body.appendChild(tr);
    });

    var foot = table.tFoot;
    clear(foot);
    var ft = el("tr");
    var label = el("th", null, t("cubeTotal"));
    label.scope = "row";
    label.colSpan = by.length;
    ft.appendChild(label);
    // grouped by currency, each row has one; only a total over several would mix them
    var rowCurrencies = {};
    var byCurrency = !!curLabel && by.indexOf(curLabel) >= 0;
    if (byCurrency) {
      data.rows.forEach(function (r) { rowCurrencies[r.labels[curLabel]] = true; });
    }
    // rows left out could hold other currencies
    var totalMixes = byCurrency && (Object.keys(rowCurrencies).length > 1 || data.groups > data.rows.length);
    data.total.forEach(function (c) {
      var td = el("td", "num cell");
      if (totalMixes) {
        td.appendChild(el("span", "cell-net", "–"));
        td.title = t("cubeTotalMixed");
      } else {
        cubeCell(td, c, data.metric, mixed ? null : (shared || Object.keys(rowCurrencies)[0] || null), 0);
      }
      ft.appendChild(td);
    });
    foot.appendChild(ft);

    var warn = $("cube-warn");
    warn.hidden = !mixed;
    warn.textContent = tf("cubeMixed", { list: (data.currencies || []).join(", "), label: labelTitle(curLabel || "") });

    var q = null;
    for (var i = data.columns.length - 1; i >= 0 && !q; i--) { if (data.columns[i].promql) { q = data.columns[i]; } }
    $("cube-promql").hidden = !q;
    if (q) {
      $("cube-promql-label").textContent = tf("cubePromql", { window: q.title });
      $("cube-promql-code").textContent = q.promql;
    }
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
    var cube = (desc.dimensions || []).length > 0 && state.by;
    $("cube").hidden = !cube;
    api("status").then(renderStatus).catch(function () { /* the strip just stays as it was */ });
    var jobs = [];
    if (cube) { // the cube needs no series: its own filter picks what it slices
      var qc = new URLSearchParams();
      qc.set("metric", state.metric);
      qc.set("by", state.then ? state.by + "," + state.then : state.by);
      var plain = state.windows.filter(function (k) {
        return !desc.anchored.some(function (a) { return a.key === k; });
      });
      qc.set("window", (plain.length ? plain : desc.windows.map(function (w) { return w.key; })).join(","));
      Object.keys(state.filter).forEach(function (k) { qc.set(k, state.filter[k]); });
      jobs.push(api("cube", qc).then(renderCube));
    }
    if (!shown) { return Promise.all(jobs).catch(showError); }

    var q = seriesQuery();
    q.set("metric", state.metric);
    var qw = new URLSearchParams(q);
    qw.set("window", state.windows.join(","));
    var qh = new URLSearchParams(q);
    qh.set("grain", state.grain);
    qh.set("count", state.grain === "h" ? "48" : "30");
    jobs.push(api("query", qw).then(renderTiles));
    jobs.push(api("overview", q).then(renderOverview));
    jobs.push(api("rolling", q).then(renderRolling));
    jobs.push(api("history", qh).then(renderHistory));
    return Promise.all(jobs).catch(showError);
  }

  function showError(e) {
    $("error").textContent = e.message;
    $("error").hidden = false;
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
    $("cube-title").textContent = t("cube");
    $("cube-note").textContent = t("cubeNote");
    $("cube-by-label").textContent = t("cubeRows");
    $("cube-then-label").textContent = t("cubeThen");
    $("cube-apply").textContent = t("cubeFilter");
    $("cube-example-label").textContent = t("cubeExamples");
    $("api-title").textContent = t("apiTitle");
    $("api-note").textContent = t("apiNote");

    var examples = desc.examples || [];
    $("cube-example-field").hidden = !examples.length;
    var ex = $("cube-example");
    clear(ex);
    var pickOne = el("option", null, t("cubeExamplePick"));
    pickOne.value = "-1";
    ex.appendChild(pickOne);
    examples.forEach(function (e, i) {
      var o = el("option", null, e.title);
      o.value = String(i);
      o.selected = i === state.example;
      ex.appendChild(o);
    });
    var explanation = $("cube-explanation");
    explanation.hidden = state.example < 0;
    explanation.textContent = state.example >= 0 ? examples[state.example].explanation : "";
    $("corrections-title").textContent = t("corrections");
    $("corrections-note").textContent = t("correctionsNote");
    $("legend-added").textContent = t("added");
    $("legend-retracted").textContent = t("retracted");
    $("empty").textContent = t("empty");
    [["rt-window", "window"], ["rt-net", "net"], ["rt-added", "added"], ["rt-retracted", "retracted"],
      ["rt-span", "counted"]].forEach(function (p) { $(p[0]).textContent = t(p[1]); });

    var fields = $("series-fields");
    clear(fields);
    desc.seriesLabels.forEach(function (l) {
      var label = el("label", "field");
      label.appendChild(el("span", null, labelTitle(l)));
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

    var dims = desc.dimensions || [];
    var by = $("cube-by");
    clear(by);
    dims.forEach(function (l) {
      var o = el("option", null, labelTitle(l));
      o.value = l;
      o.selected = l === state.by;
      by.appendChild(o);
    });
    var then = $("cube-then");
    clear(then);
    var none = el("option", null, t("cubeNone"));
    none.value = "";
    then.appendChild(none);
    dims.forEach(function (l) {
      if (l === state.by) { return; }
      var o = el("option", null, labelTitle(l));
      o.value = l;
      o.selected = l === state.then;
      then.appendChild(o);
    });
    var filters = $("cube-filter-fields");
    clear(filters);
    dims.forEach(function (l) {
      var label = el("label", "field");
      label.appendChild(el("span", null, labelTitle(l)));
      var input = el("input");
      input.name = l;
      input.value = state.filter[l] || "";
      input.placeholder = t("cubeAny");
      input.spellcheck = false;
      label.appendChild(input);
      filters.appendChild(label);
    });

    Array.prototype.forEach.call(document.querySelectorAll("[data-grain]"), function (o) {
      o.setAttribute("aria-pressed", o.getAttribute("data-grain") === state.grain ? "true" : "false");
    });
  }

  function wire() {
    $("series").addEventListener("submit", function (e) {
      e.preventDefault();
      state.labels = {};
      Array.prototype.forEach.call($("series-fields").querySelectorAll("input"), function (i) {
        if (i.value.trim()) { state.labels[i.name] = i.value.trim(); }
      });
      if (state.metric !== $("metric").value) { state.example = -1; } // the example was about the other metric
      state.metric = $("metric").value;
      writeHash();
      build();
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
    $("cube-example").addEventListener("change", function () {
      var i = parseInt($("cube-example").value, 10);
      state.example = i;
      if (i >= 0) { applyExample(desc.examples[i]); }
      writeHash();
      build();
      load();
    });
    // changing the cube by hand leaves the example: its explanation no longer describes what is shown
    $("cube-by").addEventListener("change", function () {
      state.by = $("cube-by").value;
      if (state.then === state.by) { state.then = ""; }
      state.example = -1;
      writeHash();
      build();
      load();
    });
    $("cube-then").addEventListener("change", function () {
      state.then = $("cube-then").value;
      state.example = -1;
      writeHash();
      build();
      load();
    });
    $("cube-filter").addEventListener("submit", function (e) {
      e.preventDefault();
      state.filter = {};
      Array.prototype.forEach.call($("cube-filter-fields").querySelectorAll("input"), function (i) {
        if (i.value.trim()) { state.filter[i.name] = i.value.trim(); }
      });
      state.example = -1;
      writeHash();
      build();
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
    // the system theme can change while the page is open; the button names the other one
    var scheme = matchMedia("(prefers-color-scheme: dark)");
    if (scheme.addEventListener) { scheme.addEventListener("change", themeLabel); }
    // a link pasted into an open tab, or back / forward; writeHash uses replaceState, which does not fire this
    window.addEventListener("hashchange", function () {
      readHash();
      build();
      schedule();
      load();
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
