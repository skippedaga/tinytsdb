# tinytsdb

In-process counters with labels, kept per time bucket and read over calendar, rolling, anchored or arbitrary
windows, with a subset of PromQL on top and an optional web console. Java 25, no dependencies.

It is meant for counts that a request has to know *now*, for example "how many of this user's deposits are pending
in the last 10 minutes" or "how much this card spent this month". The goal is to answer those without a round trip to
a database or to Prometheus, or, with a remote store, in one round trip.

| Artifact | What |
|---|---|
| `io.github.skippedaga:tinytsdb` | the counters, windows, PromQL subset, storage SPI and in-memory store |
| `io.github.skippedaga:tinytsdb-console` | a read-only web console: a JSON API plus three static files, on the JDK's HttpServer or mounted in any framework |

```java
TinyTsdb db = TinyTsdb.builder()
        .zone(ZoneId.of("Europe/Kyiv"))
        .tier(Grain.MINUTE, Duration.ofHours(48))
        .tier(Grain.HOUR, Duration.ofDays(62))
        .tier(Grain.DAY, Duration.ofDays(3 * 365))
        .build();

db.add("tx_total", Labels.of("user", "42", "status", "PENDING"), occurredAtMs, 1);
db.add("amount_total", Labels.of("card", "c1"), occurredAtMs, 12_50);   // minor units
db.add("amount_total", Labels.of("card", "c1"), occurredAtMs, -4_00);   // a partial refund

db.read("amount_total", Labels.of("card", "c1"), Window.calendar("mo:2026-10")).net();      // 850
db.read("tx_total", Labels.of("user", "42", "status", "PENDING"),
        Window.rolling(Duration.ofMinutes(10), Grain.MINUTE)).net();

db.query("sum by (status) (increase(tx_total{user=\"42\"}[10m]))").vector();
```

## Concepts

**Series.** A series is a metric name plus labels (`Labels`, sorted, immutable; an empty value means no label).

**PN counters, integers only.** Every bucket holds two parts that only grow (`Parts`):
- a positive delta adds to `added`;
- a negative delta adds to `retracted`;
- `net = added − retracted`.

Amounts go in as minor units, never as floating point. A status flip from approved to declined is two writes, −1 on
one counter and +1 on the other, both at the event's original time.

**Event time.** A write lands in the bucket of `occurredAtMs`, not of its arrival. A correction that arrives months
later goes back to the event's day, month and year buckets, as long as those tiers still keep that period. All time
(`Grain.ALL`) always counts it.

**Grains and tiers.** Grains:

| Grain | Time zone | Bucket id |
|---|---|---|
| second | UTC | `s:2026-10-29T13:05:07` |
| minute | UTC | `m:2026-10-29T13:05` |
| hour | UTC | `h:2026-10-29T13` |
| day | the store's zone | `d:2026-10-29` |
| ISO week | the store's zone | `w:2026-W44` |
| month | the store's zone | `mo:2026-10` |
| year | the store's zone | `y:2026` |
| all time | — | `all` |

Days, weeks, months and years start at local midnight, so a DST day is 23 or 25 hours long. Every id parses back to
its `[start, end)` with `Grain.parse`. A *tier* is a grain plus a retention. Configure only the tiers you read.

**Windows.**

| Window | Covers | Exactness |
|---|---|---|
| `Window.calendar("d:2026-10-29")` | one bucket | exact |
| `Window.rolling(7, Grain.DAY)`, `Window.rolling(Duration.ofMinutes(5), Grain.MINUTE)` | the last N steps, including the one in progress: `[start of (current − (N−1)), end of current)` | exact to the step; zoned steps are calendar steps |
| `Window.range(from, to)` | `[from, to)`, built from the coarsest whole buckets that fit, with finer ones at the edges | `exact = false` when an edge falls inside the finest bucket kept there; that part is left out, never double-counted |
| `Window.anchored("approved_total", Duration.ofDays(7))` | a fixed length from the series' first positive event of the anchor metric (same labels), from the start of the finest bucket holding it | a late, earlier event moves the anchor back; with `recomputeOnRetraction`, a reversed anchor moves forward to the first bucket that still has a positive net value |
| `Window.allTime()` | everything | exact |

Every `Reading` carries `net`, `added`, `retracted`, the `[from, to)` it counted, `asOf`, `exact`, and its `source`
(the buckets, or a stored rolling aggregate). Reads take several windows at once:
- `read(metric, labels, windows, asOf)`;
- `readGrouped(metric, matchers, groupBy, windows, asOf)` sums series per value of the grouping labels.

**Idempotency, two ways:**
- `applyOnce(key, increments)` writes a batch at most once per key. The key is yours, for example
  `paymentId:STATE:version`. The claim lasts `claimTtl` (7 days by default), so keep it at least as long as your
  source log's retention. `releaseClaim(key)` undoes it after your own processing failed.
- `applyAt(stream, offset, increments)` writes each series at most once per stream offset (a Kafka partition, a
  log). A retry after a partial failure writes only the series that didn't take it, and a replay of old offsets
  changes nothing.

## Storage SPI

`CounterStore` is the core every store implements:
- `apply` (atomic per batch, or per series: `atomicity()`), `claim`;
- bulk reads with `readMany`;
- the first-added anchor, the series version, stored rolling aggregates.

Optional capabilities:

| Capability | Gives | Without it |
|---|---|---|
| `RangeStore` | sums and lists a range of buckets itself | TinyTsdb lists the bucket keys and reads every window of every series in one `readMany` |
| `IndexedStore` | series by label matchers, label values | selectors and grouped reads must name a series exactly (`=` on every label) |
| `MaintainableStore` | expiry run by `maintain()` | `maintain()` does nothing; the store prunes on write or by TTL |
| `ExportableStore` | `export` and `mergeMaxSink`, for `rebuildFrom` | no rebuild |

`MemoryCounterStore` has them all, plus a binary snapshot format (`writeSnapshot` / `readSnapshot`, which also reads
0.1 snapshots; wrap the stream in gzip yourself).

A remote key-value store, say Redis Cluster with one key per series, can implement just the core with
`Atomicity.SERIES`:
- Every write of one series is one atomic call, with the stream offset compared and set inside it.
- `StorageKey` splits labels into a key and a field, so all metrics, currencies and statuses of a card share one hash.

**Merging.** `rebuildFrom(coldStore)` merges stores: max per part and per offset, earliest anchor, union of claims.
That is exact only when every copy applied each event at most once, so that one copy's events are a subset of the
other's. It is not a general multi-writer CRDT.

**Rolling snapshots.** With `ringSnapshots(true)` the library keeps, per series and rolling window, the aggregate of
its buckets.
- It is created lazily on the first read.
- Writes into the window patch it.
- A later read moves it forward by up to 4 steps (subtracting the buckets that left); further behind, it is recomputed.
- A version check keeps a read computed before a concurrent write from storing a stale aggregate.

This pays off with a remote store. The memory store sums its sparse buckets fast enough, so it is off by default.

**Label transforms.** `transform("card", LabelTransform.hmac(key, "tx_"))` rewrites a value before anything is stored,
so raw identifiers never reach the store. Reads and `=` / `!=` matchers go through the same transform. Transforms:

| Transform | Effect |
|---|---|
| `none` | unchanged |
| `upper` | upper case, root locale |
| `prefix:N` | the first N characters |
| `bucket:N` | a number floored to a multiple of N; a value that is not a number drops the label |
| `hmac` | HMAC-SHA256 with your key, base64url without padding, with your prefix |
| `hmac:keep` | as `hmac`, but a value that is already your prefix plus 43 base64url characters passes through, for inputs that may arrive hashed |

**Cardinality.** `CardinalityRule.cap("card", 100_000)` writes new values beyond the cap as `__overflow__`, so counts
are kept but not attributed. `CardinalityRule.idle("card", Duration.ofDays(30))` removes series idle for 30 days whole,
totals included.

**Also included:**
- `P2Quantile`: the P² streaming quantile estimate (Jain & Chlamtac), five markers and O(1) per sample, within 2% of the
  true rank on 10,000 samples in the tests. Up to five samples it is exact, interpolated between ranks (Hyndman & Fan
  type 7). `add` rejects NaN and infinities; `offer` drops them and says so. Not thread-safe.
- `TsdbListener`: hooks for write and read timings, duplicates, rolling-snapshot outcomes and overflows. Label values
  never reach a hook.

## The PromQL subset

| | |
|---|---|
| Selectors | `name{a="x", b!="y", c=~"re", d!~"re"}`, `{__name__=~"tx_.*"}`; regexes are Java's, anchored |
| Range functions | `increase(s[5m])` (net), `added(s[5m])`, `retracted(s[5m])` — a rolling window at the finest second/minute/hour tier whose step divides the range |
| Aggregations | `sum`, `count`, `min`, `max`, `avg`, with `by (…)` or `without (…)` before or after the argument |
| Operators | `+ - * /`, `== != > < >= <=` (filters, or 0/1 with `bool`), `and or unless`, `on (…)` / `ignoring (…)`, unary minus, parentheses |
| Instant selector | the series' all-time net value |

How it differs from Prometheus:
- `increase()` is exact, with no extrapolation.
- A series with nothing in the window is absent, not 0.
- A range no tier divides is refused, not approximated.
- Not supported: `rate`, other functions, `offset`, `@`, subqueries, `group_left` / `group_right`, `topk` and other
  parameterised aggregations, histograms.

## Console (`tinytsdb-console`)

A read-only page for looking at counters:
- the windows of a series, with the added / retracted split;
- the current calendar buckets and anchored windows;
- a rolling-window table that says where each value came from;
- a day or hour history (added above the axis, retracted below);
- top values by label;
- a status strip with latencies (P², from `ConsoleMetrics`).

It has light and dark themes, works at phone width and from the keyboard, and keeps its state in the URL hash.

```java
ConsoleMetrics metrics = new ConsoleMetrics();                 // register as the TinyTsdb listener
TinyTsdb db = TinyTsdb.builder().listener(metrics).build();
ConsoleConfig config = ConsoleConfig.builder()
        .title("Card counters")
        .metric(ConsoleConfig.Metric.count("n_total", "Payments"))
        .metric(ConsoleConfig.Metric.amount("amount_total", "Amount", "currency"))
        .seriesLabel("card").seriesLabel("currency")
        .browseLabel("merchant")
        .anchored("first7d", "First 7 days of the card", "n_ok_total", Duration.ofDays(7))
        .text("label_card", "Card")                            // every word on the page can be replaced
        .build();
ConsoleApi api = new ConsoleApi(db, config, metrics);

// standalone, on the JDK's HttpServer
ConsoleHttpHandler.serve(api, new InetSocketAddress(8090), "/tinytsdb", exchange -> isAllowed(exchange));

// or from your framework's controller: api.handle("GET", "query", params) returns status, content type and JSON
```

The JSON API is GET-only:

| Endpoint | Answers |
|---|---|
| `describe` | what the page shows |
| `query?metric=&window=&<labels>` | every metric over every window |
| `overview` | anchored windows, current calendar buckets, all time |
| `rolling` | every rolling preset, with where its value came from |
| `history?grain=d\|h&count=` | the last buckets of a grain |
| `labels?label=&top=` | top values of a label; needs an `IndexedStore` |
| `promql?query=` | in Prometheus' `/api/v1/query` shape |
| `status` | as of, store capabilities, latencies |

A series is picked by the configured series labels, and the page sums every series that has them. Values are shown as
stored, already transformed, so raw identifiers never reach it.

Security:
- Every answer carries a strict Content-Security-Policy: scripts, styles and requests only from the same origin, no
  framing.
- The page has no inline scripts or styles, no CDN and no web fonts.
- Put it behind your own authentication; `allow` decides each request.

`ConsoleDemo` in the console's tests starts it on made-up card payments.

## Size and speed

Measured with the opt-in `LoadTest` (`mvn test -pl tinytsdb -Dtest=LoadTest -Dtinytsdb.load=true`) on a laptop, Java
25, memory store. The setup was 100,000 users × 4 series, 600,000 increments a day for 31 days, a minute tier for 48
hours and an hour tier for 31 days.

| | |
|---|---|
| Buckets | 19.6 M |
| Heap | ~680 MB |
| Gzipped snapshot | 67 MB; write 7.8 s, read 2.2 s |
| All 12 windows (5m to 30d) for one user, through PromQL | p50 54 µs, p99 161 µs |

Memory follows the number of non-empty buckets. With sparse series (a few events per series per day), every event is
roughly one bucket per tier, so keep the tiers and retentions to what you read.

## Build

```bash
mvn verify
```

A release to Maven Central (sources and javadoc jars, GPG signatures, uploaded to the Central Portal for publishing
there) is `mvn -P release deploy`; the profile in `pom.xml` lists what it needs.

## License

Apache License 2.0, see [LICENSE](LICENSE).
