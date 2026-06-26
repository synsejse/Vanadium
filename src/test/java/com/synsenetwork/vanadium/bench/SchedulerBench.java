package com.synsenetwork.vanadium.bench;

import com.sun.management.ThreadMXBean;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.tick.TickScheduler;
import com.synsenetwork.vanadium.tick.WorkerPool;

import java.lang.management.ManagementFactory;

/** Standalone micro-benchmark of the tick scheduler hot path (no Minecraft). Run via ./gradlew benchScheduler. */
public final class SchedulerBench {

    public static void main(String[] args) {
        int threads = Runtime.getRuntime().availableProcessors();
        int cellSize = 4;
        int radius = 16;                 // ticking area is (2*radius)^2 chunks
        int warmup = 2000;
        int measured = 5000;

        WorkerPool pool = new WorkerPool(threads);
        TickScheduler scheduler = new TickScheduler(pool, cellSize);
        Runnable noop = () -> { };       // measure scheduler overhead, not task cost

        for (int t = 0; t < warmup; t++) {
            tick(scheduler, radius, noop);
        }

        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        // Snapshot the (now-warmed) thread set once so the before/after totals cover the same threads.
        long[] threadIds = threadBean.getAllThreadIds();
        long allocBefore = totalAllocatedBytes(threadBean, threadIds);
        long timeBefore = System.nanoTime();
        for (int t = 0; t < measured; t++) {
            tick(scheduler, radius, noop);
        }
        long elapsedNs = System.nanoTime() - timeBefore;
        long allocBytes = totalAllocatedBytes(threadBean, threadIds) - allocBefore;

        int areaChunks = (2 * radius) * (2 * radius);
        System.out.printf("threads=%d cellSize=%d areaChunks=%d ticks=%d%n", threads, cellSize, areaChunks, measured);
        System.out.printf("  alloc/tick = %d bytes%n", allocBytes / measured);
        System.out.printf("  time/tick  = %.1f us%n", elapsedNs / 1000.0 / measured);
        pool.shutdown();
    }

    private static void tick(TickScheduler scheduler, int radius, Runnable task) {
        scheduler.begin(Stage.CHUNK);
        for (int cx = -radius; cx < radius; cx++) {
            for (int cz = -radius; cz < radius; cz++) {
                scheduler.enqueue(Stage.CHUNK, cx, cz, task);
            }
        }
        scheduler.run(Stage.CHUNK);
    }

    private static long totalAllocatedBytes(ThreadMXBean bean, long[] ids) {
        long sum = 0;
        for (long id : ids) {
            long bytes = bean.getThreadAllocatedBytes(id);
            if (bytes > 0) {
                sum += bytes;
            }
        }
        return sum;
    }
}
