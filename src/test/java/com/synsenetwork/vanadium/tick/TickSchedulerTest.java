package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TickSchedulerTest {
    @Test void runsEveryEnqueuedTaskExactlyOnce() {
        WorkerPool pool = new WorkerPool(4);
        TickScheduler scheduler = new TickScheduler(pool, 8);
        ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
        scheduler.begin(Stage.CHUNK);
        for (int cx = 0; cx < 16; cx++)
            for (int cz = 0; cz < 16; cz++) {
                int fx = cx, fz = cz;
                scheduler.enqueue(Stage.CHUNK, fx, fz,
                    () -> counts.merge(fx + "," + fz, 1, Integer::sum), null);
            }
        scheduler.run(Stage.CHUNK);
        assertEquals(256, counts.size());
        assertTrue(counts.values().stream().allMatch(v -> v == 1));
        pool.close();
    }

    @Test void runClearsStageSoNextTickStartsFresh() {
        WorkerPool pool = new WorkerPool(2);
        TickScheduler scheduler = new TickScheduler(pool, 8);
        int[] runs = {0};
        scheduler.begin(Stage.CHUNK);
        scheduler.enqueue(Stage.CHUNK, 0, 0, () -> runs[0]++, null);
        scheduler.run(Stage.CHUNK);
        scheduler.run(Stage.CHUNK); // nothing queued this time
        assertEquals(1, runs[0]);
        pool.close();
    }

    @Test void neighborhoodWaveNeverRunsAdjacentCellsTogether() {
        WorkerPool pool = new WorkerPool(8);
        TickScheduler scheduler = new TickScheduler(pool, 1);
        int size = 12;
        AtomicInteger[][] running = new AtomicInteger[size][size];
        for (AtomicInteger[] row : running) for (int i = 0; i < size; i++) row[i] = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        for (int round = 0; round < 40; round++) {
            scheduler.begin(Stage.ENTITY);
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    int fx = x, fz = z, tasks = 1 + (x * 7 + z * 3 + round) % 4;
                    for (int t = 0; t < tasks; t++) {
                        scheduler.enqueue(Stage.ENTITY, fx, fz, () -> {
                            running[fx][fz].incrementAndGet();
                            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                                int nx = fx + dx, nz = fz + dz;
                                if ((dx != 0 || dz != 0) && nx >= 0 && nz >= 0 && nx < size && nz < size
                                        && running[nx][nz].get() > 0) violations.incrementAndGet();
                            }
                            Thread.onSpinWait();
                            executed.incrementAndGet();
                            running[fx][fz].decrementAndGet();
                        }, null);
                    }
                }
            }
            scheduler.run(Stage.ENTITY);
        }
        int expected = 0;
        for (int round = 0; round < 40; round++)
            for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) expected += 1 + (x * 7 + z * 3 + round) % 4;
        assertEquals(expected, executed.get());
        assertEquals(0, violations.get(), "adjacent cells overlapped");
        pool.close();
    }

    @Test void neighborhoodWaveKeepsInsertionOrderWithinCells() {
        WorkerPool pool = new WorkerPool(4);
        TickScheduler scheduler = new TickScheduler(pool, 2);
        List<List<Integer>> seen = new ArrayList<>();
        for (int c = 0; c < 9; c++) seen.add(Collections.synchronizedList(new ArrayList<>()));
        scheduler.begin(Stage.CHUNK);
        for (int i = 0; i < 50; i++) {
            for (int c = 0; c < 9; c++) {
                int cell = c, value = i;
                scheduler.enqueue(Stage.CHUNK, (c % 3) * 2, (c / 3) * 2, () -> seen.get(cell).add(value), null);
            }
        }
        scheduler.run(Stage.CHUNK);
        for (List<Integer> values : seen) {
            for (int i = 0; i < 50; i++) assertEquals(i, values.get(i));
        }
        pool.close();
    }

    @Test void neighborhoodWaveFinishesOtherCellsAndPropagatesFailure() {
        WorkerPool pool = new WorkerPool(4);
        TickScheduler scheduler = new TickScheduler(pool, 1);
        AtomicInteger completed = new AtomicInteger();
        IllegalStateException boom = new IllegalStateException("boom");
        scheduler.begin(Stage.CHUNK);
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) {
            boolean fail = x == 2 && z == 3;
            scheduler.enqueue(Stage.CHUNK, x, z, () -> {
                if (fail) throw boom;
                completed.incrementAndGet();
            }, null);
        }
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> scheduler.run(Stage.CHUNK));
        assertSame(boom, thrown.getCause());
        assertEquals(35, completed.get());
        // The stage resets even after a failure.
        int[] runs = {0};
        scheduler.begin(Stage.CHUNK);
        scheduler.enqueue(Stage.CHUNK, 0, 0, () -> runs[0]++, null);
        scheduler.run(Stage.CHUNK);
        assertEquals(1, runs[0]);
        pool.close();
    }
}
