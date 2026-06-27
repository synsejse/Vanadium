package com.synsenetwork.vanadium.tick;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A fixed pool of worker threads that runs a batch of tasks and waits for all of them.
 *
 * <p>{@link #runWave} is a work-stealing barrier: the calling thread participates as a worker and
 * drains the shared queue alongside the pool instead of parking idle, waiting only for the tail
 * (tasks already in flight on other threads when the queue runs dry).
 *
 * <p><b>Invariant this relies on:</b> tick-path locking is UNCONDITIONAL — never gated on thread
 * identity (see {@link #isWorkerThread()}). A cell therefore runs identically whether a worker or
 * the calling thread executes it. If tick-path locking ever becomes conditional on thread identity,
 * revert to a submit-and-block barrier.
 */
public final class WorkerPool {
    private final ExecutorService executor;
    private final int workerCount;

    public WorkerPool(int threads) {
        this.workerCount = Math.max(1, threads);
        this.executor = Executors.newFixedThreadPool(this.workerCount, new WorkerThreadFactory());
    }

    /**
     * Runs every task and blocks until all have finished (a full barrier). The calling thread
     * drains the shared queue alongside the pool rather than blocking idle; a single-task wave
     * runs inline on the caller with no dispatch.
     */
    public void runWave(Collection<? extends Runnable> tasks) {
        int n = tasks.size();
        if (n == 0) {
            return;
        }
        if (n == 1) {
            tasks.iterator().next().run();
            return;
        }

        Queue<Runnable> queue = new ConcurrentLinkedQueue<>(tasks);
        AtomicInteger remaining = new AtomicInteger(n);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        int helpers = Math.min(workerCount, n - 1); // the caller is the n-th hand
        for (int i = 0; i < helpers; i++) {
            executor.execute(() -> drain(queue, remaining, done, error));
        }
        drain(queue, remaining, done, error);
        awaitLatch(done);

        Throwable failure = error.get();
        if (failure != null) {
            throw new RuntimeException("Vanadium worker task failed", failure);
        }
    }

    private static void drain(Queue<Runnable> queue, AtomicInteger remaining,
                              CountDownLatch done, AtomicReference<Throwable> error) {
        Runnable task;
        while ((task = queue.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            } finally {
                if (remaining.decrementAndGet() == 0) {
                    done.countDown();
                }
            }
        }
    }

    private static void awaitLatch(CountDownLatch done) {
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** True when called from a thread owned by a WorkerPool. */
    public static boolean isWorkerThread() {
        return Thread.currentThread() instanceof WorkerThread;
    }

    private static final class WorkerThread extends Thread {
        WorkerThread(Runnable target, String name) {
            super(target, name);
            setDaemon(true);
        }
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(@NotNull Runnable runnable) {
            return new WorkerThread(runnable, "Vanadium-Worker-" + counter.incrementAndGet());
        }
    }
}
