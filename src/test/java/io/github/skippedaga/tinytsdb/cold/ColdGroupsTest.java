package io.github.skippedaga.tinytsdb.cold;

import io.github.skippedaga.tinytsdb.CounterStore.Batch;
import io.github.skippedaga.tinytsdb.CounterStore.BucketWrite;
import io.github.skippedaga.tinytsdb.CounterStore.SeriesBatch;
import io.github.skippedaga.tinytsdb.Grain;
import io.github.skippedaga.tinytsdb.Labels;
import io.github.skippedaga.tinytsdb.MemoryCounterStore;
import io.github.skippedaga.tinytsdb.Parts;
import io.github.skippedaga.tinytsdb.SeriesGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColdGroupsTest {

    static final long T0 = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
    static final long MIN = 60_000;
    static final long HOUR = 3_600_000;
    static final long DAY = 86_400_000;
    static final Set<Grain> WINDOWS = EnumSet.of(Grain.MINUTE, Grain.DAY);

    /** A clock moved by hand. */
    static final class ManualClock extends Clock {
        volatile long now;

        ManualClock(long now) {
            this.now = now;
        }

        @Override
        public long millis() {
            return now;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** A store in memory that can be made to fail. */
    static final class MemoryColdStore implements ColdStore {
        final Map<String, Row> rows = new ConcurrentHashMap<>();
        final AtomicBoolean down = new AtomicBoolean();
        volatile Runnable onSave = () -> { };

        @Override
        public void saveAll(List<Row> batch) {
            if (down.get()) {
                throw new IllegalStateException("store down");
            }
            for (Row r : batch) {
                rows.merge(r.key(), r, (held, now) -> now.evictedAtMs() >= held.evictedAtMs() ? now : held);
            }
            onSave.run();
        }

        @Override
        public Map<String, Row> readMany(Collection<String> keys) {
            if (down.get()) {
                throw new IllegalStateException("store down");
            }
            Map<String, Row> out = new HashMap<>();
            for (String k : keys) {
                Row r = rows.get(k);
                if (r != null) {
                    out.put(k, r);
                }
            }
            return out;
        }

        @Override
        public int deleteEvictedBefore(long beforeMs) {
            if (down.get()) {
                throw new IllegalStateException("store down");
            }
            int before = rows.size();
            rows.values().removeIf(r -> r.evictedAtMs() < beforeMs);
            return before - rows.size();
        }
    }

    private final ManualClock clock = new ManualClock(T0);
    private final MemoryCounterStore memory = new MemoryCounterStore();
    private final MemoryColdStore store = new MemoryColdStore();
    private final List<String> warnings = new ArrayList<>();
    private final List<ColdGroups> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(ColdGroups::close);
    }

    private ColdGroups cold(MemoryCounterStore mem, MemoryColdStore rows) {
        ColdGroups c = ColdGroups.builder(rows, () -> mem, "user").clock(clock).coldAfter(Duration.ofDays(7))
                .keepFor(Duration.ofDays(33)).grains(WINDOWS).batch(3).warn(warnings::add).build();
        opened.add(c);
        return c;
    }

    private ColdGroups cold() {
        return cold(memory, store);
    }

    static void waitUntil(int seconds, BooleanSupplier done) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("not within " + seconds + " s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Labels series(String user, String status) {
        return Labels.of(Labels.METRIC_NAME, "tx_total", "user", user, "status", status);
    }

    /** A write as an application makes it: every grain, then the activity. */
    static void write(MemoryCounterStore mem, ColdGroups cold, String user, String status, long at, long delta) {
        mem.apply(new Batch(List.of(new SeriesBatch(series(user, status), List.of(
                new BucketWrite(Grain.MINUTE, at - at % MIN, Parts.of(delta)),
                new BucketWrite(Grain.DAY, at - at % DAY, Parts.of(delta)),
                new BucketWrite(Grain.ALL, 0, Parts.of(delta))), delta > 0 ? at : Long.MAX_VALUE, at, null)), null, 0));
        cold.written(user);
    }

    private void write(ColdGroups cold, String user, String status, long delta) {
        write(memory, cold, user, status, clock.millis(), delta);
    }

    static long days(MemoryCounterStore mem, String user, String status) {
        return mem.sum(series(user, status), Grain.DAY, Long.MIN_VALUE, Long.MAX_VALUE).net();
    }

    static long minutes(MemoryCounterStore mem, String user, String status) {
        return mem.sum(series(user, status), Grain.MINUTE, Long.MIN_VALUE, Long.MAX_VALUE).net();
    }

    @Test
    void theBuilderRefusesWhatCannotWork() {
        assertThatThrownBy(() -> ColdGroups.builder(store, () -> memory, "user").keepFor(Duration.ofDays(1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColdGroups.builder(store, () -> memory, "user").coldAfter(Duration.ofDays(7))
                .keepFor(Duration.ofDays(1)).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColdGroups.builder(store, () -> memory, "user").coldAfter(Duration.ofDays(7))
                .keepFor(Duration.ofDays(33)).conflictAfter(Duration.ofDays(7)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColdGroups.builder(store, () -> memory, "user").grains(Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIdleGroupLeavesWithItsRowAndAReadBringsItBackExactly() {
        ColdGroups cold = cold();
        write(cold, "1", "SUCCESS", 2);
        write(cold, "1", "PENDING", 1);
        clock.now += MIN;
        write(cold, "1", "PENDING", -1);
        write(cold, "2", "SUCCESS", 1);
        clock.now += 6 * DAY;
        write(cold, "2", "SUCCESS", 1); // 2 stays active
        clock.now += DAY + HOUR;

        assertThat(cold.evict()).isEqualTo(1);
        assertThat(cold.isCold("1")).isTrue();
        assertThat(cold.isCold("2")).isFalse();
        assertThat(memory.seriesOf("user", "1")).isEmpty();
        assertThat(store.rows).containsOnlyKeys("1");
        assertThat(cold.stats().cold()).isEqualTo(1);
        assertThat(cold.stats().active()).isEqualTo(1);

        cold.ensureLoaded("1");
        assertThat(cold.isCold("1")).isFalse();
        assertThat(days(memory, "1", "SUCCESS")).isEqualTo(2);
        assertThat(minutes(memory, "1", "SUCCESS")).isEqualTo(2);
        assertThat(memory.sum(series("1", "PENDING"), Grain.MINUTE, Long.MIN_VALUE, Long.MAX_VALUE))
                .isEqualTo(new Parts(1, 1));
        // all time was not kept (not among the grains): gone with the series
        assertThat(memory.sum(series("1", "SUCCESS"), Grain.ALL, 0, 1)).isEqualTo(Parts.ZERO);
        assertThat(cold.stats().loaded()).isEqualTo(1);
        // loaded: active again from now, not evicted at the next run
        assertThat(cold.evict()).isZero();
    }

    @Test
    void aGroupWithNothingInTheWindowsKeptLeavesWithoutARowOrAMark() {
        ColdGroups cold = ColdGroups.builder(store, () -> memory, "user").clock(clock).coldAfter(Duration.ofDays(7))
                .keepFor(Duration.ofDays(33)).grains(EnumSet.of(Grain.MINUTE)).build();
        opened.add(cold);
        write(cold, "1", "SUCCESS", 1);
        memory.expire(Grain.MINUTE, clock.now + DAY); // the minutes are past their retention
        clock.now += 8 * DAY;
        assertThat(cold.evict()).isZero();
        assertThat(memory.seriesOf("user", "1")).isEmpty();
        assertThat(cold.isCold("1")).isFalse();
        assertThat(cold.stats().active()).isZero();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void aWriteToAColdGroupIsCountedAtOnceAndTheRowIsAddedInTheBackground() {
        ColdGroups cold = cold();
        write(cold, "1", "SUCCESS", 2);
        clock.now += 8 * DAY;
        assertThat(cold.evict()).isEqualTo(1);

        write(cold, "1", "SUCCESS", 3);
        assertThat(days(memory, "1", "SUCCESS")).isIn(3L, 5L); // the row may already be in
        waitUntil(10, () -> !cold.isCold("1"));
        assertThat(days(memory, "1", "SUCCESS")).isEqualTo(5);
        assertThat(cold.stats().loadQueue()).isZero();
    }

    @Test
    void aReadOrAWriteBetweenTheExportAndTheRemovalKeepsTheGroup() {
        ColdGroups cold = cold();
        write(cold, "1", "SUCCESS", 1);
        write(cold, "2", "SUCCESS", 1);
        clock.now += 8 * DAY;
        // while the rows are saved: 1 is read, a series of 2 is written without the application telling (version)
        store.onSave = () -> {
            cold.read("1");
            memory.apply(new Batch(List.of(new SeriesBatch(series("2", "SUCCESS"),
                    List.of(new BucketWrite(Grain.MINUTE, clock.now - clock.now % MIN, Parts.of(1))), clock.now,
                    clock.now, null)), null, 0));
        };
        assertThat(cold.evict()).isZero();
        assertThat(cold.isCold("1")).isFalse();
        assertThat(cold.isCold("2")).isFalse();
        assertThat(memory.seriesOf("user", "1")).hasSize(1);
        assertThat(minutes(memory, "2", "SUCCESS")).isEqualTo(2);
        assertThat(cold.stats().kept()).isEqualTo(2);
        // their rows were written: harmless (never loaded without a mark), dropped after keepFor or replaced
        store.onSave = () -> { };
        clock.now += 8 * DAY;
        // 8 days on, neither was active again: both go
        assertThat(cold.evict()).isEqualTo(2);
    }

    @Test
    void theStoreDownOnARead() {
        ColdGroups cold = cold();
        write(cold, "1", "SUCCESS", 1);
        clock.now += 8 * DAY;
        cold.evict();
        store.down.set(true);
        assertThatThrownBy(() -> cold.ensureLoaded("1")).hasMessage("store down");
        assertThat(cold.isCold("1")).isTrue();
        assertThat(cold.stats().loadFailures()).isEqualTo(1);
        store.down.set(false);
        cold.ensureLoaded("1");
        assertThat(days(memory, "1", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void theStoreDownOnAnEvictionRemovesNothing() {
        ColdGroups cold = cold();
        for (int u = 0; u < 7; u++) {
            write(cold, Integer.toString(u), "SUCCESS", 1);
        }
        clock.now += 8 * DAY;
        store.down.set(true);
        assertThat(cold.evict()).isZero();
        assertThat(memory.labelValues("user")).hasSize(7);
        assertThat(cold.stats().cold()).isZero();
        assertThat(warnings).anyMatch(w -> w.contains("refused the rows"));
        store.down.set(false);
        assertThat(cold.evict()).isEqualTo(7);
    }

    @Test
    void theBackgroundLoadRetriesWhileTheStoreIsDown() {
        ColdGroups cold = cold();
        write(cold, "1", "SUCCESS", 2);
        clock.now += 8 * DAY;
        cold.evict();
        store.down.set(true);
        write(cold, "1", "SUCCESS", 1);
        waitUntil(10, () -> cold.stats().loadFailures() > 0);
        assertThat(cold.isCold("1")).isTrue();
        store.down.set(false);
        waitUntil(40, () -> !cold.isCold("1"));
        assertThat(days(memory, "1", "SUCCESS")).isEqualTo(3);
    }

    @Test
    void marksAndRowsAreDroppedAfterKeepFor() {
        ColdGroups cold = cold();
        write(cold, "1", "SUCCESS", 1);
        write(cold, "2", "SUCCESS", 1);
        clock.now += 8 * DAY;
        cold.evict();
        assertThat(store.rows).hasSize(2);
        clock.now += 32 * DAY;
        cold.evict();
        assertThat(cold.stats().cold()).isEqualTo(2);
        clock.now += 2 * DAY;
        cold.evict();
        assertThat(cold.stats().cold()).isZero();
        assertThat(cold.stats().expired()).isEqualTo(2);
        assertThat(store.rows).isEmpty();
        // a write after that: a group from zero, not a cold one
        write(cold, "1", "SUCCESS", 1);
        assertThat(cold.isCold("1")).isFalse();
        assertThat(days(memory, "1", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void aRowOfALaterAbsenceIsAConflict() {
        MemoryCounterStore otherMemory = new MemoryCounterStore();
        ColdGroups a = cold();
        ColdGroups b = cold(otherMemory, store);
        write(memory, a, "1", "SUCCESS", clock.now, 1);
        write(otherMemory, b, "1", "SUCCESS", clock.now, 1);
        clock.now += 8 * DAY;
        a.evict();
        b.evict();
        // b takes the user back and evicts it again much later; a never loaded it meanwhile
        b.ensureLoaded("1");
        write(otherMemory, b, "1", "SUCCESS", clock.now, 1);
        clock.now += 8 * DAY;
        b.evict();
        a.ensureLoaded("1");
        assertThat(a.stats().conflicts()).isEqualTo(1);
        assertThat(warnings).anyMatch(w -> w.contains("may count twice"));
        assertThat(days(memory, "1", "SUCCESS")).isEqualTo(2);
    }

    @Test
    void theStateTravelsWithASnapshotAndAWriteToAColdGroupGetsItsRowAfterARestore() throws Exception {
        ColdGroups cold = cold();
        write(cold, "1", "SUCCESS", 2);
        write(cold, "2", "SUCCESS", 1);
        clock.now += 8 * DAY;
        cold.evict();
        // a write to cold 1 counted, then the snapshot (the row not loaded yet: the store is down)
        store.down.set(true);
        write(cold, "1", "SUCCESS", 1);
        ByteArrayOutputStream memoryBytes = new ByteArrayOutputStream();
        memory.writeSnapshot(memoryBytes);
        ByteArrayOutputStream stateBytes = new ByteArrayOutputStream();
        cold.writeState(new DataOutputStream(stateBytes));
        cold.close();
        store.down.set(false);

        MemoryCounterStore restored = MemoryCounterStore.readSnapshot(new ByteArrayInputStream(memoryBytes.toByteArray()));
        ColdGroups again = cold(restored, store);
        again.readState(new DataInputStream(new ByteArrayInputStream(stateBytes.toByteArray())));
        assertThat(again.isCold("2")).isTrue();
        waitUntil(10, () -> !again.isCold("1"));
        assertThat(days(restored, "1", "SUCCESS")).isEqualTo(3);
        again.ensureLoaded("2");
        assertThat(days(restored, "2", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void aStoreWithoutStateTakesEachGroupAsActiveAtItsNewestBucket() {
        ColdGroups cold = cold();
        write(memory, cold, "old", "SUCCESS", T0, 1);
        write(memory, cold, "new", "SUCCESS", T0 + 6 * DAY, 1);
        memory.apply(new Batch(List.of(new SeriesBatch(series("allonly", "SUCCESS"),
                List.of(new BucketWrite(Grain.ALL, 0, Parts.of(1))), T0, T0, null)), null, 0));
        clock.now = T0 + 9 * DAY;
        cold.initFrom(memory);
        assertThat(cold.active()).containsExactlyInAnyOrder("old", "new", "allonly");
        assertThat(cold.activeSince(T0 + DAY)).containsExactly("new");
        assertThat(cold.evict()).isEqualTo(1); // old; allonly has nothing in the windows: no row, gone
        assertThat(cold.isCold("old")).isTrue();
        assertThat(memory.seriesOf("user", "allonly")).isEmpty();
        assertThat(cold.active()).containsExactly("new");
    }

    @Test
    void atMostTheGroupsAskedForGoInOneRun() {
        ColdGroups cold = cold();
        for (int u = 0; u < 10; u++) {
            write(cold, Integer.toString(u), "SUCCESS", 1);
        }
        clock.now += 8 * DAY;
        assertThat(cold.evict(4)).isEqualTo(4);
        assertThat(cold.evict(4)).isEqualTo(4);
        assertThat(cold.evict(4)).isEqualTo(2);
        assertThat(cold.stats().evicted()).isEqualTo(10);
    }

    /**
     * Days of writes and reads on many threads with groups leaving and coming back, against a store that keeps
     * everything: every read inside {@link ColdGroups#reading} answers what the oracle holds.
     */
    @Test
    void readsOnManyThreadsWhileGroupsLeaveAndComeBackMatchAStoreThatKeepsEverything() throws Exception {
        ColdGroups cold = cold();
        MemoryCounterStore oracle = new MemoryCounterStore();
        SplittableRandom random = new SplittableRandom(15847);
        int users = 300;
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> mismatch = new AtomicReference<>();
        // a read compares days and minutes of one user with the oracle; both are written by the one writer below,
        // which pauses on a latch while readers compare
        ExecutorService readers = Executors.newFixedThreadPool(4);
        List<Future<?>> running = new ArrayList<>();
        Object writerLock = new Object();
        for (int r = 0; r < 4; r++) {
            int seed = r;
            running.add(readers.submit(() -> {
                SplittableRandom rr = new SplittableRandom(seed);
                while (!stop.get()) {
                    // mostly the busy users, now and then anyone (who then stays a week): as reads follow payments
                    String u = Integer.toString(rr.nextInt(50) == 0 ? rr.nextInt(users) : rr.nextInt(20));
                    sleep(1);
                    try {
                        cold.ensureLoaded(u);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    synchronized (writerLock) {
                        cold.read(u);
                        cold.reading(() -> {
                            for (String s : List.of("SUCCESS", "FAILED")) {
                                if (!cold.isCold(u) && (days(memory, u, s) != days(oracle, u, s)
                                        || minutes(memory, u, s) != minutes(oracle, u, s))) {
                                    mismatch.compareAndSet(null, "user " + u + " " + s + ": " + days(memory, u, s)
                                            + " vs " + days(oracle, u, s));
                                }
                            }
                            return null;
                        });
                    }
                }
            }));
        }
        try {
            for (int day = 0; day < 60; day++) {
                for (int i = 0; i < 400; i++) {
                    // a few users are busy every day, the rest come and go
                    int u = random.nextInt(10) < 3 ? random.nextInt(20) : random.nextInt(users);
                    if (day % 15 > 9 && u >= 20) {
                        continue; // quiet days: most leave
                    }
                    String status = random.nextBoolean() ? "SUCCESS" : "FAILED";
                    long delta = random.nextInt(5) == 0 ? -1 : 1;
                    synchronized (writerLock) {
                        clock.now += 3 * MIN;
                        write(memory, cold, Integer.toString(u), status, clock.now, delta);
                        oracle.apply(new Batch(List.of(new SeriesBatch(series(Integer.toString(u), status), List.of(
                                new BucketWrite(Grain.MINUTE, clock.now - clock.now % MIN, Parts.of(delta)),
                                new BucketWrite(Grain.DAY, clock.now - clock.now % DAY, Parts.of(delta))),
                                clock.now, clock.now, null)), null, 0));
                    }
                }
                synchronized (writerLock) {
                    clock.now = T0 + (day + 1) * DAY;
                    cold.evict();
                }
                sleep(20); // the readers' share of the day
            }
        } finally {
            stop.set(true);
            for (Future<?> f : running) {
                f.get(30, TimeUnit.SECONDS);
            }
            readers.shutdownNow();
        }
        assertThat(mismatch.get()).isNull();
        assertThat(cold.stats().evicted()).isGreaterThan(100);
        assertThat(cold.stats().loaded()).isGreaterThan(50);
        // at the end every user loaded back answers as the oracle
        waitUntil(10, () -> cold.stats().loadQueue() == 0);
        for (int u = 0; u < users; u++) {
            String user = Integer.toString(u);
            cold.ensureLoaded(user);
            for (String s : List.of("SUCCESS", "FAILED")) {
                assertThat(days(memory, user, s)).as("user %s %s", user, s).isEqualTo(days(oracle, user, s));
                assertThat(minutes(memory, user, s)).as("user %s %s", user, s).isEqualTo(minutes(oracle, user, s));
            }
        }
    }

    @Test
    void aGroupRoundTripsThroughTheCodec() {
        ColdGroups cold = cold();
        write(memory, cold, "1", "SUCCESS", T0, 3);
        write(memory, cold, "1", "PENDING", T0, 1);
        write(memory, cold, "1", "PENDING", T0 + MIN, -1);
        SeriesGroup g = memory.exportGroup("user", "1", WINDOWS);
        SeriesGroup back = GroupCodec.decode("user", "1", GroupCodec.encode(g));
        assertThat(back.label()).isEqualTo("user");
        Map<Labels, List<BucketWrite>> want = new HashMap<>();
        g.series().forEach(m -> want.put(m.labels(), m.buckets()));
        Map<Labels, List<BucketWrite>> got = new HashMap<>();
        back.series().forEach(m -> got.put(m.labels(), m.buckets()));
        assertThat(got).isEqualTo(want);
        assertThat(back.series()).allMatch(m -> m.version() == 0);
    }

    /** Day buckets of SUCCESS in a cold row handed to a read (0 for none). */
    static long rowDays(SeriesGroup row) {
        if (row == null) {
            return 0;
        }
        long sum = 0;
        for (SeriesGroup.Member m : row.series()) {
            if (m.labels().get("status").equals("SUCCESS")) {
                for (BucketWrite b : m.buckets()) {
                    if (b.grain() == Grain.DAY) {
                        sum += b.delta().net();
                    }
                }
            }
        }
        return sum;
    }

    /** A cold group "1" with 3 SUCCESS in its row, and a group "2" held. */
    private ColdGroups oneColdOneHeld() {
        ColdGroups cold = cold();
        write(cold, "1", "SUCCESS", 3);
        write(cold, "2", "SUCCESS", 1);
        clock.now += 6 * DAY;
        write(cold, "2", "SUCCESS", 1);
        clock.now += DAY + HOUR;
        assertThat(cold.evict()).isEqualTo(1);
        assertThat(cold.isCold("1")).isTrue();
        return cold;
    }

    @Test
    void whileLoadsArePausedALoadWaitsAndHoldsBackNoRead() throws Exception {
        ColdGroups cold = oneColdOneHeld();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // a snapshot: loads paused, then the memory store's read lock held for a long read
            Future<?> snapshot = pool.submit(() -> {
                cold.pauseLoads();
                try {
                    AtomicBoolean first = new AtomicBoolean(true);
                    memory.export(new io.github.skippedaga.tinytsdb.StoreSink() {
                        @Override
                        public void bucket(Labels s, Grain grain, long start, Parts parts) {
                            if (first.getAndSet(false)) {
                                holding.countDown();
                                try {
                                    release.await(20, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }

                        @Override
                        public void firstAdded(Labels s, long at) {
                        }

                        @Override
                        public void claim(String key, long expiresAtMs) {
                        }
                    });
                } finally {
                    cold.resumeLoads();
                }
                return null;
            });
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

            // the cold group is written to (its report was counted before the snapshot began): the loader is asked for
            // its row and waits for the pause
            cold.written("1");
            sleep(300);
            assertThat(cold.isCold("1")).isTrue();

            // reads go on: a held group at once, the cold one from memory + its row, exactly, still cold after
            long t = System.nanoTime();
            assertThat(cold.<Long>readGroup("2", row -> rowDays(row) + days(memory, "2", "SUCCESS"))).isEqualTo(2);
            assertThat(cold.<Long>readGroup("1", row -> rowDays(row) + days(memory, "1", "SUCCESS"))).isEqualTo(3);
            assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(Duration.ofSeconds(2));
            assertThat(cold.isCold("1")).isTrue();
            assertThat(snapshot.isDone()).isFalse();

            release.countDown();
            snapshot.get(10, TimeUnit.SECONDS);
            // the loader goes on once the pause is over: the row is added once
            waitUntil(10, () -> !cold.isCold("1"));
            assertThat(days(memory, "1", "SUCCESS")).isEqualTo(3);
            assertThat(cold.<Long>readGroup("1", row -> {
                assertThat(row).isNull();
                return days(memory, "1", "SUCCESS");
            })).isEqualTo(3);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aReadAcrossTheEndOfAPauseCountsTheRowOnce() throws Exception {
        for (int round = 0; round < 30; round++) {
            MemoryCounterStore mem = new MemoryCounterStore();
            MemoryColdStore rows = new MemoryColdStore();
            ColdGroups cold = cold(mem, rows);
            write(mem, cold, "1", "SUCCESS", clock.millis(), 3);
            write(mem, cold, "2", "SUCCESS", clock.millis(), 1);
            clock.now += 7 * DAY + HOUR;
            write(mem, cold, "2", "SUCCESS", clock.millis(), 1);
            assertThat(cold.evict()).isEqualTo(1);
            write(mem, cold, "1", "SUCCESS", clock.millis(), 1); // the loader wants it now

            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                CountDownLatch paused = new CountDownLatch(1);
                Future<?> pauser = pool.submit(() -> {
                    cold.pauseLoads();
                    paused.countDown();
                    sleep(20);
                    cold.resumeLoads();
                    return null;
                });
                assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
                List<Future<Long>> reads = new ArrayList<>();
                for (int r = 0; r < 3; r++) {
                    reads.add(pool.submit(() -> {
                        long worst = 4;
                        for (int i = 0; i < 200; i++) {
                            long v = cold.<Long>readGroup("1", row -> rowDays(row) + days(mem, "1", "SUCCESS"));
                            if (v != 4) {
                                worst = v;
                            }
                        }
                        return worst;
                    }));
                }
                pauser.get(10, TimeUnit.SECONDS);
                for (Future<Long> f : reads) {
                    assertThat(f.get(10, TimeUnit.SECONDS)).as("round " + round).isEqualTo(4);
                }
                waitUntil(10, () -> !cold.isCold("1"));
                assertThat(days(mem, "1", "SUCCESS")).isEqualTo(4);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
