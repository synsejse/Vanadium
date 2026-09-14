package com.synsenetwork.vanadium.tick;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Opt-in watchdog. It reads progress and stacks without acquiring monitors used by tick tasks. */
public final class WaveDiagnostics implements AutoCloseable {
    private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_STACKS = 8;
    private final Consumer<String> reporter;
    private final LongSupplier clock;
    private final boolean automatic;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> polling;
    private volatile Watch current;
    private long thresholdNanos;
    private boolean detailed;
    private boolean reported;
    private long lastReport;

    public WaveDiagnostics(Consumer<String> reporter) {
        this(reporter, System::nanoTime, true);
    }

    WaveDiagnostics(Consumer<String> reporter, LongSupplier clock, boolean automatic) {
        this.reporter = reporter;
        this.clock = clock;
        this.automatic = automatic;
    }

    /** Server-thread only, between waves. Zero disables monitoring and cancels polling. */
    public void configure(int thresholdMillis, boolean detailed) {
        this.thresholdNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, thresholdMillis));
        this.detailed = detailed;
        if (thresholdNanos == 0 && polling != null) {
            polling.cancel(false);
            polling = null;
        }
    }

    public boolean enabled() {
        return thresholdNanos > 0;
    }

    public boolean detailed() {
        return enabled() && detailed;
    }

    public Watch watch(Stage stage, String dimension, int color, int cellSize) {
        if (!enabled()) return null;
        if (automatic && polling == null) {
            if (executor == null) {
                executor = Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "Vanadium-Wave-Watchdog");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            polling = executor.scheduleWithFixedDelay(this::poll, 100, 100, TimeUnit.MILLISECONDS);
        }
        Watch watch = new Watch(this, stage, dimension, color, cellSize, thresholdNanos, detailed, clock.getAsLong());
        current = watch;
        return watch;
    }

    /** Called only by the watchdog (or by deterministic tests using an injected clock). */
    void poll() {
        Watch watch = current;
        if (watch == null || watch.closed) return;
        long now = clock.getAsLong();
        if (now - watch.started < watch.thresholdNanos) return;
        if (reported && now - lastReport < REPORT_INTERVAL_NANOS) return;
        String report = watch.describe(now);
        if (watch.closed) return;
        reported = true;
        lastReport = now;
        reporter.accept(report);
    }

    @Override
    public void close() {
        Watch watch = current;
        if (watch != null) watch.close();
        if (executor != null) executor.close();
        polling = null;
        executor = null;
        thresholdNanos = 0;
    }

    public static final class Watch implements AutoCloseable {
        private final WaveDiagnostics owner;
        private final Stage stage;
        private final String dimension;
        private final int color;
        private final int cellSize;
        private final long thresholdNanos;
        private final boolean detailed;
        private final long started;
        private final ConcurrentHashMap<Thread, Progress> running = new ConcurrentHashMap<>();
        private volatile boolean closed;

        private Watch(WaveDiagnostics owner, Stage stage, String dimension, int color, int cellSize,
                      long thresholdNanos, boolean detailed, long started) {
            this.owner = owner;
            this.stage = stage;
            this.dimension = dimension;
            this.color = color;
            this.cellSize = cellSize;
            this.thresholdNanos = thresholdNanos;
            this.detailed = detailed;
            this.started = started;
        }

        /** Wrap only monitored waves, leaving the worker pool's normal task loop untouched. */
        List<Runnable> wrap(List<? extends Runnable> tasks) {
            List<Runnable> watched = new ArrayList<>(tasks.size());
            for (Runnable task : tasks) watched.add(() -> runTask(task));
            return watched;
        }

        private void runTask(Runnable task) {
            Thread thread = Thread.currentThread();
            Progress progress = new Progress(task instanceof Cell cell ? cell : null);
            running.put(thread, progress);
            try {
                if (detailed && task instanceof Cell cell) {
                    cell.run(next -> progress.task = next instanceof NamedTask named
                            ? named.label() : next.getClass().getName());
                } else {
                    task.run();
                }
            } finally {
                running.remove(thread);
            }
        }

        private String describe(long now) {
            StringBuilder report = new StringBuilder(String.format(Locale.ROOT,
                    "Vanadium slow wave: stage=%s dimension=%s color=%d cellSize=%d elapsed=%.0fms; active participants=%d",
                    stage, dimension, color, cellSize, (now - started) / 1e6, running.size()));
            int shown = 0;
            for (var entry : running.entrySet()) {
                if (shown++ == MAX_STACKS) {
                    report.append("\nAdditional participant stacks omitted (limit ").append(MAX_STACKS).append(")");
                    break;
                }
                Thread thread = entry.getKey();
                Progress progress = entry.getValue();
                report.append("\n").append(thread.getName()).append(" [").append(thread.getState()).append("] ");
                if (progress.cell != null) {
                    report.append("cell=(").append(progress.cell.cellX()).append(",").append(progress.cell.cellZ())
                            .append(") in cell units");
                }
                if (progress.task != null) report.append(" task=").append(progress.task);
                StackTraceElement[] stack = thread.getStackTrace();
                for (int i = 0; i < Math.min(stack.length, 12); i++) report.append("\n  at ").append(stack[i]);
            }
            return report.toString();
        }

        @Override
        public void close() {
            closed = true;
            if (owner.current == this) owner.current = null;
        }
    }

    private static final class Progress {
        final Cell cell;
        volatile String task;

        Progress(Cell cell) {
            this.cell = cell;
        }
    }

    record NamedTask(String label, Runnable action) implements Runnable {
        @Override public void run() { action.run(); }
    }
}
