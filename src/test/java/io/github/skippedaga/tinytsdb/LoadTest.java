package io.github.skippedaga.tinytsdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Opt-in ({@code -Dtinytsdb.load=true}): {@code tinytsdb.users} users × 4 series, {@code tinytsdb.perDay} increments
 * a day for 31 days, minute tier 48h + hour tier 31d. Prints heap, snapshot size and timings; asserts nothing.
 */
@EnabledIfSystemProperty(named = "tinytsdb.load", matches = "true")
class LoadTest {

    @Test
    void measure() throws Exception {
        int users = Integer.getInteger("tinytsdb.users", 100_000);
        int perDay = Integer.getInteger("tinytsdb.perDay", 600_000);
        int days = 31;
        long day = TinyTsdbTest.DAY;
        long start = TinyTsdbTest.T - days * day;
        TestClock clock = new TestClock(start);
        String[] statuses = {"PENDING", "SUCCESS", "FAILED", "PENDING_P2P"};
        Random random = new Random(1);

        long heapBefore = usedHeap();
        MemoryCounterStore store = new MemoryCounterStore();
        TinyTsdb db = TinyTsdb.builder().clock(clock).store(store)
                .tier(Grain.MINUTE, Duration.ofHours(48))
                .tier(Grain.HOUR, Duration.ofDays(31))
                .build();
        long t0 = System.nanoTime();
        long added = 0;
        for (int d = 0; d < days; d++) {
            for (int i = 0; i < perDay; i++) {
                clock.set(start + d * day + (long) i * day / perDay);
                db.add("tx_total", Labels.of("user", Integer.toString(random.nextInt(users)), "direction", "DEPOSIT",
                        "status", statuses[random.nextInt(statuses.length)]), clock.millis(), 1);
                added++;
            }
            db.maintain();
        }
        double addSeconds = (System.nanoTime() - t0) / 1e9;
        long heap = usedHeap() - heapBefore;

        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        long w = System.nanoTime();
        try (GZIPOutputStream gz = new GZIPOutputStream(zipped)) {
            store.writeSnapshot(gz);
        }
        double writeSeconds = (System.nanoTime() - w) / 1e9;
        long r = System.nanoTime();
        MemoryCounterStore restored = MemoryCounterStore.readSnapshot(
                new GZIPInputStream(new ByteArrayInputStream(zipped.toByteArray())));
        double readSeconds = (System.nanoTime() - r) / 1e9;
        if (restored.bucketCount() != store.bucketCount()) {
            throw new AssertionError("restore lost buckets");
        }
        restored = null;

        String[] windows = {"5m", "10m", "30m", "60m", "3h", "5h", "10h", "1d", "5d", "7d", "10d", "30d"};
        Query[] queries = new Query[windows.length];
        int reads = 20_000;
        long[] nanos = new long[reads];
        for (int q = 0; q < reads; q++) {
            String user = Integer.toString(random.nextInt(users));
            long t = System.nanoTime();
            for (int i = 0; i < windows.length; i++) {
                db.query("sum by (direction, status) (increase(tx_total{user=\"" + user + "\"}[" + windows[i] + "]))");
            }
            nanos[q] = System.nanoTime() - t;
        }
        Arrays.sort(nanos);

        System.out.printf("users=%d series=%d buckets=%d increments=%d in %.1fs (%.0f/s)%n", users,
                store.seriesCount(), store.bucketCount(), added, addSeconds, added / addSeconds);
        System.out.printf("heap ~%d MB, snapshot %d MB gzip, write %.1fs, read %.1fs%n", heap >> 20,
                zipped.size() >> 20, writeSeconds, readSeconds);
        System.out.printf("12 windows for one user (parse + evaluate): p50 %.0f µs, p99 %.0f µs%n",
                nanos[reads / 2] / 1e3, nanos[reads * 99 / 100] / 1e3);
        if (queries.length != windows.length) {
            throw new AssertionError();
        }
    }

    private static long usedHeap() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
