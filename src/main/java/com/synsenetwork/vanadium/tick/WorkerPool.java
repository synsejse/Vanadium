package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;

/**
 * A fixed set of persistent worker threads that runs a batch of tasks and waits for all of them.
 *
 * <p>In {@link #runWave}, the calling thread participates as a worker and
 * claims tasks by index alongside the pool instead of parking idle, waiting only for the tail
 * (tasks already in flight on other threads when every index has been claimed).
 *
 * <p>Dispatch publishes the wave through one volatile field. Idle workers spin for a short,
 * configurable window before parking, so the back-to-back waves of a tick (one per stage and world)
 * reach running threads without futex wake-ups. Parked workers are unparked individually only when
 * a wave needs them; low-numbered workers take small waves, keeping the rest asleep. Tasks are
 * claimed in small chunks and each participant reports its completed count once, so a wave does
 * not bounce a shared counter between sockets for every task.
 *
 * <p><b>Invariant this relies on:</b> tick-path locking is UNCONDITIONAL — never gated on thread
 * identity (see {@link #isWorkerThread()}). A cell therefore runs identically whether a worker or
 * the calling thread executes it.
 */
public final class WorkerPool implements AutoCloseable {
    /** Idle spin before a worker parks; covers the gaps between a tick's waves. */
    private static final long WORKER_SPIN_NANOS = Long.getLong("vanadium.workerSpinMicros", 100) * 1_000L;
    /** Caller spin while waiting for the wave tail before parking. */
    private static final long CALLER_SPIN_NANOS = Long.getLong("vanadium.callerSpinMicros", 200) * 1_000L;

    private final WorkerThread[] workers;
    private volatile Wave current;
    private volatile boolean closed;

    public WorkerPool(int threads) {
        int count = Math.max(1, threads);
        this.workers = new WorkerThread[count];
        for (int i = 0; i < count; i++) {
            workers[i] = new WorkerThread(this, i);
        }
        for (WorkerThread worker : workers) {
            worker.start();
        }
    }

    public int workerCount() {
        return workers.length;
    }

    /**
     * Runs every task and blocks until all have finished (a full barrier). The calling thread
     * claims tasks alongside the pool rather than blocking idle; a single-task wave
     * runs inline on the caller with no dispatch. Interruption does not cancel a wave: the
     * caller waits for all tasks and then restores its interrupt flag.
     *
     * <p>Do not change the collection's contents until this method returns. Random-access
     * lists are read directly; other collections are copied to an indexed list.
     *
     * <p>Only one thread may run waves on a pool at a time (the server thread).
     *
     * @param callerWait optional profiling callback for caller tail-wait time; null disables timing
     */
    public void runWave(Collection<? extends Runnable> tasks, LongConsumer callerWait) {
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
        boolean rejected = closed;
        // At most n participants, including the caller.
        int helpers = rejected ? 0 : Math.min(workers.length, n - 1);
        Wave wave = new Wave(indexed, helpers + 1, Thread.currentThread());
        if (helpers > 0) {
            current = wave;
            for (int i = 0; i < helpers; i++) {
                workers[i].wake();
            }
        }
        wave.participate();
        long waitStart = callerWait != null ? System.nanoTime() : 0;
        try {
            wave.awaitCompletion();
        } finally {
            if (callerWait != null) callerWait.accept(System.nanoTime() - waitStart);
        }
        if (rejected) {
            throw new RejectedExecutionException("Vanadium worker pool is closed");
        }
    }

    private static final class Wave {
        private final List<? extends Runnable> tasks;
        private final int taskCount;
        private final int chunk;
        private final int helpers;
        private final Thread waiter;
        private final AtomicInteger next = new AtomicInteger();
        // Keep the claim cursor and the completion counter on different cache lines.
        @SuppressWarnings("unused") private long p0, p1, p2, p3, p4, p5, p6, p7;
        private final AtomicInteger remaining;
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private volatile boolean waiterParked;

        Wave(List<? extends Runnable> tasks, int participants, Thread waiter) {
            this.tasks = tasks;
            this.taskCount = tasks.size();
            this.helpers = participants - 1;
            // Chunks only when tasks greatly outnumber participants; cells stay individually balanced.
            this.chunk = Math.max(1, taskCount / (participants * 8));
            this.remaining = new AtomicInteger(taskCount);
            this.waiter = waiter;
        }

        void participate() {
            int done = 0;
            int start;
            // A late helper must use the saved count, never read a list already reused by the caller.
            while ((start = next.getAndAdd(chunk)) < taskCount) {
                int end = Math.min(taskCount, start + chunk);
                for (int i = start; i < end; i++) {
                    try {
                        tasks.get(i).run();
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    }
                }
                done += end - start;
            }
            if (done > 0 && remaining.addAndGet(-done) == 0 && waiterParked) {
                LockSupport.unpark(waiter);
            }
        }

        void awaitCompletion() {
            boolean interrupted = false;
            long deadline = System.nanoTime() + CALLER_SPIN_NANOS;
            while (remaining.get() != 0) {
                if (System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                    continue;
                }
                waiterParked = true;
                if (remaining.get() != 0) {
                    LockSupport.park(this);
                }
                waiterParked = false;
                if (Thread.interrupted()) {
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

    /** Called after the last wave; finishes late helpers and releases the pool's threads. */
    @Override
    public void close() {
        closed = true;
        for (WorkerThread worker : workers) {
            LockSupport.unpark(worker);
        }
        boolean interrupted = false;
        for (WorkerThread worker : workers) {
            if (worker == Thread.currentThread()) continue;
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** True when called from a thread owned by a WorkerPool. */
    public static boolean isWorkerThread() {
        return Thread.currentThread() instanceof WorkerThread;
    }

    private static final class WorkerThread extends Thread {
        private final WorkerPool pool;
        private final int index;
        private volatile boolean parked;

        WorkerThread(WorkerPool pool, int index) {
            super("Vanadium-Worker-" + (index + 1));
            this.pool = pool;
            this.index = index;
            setDaemon(true);
        }

        /** Dekker pairing with {@link #run}: the wave is published before {@code parked} is read. */
        void wake() {
            if (parked) {
                LockSupport.unpark(this);
            }
        }

        @Override
        public void run() {
            Wave seen = null;
            long deadline = System.nanoTime() + WORKER_SPIN_NANOS;
            while (true) {
                Wave wave = pool.current;
                if (wave != seen) {
                    seen = wave;
                    if (index < wave.helpers) {
                        wave.participate();
                        deadline = System.nanoTime() + WORKER_SPIN_NANOS;
                    }
                    continue;
                }
                if (pool.closed) {
                    return;
                }
                if (System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                    continue;
                }
                parked = true;
                if (pool.current == seen && !pool.closed) {
                    LockSupport.park(pool);
                }
                parked = false;
                Thread.interrupted(); // Pool threads are never interrupted for control; ignore strays.
            }
        }
    }
}
