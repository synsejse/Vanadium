package com.synsenetwork.vanadium.tick;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

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
                    () -> counts.merge(fx + "," + fz, 1, Integer::sum));
            }
        scheduler.run(Stage.CHUNK);
        assertEquals(256, counts.size());
        assertTrue(counts.values().stream().allMatch(v -> v == 1));
        pool.shutdown();
    }

    @Test void colorsRunInOrderSeparatedByBarriers() {
        WorkerPool pool = new WorkerPool(4);
        TickScheduler scheduler = new TickScheduler(pool, 1); // cellSize 1 => one cell per chunk
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        scheduler.begin(Stage.ENTITY);
        for (int x = 0; x < 4; x++)
            for (int z = 0; z < 4; z++) {
                int color = (x % 2) + 2 * (z % 2);
                scheduler.enqueue(Stage.ENTITY, x, z, () -> {
                    try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                    order.add(color);
                });
            }
        scheduler.run(Stage.ENTITY);
        for (int i = 1; i < order.size(); i++) {
            assertTrue(order.get(i) >= order.get(i - 1),
                "colors not separated by a barrier: " + order);
        }
        pool.shutdown();
    }

    @Test void runClearsStageSoNextTickStartsFresh() {
        WorkerPool pool = new WorkerPool(2);
        TickScheduler scheduler = new TickScheduler(pool, 8);
        int[] runs = {0};
        scheduler.begin(Stage.CHUNK);
        scheduler.enqueue(Stage.CHUNK, 0, 0, () -> runs[0]++);
        scheduler.run(Stage.CHUNK);
        scheduler.run(Stage.CHUNK); // nothing queued this time
        assertEquals(1, runs[0]);
        pool.shutdown();
    }
}
