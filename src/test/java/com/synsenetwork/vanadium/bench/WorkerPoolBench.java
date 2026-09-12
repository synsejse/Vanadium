package com.synsenetwork.vanadium.bench;

import com.sun.management.ThreadMXBean;
import com.synsenetwork.vanadium.tick.WorkerPool;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/** Synthetic dispatch benchmark; run via ./gradlew benchWorkerPool. No timing assertions. */
public final class WorkerPoolBench {
    private static final int WARMUP = 2000;
    private static final int MEASURED = 5000;
    private static final int SPIN = 200;
    private static volatile long sink;

    public static void main(String[] args) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        System.out.printf("warmup=%d measured=%d spin=%d java=%s%n",
                WARMUP, MEASURED, SPIN, System.getProperty("java.version"));
        System.out.println("workers,tasks,workload,us/wave,bytes/wave");
        for (int workers : new int[]{1, Runtime.getRuntime().availableProcessors()}) {
            for (int count : new int[]{1, 2, 16, 256, 1024}) {
                for (String workload : List.of("noop", "balanced", "uneven")) {
                    measure(bean, workers, count, workload);
                }
            }
        }
        System.out.println("sink=" + sink);
    }

    private static void measure(ThreadMXBean bean, int workers, int count, String workload) {
        WorkerPool pool = new WorkerPool(workers);
        List<Runnable> tasks = new ArrayList<>(count);
        List<BurnTask> burns = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (workload.equals("noop")) {
                tasks.add(() -> { });
            } else {
                // Concentrate the same total CPU work into every sixteenth task.
                int spin = workload.equals("balanced") ? SPIN
                        : (i % 16 == 0 ? SPIN * Math.min(16, count - i) : 0);
                BurnTask task = new BurnTask(spin);
                tasks.add(task);
                burns.add(task);
            }
        }
        try {
            for (int i = 0; i < WARMUP; i++) pool.runWave(tasks);
            long[] ids = bean.getAllThreadIds();
            long bytesBefore = allocated(bean, ids);
            long start = System.nanoTime();
            for (int i = 0; i < MEASURED; i++) pool.runWave(tasks);
            long elapsed = System.nanoTime() - start;
            long bytes = allocated(bean, ids) - bytesBefore;
            System.out.printf(java.util.Locale.ROOT, "%d,%d,%s,%.3f,%d%n",
                    workers, count, workload, elapsed / 1000.0 / MEASURED, bytes / MEASURED);
            for (BurnTask task : burns) sink += task.value;
        } finally {
            pool.shutdown();
        }
    }

    private static long allocated(ThreadMXBean bean, long[] ids) {
        long total = 0;
        for (long bytes : bean.getThreadAllocatedBytes(ids)) {
            if (bytes > 0) total += bytes;
        }
        return total;
    }

    private static final class BurnTask implements Runnable {
        private final int spin;
        private long value;

        BurnTask(int spin) {
            this.spin = spin;
        }

        @Override
        public void run() {
            long acc = value;
            for (int i = 0; i < spin; i++) {
                acc += (i * 31L + 7L) ^ (acc >> 1);
            }
            value = acc;
        }
    }
}
