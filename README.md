# tinytsdb

In-process counters with labels, kept per time bucket and read over calendar, rolling, anchored or arbitrary
windows, with a subset of PromQL on top. Java 17, no dependencies.

It is meant for counts that a request has to know *now*, for example "how many of this user's deposits are pending
in the last 10 minutes" or "how much this card spent this month". The goal is to answer those without a round trip to
a database or to Prometheus.

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

Every `Reading` carries `net`, `added`, `retracted`, the `[from, to)` it counted, `asOf` and `exact`.
`readGrouped(metric, filter, groupBy, window)` sums series per value of the grouping labels.

**Idempotency.** `applyOnce(key, increments)` writes a batch atomically, at most once per key. The key is yours, for
example `paymentId:STATE:version`.
- The claim lasts `claimTtl` (7 days by default). Keep that at least as long as your source log's retention, so a
  replay cannot count twice.
- `releaseClaim(key)` undoes a claim after your own processing failed.

**Storage SPI.** `CounterStore` is where buckets live. `MemoryCounterStore` ships with the library, with an inverted
label index and a binary snapshot format (`writeSnapshot` / `readSnapshot`; wrap the stream in gzip yourself). A
product can bring its own store, such as Redis for hot data or a database for history.
- `rebuildFrom(coldStore)` merges stores: max per part, earliest anchor, union of claims.
- That merge is exact only when every copy applied each event at most once, so that one copy's events are a subset of
  the other's. It is not a general multi-writer CRDT.

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

**Cardinality.** `CardinalityRule.cap("card", 100_000)` writes new values beyond the cap as `__overflow__`, so counts
are kept but not attributed. `CardinalityRule.idle("card", Duration.ofDays(30))` removes series idle for 30 days
whole, totals included. Run `maintain()` every minute or so; it also drops buckets past each tier's retention and
expired claims.

**Also included:**
- `P2Quantile`: the P² streaming quantile estimate (Jain & Chlamtac), five markers and O(1) per sample, within 2% of the
  true rank on 10,000 samples in the tests.
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

## Size and speed

Measured with the opt-in `LoadTest` (`mvn test -Dtest=LoadTest -Dtinytsdb.load=true`) on a laptop, Java 21, memory
store. The setup was 100,000 users × 4 series, 600,000 increments a day for 31 days, a minute tier for 48 hours and an
hour tier for 31 days.

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

## License

Apache License 2.0, see [LICENSE](LICENSE).
