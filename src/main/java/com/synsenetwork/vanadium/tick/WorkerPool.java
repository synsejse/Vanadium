package com.synsenetwork.vanadium.tick;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A fixed pool of worker threads that runs a batch of tasks and waits for all of them.
 *
 * <p>In {@link #runWave}, the calling thread participates as a worker and
 * claims tasks by index alongside the pool instead of parking idle, waiting only for the tail
 * (tasks already in flight on other threads when every index has been claimed).
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

    public int workerCount() {
        return workerCount;
    }

    /**
     * Runs every task and blocks until all have finished (a full barrier). The calling thread
     * claims tasks alongside the pool rather than blocking idle; a single-task wave
     * runs inline on the caller with no dispatch. Interruption does not cancel a wave: the
     * caller waits for all tasks and then restores its interrupt flag.
     *
     * <p>Do not change the collection's contents until this method returns. Random-access
     * lists are read directly; other collections are copied to an indexed list.
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

        List<? extends Runnable> indexed = tasks instanceof List<? extends Runnable> list
                && tasks instanceof RandomAccess ? list : new ArrayList<>(tasks);
        Wave wave = new Wave(indexed);
        int helpers = Math.min(workerCount, n - 1); // At most n participants, including the caller.
        try {
            for (int i = 0; i < helpers; i++) {
                executor.execute(wave);
            }
        } finally {
            // Even if submission fails, finish all tasks, including those claimed by helpers.
            wave.run();
            wave.awaitCompletion();
        }
    }

    private static final class Wave implements Runnable {
        private final List<? extends Runnable> tasks;
        private final int taskCount;
        private final AtomicInteger next = new AtomicInteger();
        private final AtomicInteger remaining;
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        Wave(List<? extends Runnable> tasks) {
            this.tasks = tasks;
            this.taskCount = tasks.size();
            this.remaining = new AtomicInteger(taskCount);
        }

        @Override
        public void run() {
            int index;
            // A late helper must use the saved count, never read a list already reused by the caller.
            while ((index = next.getAndIncrement()) < taskCount) {
                try {
                    tasks.get(index).run();
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        done.countDown();
                    }
                }
            }
        }

        void awaitCompletion() {
            boolean interrupted = false;
            while (true) {
                try {
                    done.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();

            Throwable failure = error.get();
            if (failure != null) {
                throw new RuntimeException("Vanadium worker task failed", failure);
            }
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
