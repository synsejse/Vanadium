package com.synsenetwork.vanadium.tick;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** A fixed pool of worker threads that runs a batch of tasks and waits for all of them. */
public final class WorkerPool {
    private final ExecutorService executor;

    public WorkerPool(int threads) {
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads), new WorkerThreadFactory());
    }

    /** Runs every task in parallel and blocks until all have finished (a barrier). */
    public void runWave(Collection<? extends Runnable> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        List<Future<?>> futures = new ArrayList<>(tasks.size());
        for (Runnable task : tasks) {
            futures.add(executor.submit(task));
        }
        for (Future<?> future : futures) {
            await(future);
        }
    }

    private static void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new RuntimeException("Vanadium worker task failed", e.getCause());
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
