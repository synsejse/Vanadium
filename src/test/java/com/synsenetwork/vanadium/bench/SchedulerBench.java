package com.synsenetwork.vanadium.bench;

import com.sun.management.ThreadMXBean;
import com.synsenetwork.vanadium.tick.Stage;
import com.synsenetwork.vanadium.tick.TickScheduler;
import com.synsenetwork.vanadium.tick.WorkerPool;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.LongAdder;

/** Standalone micro-benchmark of the tick scheduler hot path (no Minecraft). Run via ./gradlew benchScheduler. */
public final class SchedulerBench {

    /** Consumed at the end so the loaded task's work isn't eliminated by the JIT. */
    private static final LongAdder SINK = new LongAdder();
    /** ~a few microseconds of CPU per cell, so loaded waves are barrier-bound (caller-runs visible). */
    private static final int SPIN = 200;

    public static void main(String[] args) {
        int threads = Runtime.getRuntime().availableProcessors();
        int cellSize = 4;
        int radius = 16;                 // ticking area is (2*radius)^2 chunks
        int warmup = 2000;
        int measured = 5000;

        WorkerPool pool = new WorkerPool(threads);
        TickScheduler scheduler = new TickScheduler(pool, cellSize);
        Runnable noop = () -> { };        // measures scheduler overhead, not task cost
        Runnable loaded = SchedulerBench::burn;

        for (int t = 0; t < warmup; t++) {
            tick(scheduler, radius, noop);
            tick(scheduler, radius, loaded);
        }

        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        // Snapshot the (now-warmed) thread set once so the before/after totals cover the same threads.
        long[] threadIds = threadBean.getAllThreadIds();
        long allocBefore = totalAllocatedBytes(threadBean, threadIds);
        long noopBefore = System.nanoTime();
        for (int t = 0; t < measured; t++) {
            tick(scheduler, radius, noop);
        }
        long noopNs = System.nanoTime() - noopBefore;
        long allocBytes = totalAllocatedBytes(threadBean, threadIds) - allocBefore;

        long loadedBefore = System.nanoTime();
        for (int t = 0; t < measured; t++) {
            tick(scheduler, radius, loaded);
        }
        long loadedNs = System.nanoTime() - loadedBefore;

        int areaChunks = (2 * radius) * (2 * radius);
        System.out.printf("threads=%d cellSize=%d areaChunks=%d ticks=%d spin=%d%n",
                threads, cellSize, areaChunks, measured, SPIN);
        System.out.printf("  noop   alloc/tick = %d bytes%n", allocBytes / measured);
        System.out.printf("  noop   time/tick  = %.1f us%n", noopNs / 1000.0 / measured);
        System.out.printf("  loaded time/tick  = %.1f us  (sink=%d)%n",
                loadedNs / 1000.0 / measured, SINK.sum());
        pool.shutdown();
    }

    private static void burn() {
        long acc = 0;
        for (int i = 0; i < SPIN; i++) {
            acc += (i * 31L + 7L) ^ (acc >> 1);
        }
        SINK.add(acc);
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
