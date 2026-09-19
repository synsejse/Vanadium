package com.synsenetwork.vanadium.tick;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TickProfileTest {
    @Test void percentilesUseNearestRank() {
        assertEquals(50, TickProfile.Samples.percentile(LongStream.rangeClosed(1, 100).toArray(), 50));
        assertEquals(95, TickProfile.Samples.percentile(LongStream.rangeClosed(1, 100).toArray(), 95));
        assertEquals(99, TickProfile.Samples.percentile(LongStream.rangeClosed(1, 100).toArray(), 99));
        assertEquals(7, TickProfile.Samples.percentile(new long[]{7}, 99));
    }

    @Test void captureCountsCellsTasksAndWaitWithoutChangingDispatch() {
        WorkerPool pool = new WorkerPool(2);
        try {
            TickScheduler scheduler = new TickScheduler(pool, 2);
            TickProfile profile = new TickProfile();
            AtomicInteger calls = new AtomicInteger();
            scheduler.setProfile(profile);
            scheduler.setDimension("test:world");
            scheduler.begin(Stage.ENTITY);
            scheduler.enqueue(Stage.ENTITY, 0, 0, calls::incrementAndGet, null);
            scheduler.enqueue(Stage.ENTITY, 0, 0, calls::incrementAndGet, null);
            scheduler.enqueue(Stage.ENTITY, 4, 0, calls::incrementAndGet, null);
            scheduler.run(Stage.ENTITY);
            assertEquals(3, calls.get());
            scheduler.finishWorld();
            String report = profile.report();
            assertTrue(report.contains("waves=1 cells=2 tasks=3; tasks/cell avg=1.5 max=2"), report);
            assertTrue(report.contains("cell execution n=2"), report);
            assertTrue(report.contains("test:world / ENTITY preparation: n=1"), report);
            assertTrue(report.contains("test:world / ENTITY execution: n=1"), report);
            assertTrue(report.contains("test:world / world total: n=1"), report);
            scheduler.setProfile(null);
            scheduler.begin(Stage.ENTITY);
            scheduler.enqueue(Stage.ENTITY, 0, 0, calls::incrementAndGet, null);
            scheduler.run(Stage.ENTITY);
            assertEquals(4, calls.get());
            assertEquals(report, profile.report());
        } finally {
            pool.close();
        }
    }

    @Test void sampleStorageIsBoundedAndTruncationIsReported() {
        TickProfile profile = new TickProfile();
        for (int i = 0; i < TickProfile.MAX_SAMPLES + 1; i++) profile.recordTick(1_000_000);
        assertTrue(profile.isFull());
        assertTrue(profile.report().contains("percentiles limited to first 65536"));
    }

    @Test void waitCallbackRunsAfterCompletionEvenWhenATaskFails() {
        WorkerPool pool = new WorkerPool(2);
        AtomicInteger finished = new AtomicInteger();
        AtomicLong waited = new AtomicLong(-1);
        try {
            assertThrows(RuntimeException.class, () -> pool.runWave(List.of(
                    () -> { finished.incrementAndGet(); throw new IllegalStateException("fixture"); },
                    finished::incrementAndGet), nanos -> {
                assertEquals(2, finished.get());
                waited.set(nanos);
            }));
            assertTrue(waited.get() >= 0);
        } finally {
            pool.close();
        }
    }
}
