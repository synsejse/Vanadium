package com.synsenetwork.vanadium.tick;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkerPoolTest {
    @Test void interruptedCallerStillWaitsForWorkers() throws Exception {
        checkInterruptedBarrier(false, false);
    }

    @Test void alreadyInterruptedCallerStillWaitsForWorkers() throws Exception {
        checkInterruptedBarrier(true, false);
    }

    @Test void interruptionPreservesBarrierAndTaskFailure() throws Exception {
        checkInterruptedBarrier(false, true);
    }

    private static void checkInterruptedBarrier(boolean interruptBeforeWait, boolean failTask) throws Exception {
        WorkerPool pool = new WorkerPool(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch callerTaskFinished = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicBoolean workerFinished = new AtomicBoolean();
        AtomicBoolean finishedAtReturn = new AtomicBoolean();
        AtomicBoolean interruptedAtReturn = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        IllegalStateException taskFailure = new IllegalStateException("task failed");
        Runnable task = () -> {
            if (WorkerPool.isWorkerThread()) {
                workerStarted.countDown();
                await(releaseWorker);
                workerFinished.set(true);
            } else {
                await(workerStarted);
                if (interruptBeforeWait) Thread.currentThread().interrupt();
                callerTaskFinished.countDown();
                if (failTask) throw taskFailure;
            }
        };
        Thread caller = new Thread(() -> {
            try {
                pool.runWave(List.of(task, task));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                finishedAtReturn.set(workerFinished.get());
                interruptedAtReturn.set(Thread.currentThread().isInterrupted());
                returned.countDown();
            }
        }, "wave-test-caller");
        caller.setDaemon(true);
        try {
            caller.start();
            assertTrue(callerTaskFinished.await(5, TimeUnit.SECONDS));
            if (!interruptBeforeWait) {
                // The caller has left its task and parked at the wave barrier.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (caller.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertEquals(Thread.State.WAITING, caller.getState());
                caller.interrupt();
            }
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS),
                    "interruption must not let the caller leave a worker running");
            releaseWorker.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            assertTrue(finishedAtReturn.get());
            assertTrue(interruptedAtReturn.get(), "restore the interrupt flag after the barrier");
            if (failTask) {
                assertInstanceOf(RuntimeException.class, failure.get());
                assertSame(taskFailure, failure.get().getCause());
            } else {
                assertNull(failure.get());
            }
        } finally {
            releaseWorker.countDown();
            caller.join(5000);
            pool.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "test gate timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test void taskListsCanBeClearedAndReusedAfterEachWave() {
        WorkerPool pool = new WorkerPool(4);
        List<Runnable> tasks = new ArrayList<>();
        AtomicInteger completed = new AtomicInteger();
        int expected = 0;
        try {
            for (int wave = 0; wave < 500; wave++) {
                int count = wave % 2 == 0 ? 2 : 64;
                for (int i = 0; i < count; i++) tasks.add(completed::incrementAndGet);
                pool.runWave(tasks);
                tasks.clear();
                expected += count;
                assertEquals(expected, completed.get());
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test void supportsSequentialListsAndOtherCollections() {
        WorkerPool pool = new WorkerPool(2);
        AtomicInteger completed = new AtomicInteger();
        List<Runnable> tasks = List.of(completed::incrementAndGet, completed::incrementAndGet);
        try {
            pool.runWave(new LinkedList<>(tasks));
            pool.runWave(new LinkedHashSet<>(tasks));
            assertEquals(4, completed.get());
        } finally {
            pool.shutdown();
        }
    }

    @Test void runWaveRunsEveryTask() {
        WorkerPool pool = new WorkerPool(4);
        AtomicIntegerArray visits = new AtomicIntegerArray(200);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < visits.length(); i++) {
            int index = i;
            tasks.add(() -> visits.incrementAndGet(index));
        }
        pool.runWave(tasks);
        for (int i = 0; i < visits.length(); i++) assertEquals(1, visits.get(i));
        pool.shutdown();
    }

    @Test void submissionFailureFinishesWaveBeforePropagating() {
        WorkerPool pool = new WorkerPool(1);
        AtomicInteger completed = new AtomicInteger();
        pool.shutdown();
        assertThrows(RejectedExecutionException.class,
                () -> pool.runWave(List.of(completed::incrementAndGet, completed::incrementAndGet)));
        assertEquals(2, completed.get());
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
