package io.github.skippedaga.tinytsdb.console;

import io.github.skippedaga.tinytsdb.P2Quantile;
import io.github.skippedaga.tinytsdb.TsdbListener;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link TsdbListener} that keeps what the console's status strip shows: write and read latencies (P² median and
 * 99th percentile, per read kind), duplicates, overflows and rolling-aggregate outcomes. Register it with
 * {@code TinyTsdb.builder().listener(metrics)} (or chain it from your own listener).
 */
public final class ConsoleMetrics implements TsdbListener {

    private static final class Latency {
        final P2Quantile p50 = new P2Quantile(0.5);
        final P2Quantile p99 = new P2Quantile(0.99);

        synchronized void add(long nanos) {
            double ms = nanos / 1e6;
            p50.add(ms);
            p99.add(ms);
        }

        synchronized Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", p50.count());
            m.put("p50Ms", p50.count() == 0 ? null : p50.value());
            m.put("p99Ms", p99.count() == 0 ? null : p99.value());
            return m;
        }
    }

    private final Latency writes = new Latency();
    private final AtomicLong increments = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong overflows = new AtomicLong();
    private final Map<ReadKind, Latency> reads = new EnumMap<>(ReadKind.class);
    private final Map<RingOutcome, AtomicLong> rings = new EnumMap<>(RingOutcome.class);

    public ConsoleMetrics() {
        for (ReadKind k : ReadKind.values()) {
            reads.put(k, new Latency());
        }
        for (RingOutcome o : RingOutcome.values()) {
            rings.put(o, new AtomicLong());
        }
    }

    @Override
    public void onWrite(int increments, long nanos) {
        this.increments.addAndGet(increments);
        writes.add(nanos);
    }

    @Override
    public void onDuplicate() {
        duplicates.incrementAndGet();
    }

    @Override
    public void onRead(ReadKind kind, long nanos) {
        reads.get(kind).add(nanos);
    }

    @Override
    public void onRing(RingOutcome outcome) {
        rings.get(outcome).incrementAndGet();
    }

    @Override
    public void onOverflow(String label) {
        overflows.incrementAndGet();
    }

    /** What the status strip shows. */
    Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> w = new LinkedHashMap<>(writes.view());
        w.put("increments", increments.get());
        w.put("duplicates", duplicates.get());
        w.put("overflows", overflows.get());
        m.put("writes", w);
        Map<String, Object> r = new LinkedHashMap<>();
        reads.forEach((k, v) -> r.put(k.name().toLowerCase(java.util.Locale.ROOT), v.view()));
        m.put("reads", r);
        Map<String, Object> o = new LinkedHashMap<>();
        rings.forEach((k, v) -> o.put(k.name().toLowerCase(java.util.Locale.ROOT), v.get()));
        m.put("rollingAggregates", o);
        return m;
    }
}
