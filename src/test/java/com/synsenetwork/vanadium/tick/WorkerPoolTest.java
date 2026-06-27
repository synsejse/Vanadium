package com.synsenetwork.vanadium.tick;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    // Contract change: a multi-task wave still runs at least one task on a pool worker, and the
    // main thread never reports as a worker. The gate forces both hands to be busy at once so the
    // helper provably runs one task (otherwise the caller could drain both before the helper polls).
    @Test void poolThreadsReportAsWorkers() throws Exception {
        WorkerPool pool = new WorkerPool(2);
        AtomicBoolean anyOnWorker = new AtomicBoolean(false);
        CyclicBarrier gate = new CyclicBarrier(2);
        Runnable t = () -> {
            if (WorkerPool.isWorkerThread()) anyOnWorker.set(true);
            try { gate.await(2, TimeUnit.SECONDS); }
            catch (Exception e) { throw new RuntimeException(e); }
        };
        pool.runWave(List.of(t, t));
        assertTrue(anyOnWorker.get(), "at least one task should run on a pool worker");
        assertFalse(WorkerPool.isWorkerThread(), "main thread must not report as a worker");
        pool.shutdown();
    }

    @Test void singleTaskRunsOnCaller() {
        WorkerPool pool = new WorkerPool(4);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicBoolean wasWorker = new AtomicBoolean(true);
        pool.runWave(List.of(() -> {
            ranOn.set(Thread.currentThread());
            wasWorker.set(WorkerPool.isWorkerThread());
        }));
        assertSame(Thread.currentThread(), ranOn.get(), "single-task wave must run on the caller");
        assertFalse(wasWorker.get(), "caller must not report as a worker");
        pool.shutdown();
    }

    // Deterministic: with one worker, the wave can only complete if the caller runs the 2nd task.
    // Both tasks gate on a 2-party barrier, so the lone worker cannot drain both serially.
    @Test void callerParticipates() {
        WorkerPool pool = new WorkerPool(1);
        Set<Thread> ran = ConcurrentHashMap.newKeySet();
        CyclicBarrier gate = new CyclicBarrier(2);
        Runnable t = () -> {
            ran.add(Thread.currentThread());
            try { gate.await(2, TimeUnit.SECONDS); }
            catch (Exception e) { throw new RuntimeException(e); }
        };
        // assertTimeoutPreemptively runs its lambda in a separate thread; capture that thread so
        // we can verify the runWave caller (not the JUnit test thread) participated as a worker.
        AtomicReference<Thread> callerRef = new AtomicReference<>();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            callerRef.set(Thread.currentThread());
            pool.runWave(List.of(t, t));
        });
        assertTrue(ran.contains(callerRef.get()), "caller must execute a task, not just block");
        pool.shutdown();
    }

    @Test void errorPropagatesAndOthersComplete() {
        WorkerPool pool = new WorkerPool(4);
        AtomicInteger completed = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) tasks.add(completed::incrementAndGet);
        tasks.add(25, () -> { throw new IllegalStateException("boom"); });
        assertThrows(RuntimeException.class, () -> pool.runWave(tasks));
        assertEquals(50, completed.get(), "all non-throwing tasks must still run");
        pool.shutdown();
    }

    @Test void highContentionAllRun() {
        WorkerPool pool = new WorkerPool(4);
        AtomicInteger counter = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) tasks.add(counter::incrementAndGet);
        pool.runWave(tasks);
        assertEquals(10_000, counter.get());
        pool.shutdown();
    }
}
