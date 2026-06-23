package com.synsenetwork.vanadium.tick;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerPoolTest {
    @Test void runWaveRunsEveryTask() {
        WorkerPool pool = new WorkerPool(4);
        AtomicInteger counter = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) tasks.add(counter::incrementAndGet);
        pool.runWave(tasks);
        assertEquals(200, counter.get());
        pool.shutdown();
    }

    @Test void runWaveBlocksUntilDone() {
        WorkerPool pool = new WorkerPool(2);
        AtomicBoolean done = new AtomicBoolean(false);
        pool.runWave(List.of(() -> {
            try { Thread.sleep(40); } catch (InterruptedException ignored) {}
            done.set(true);
        }));
        assertTrue(done.get(), "runWave returned before its task finished");
        pool.shutdown();
    }

    @Test void runWavePropagatesTaskFailure() {
        WorkerPool pool = new WorkerPool(2);
        assertThrows(RuntimeException.class,
            () -> pool.runWave(List.of(() -> { throw new IllegalStateException("boom"); })));
        pool.shutdown();
    }

    @Test void emptyWaveIsANoop() {
        WorkerPool pool = new WorkerPool(2);
        pool.runWave(List.of());
        pool.shutdown();
    }

    @Test void poolThreadsReportAsWorkers() {
        WorkerPool pool = new WorkerPool(1);
        AtomicBoolean onWorker = new AtomicBoolean(false);
        pool.runWave(List.of(() -> onWorker.set(WorkerPool.isWorkerThread())));
        assertTrue(onWorker.get());
        assertFalse(WorkerPool.isWorkerThread(), "main thread must not report as a worker");
        pool.shutdown();
    }
}
