package com.synsenetwork.vanadium.tick;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded samples aggregated on the server thread after each completion barrier. */
public final class TickProfile {
    public static final int MAX_SAMPLES = 65_536;
    private final Samples ticks = new Samples();
    private final EnumMap<Stage, StageStats> stages = new EnumMap<>(Stage.class);
    private final Map<String, Samples> phases = new LinkedHashMap<>();

    public TickProfile() {
        for (Stage stage : Stage.values()) stages.put(stage, new StageStats());
    }

    public void recordTick(long nanos) {
        ticks.add(nanos);
    }

    public boolean isFull() {
        return ticks.size == MAX_SAMPLES;
    }

    public void recordStage(Stage stage, long nanos) {
        stages.get(stage).durations.add(nanos);
    }

    public void recordPhase(String dimension, String phase, long nanos) {
        phases.computeIfAbsent(dimension + " / " + phase, key -> new Samples()).add(nanos);
    }

    public void recordCells(Stage stage, long[] durations) {
        for (long duration : durations) stages.get(stage).cellDurations.add(duration);
    }

    public void recordWait(Stage stage, long nanos) {
        stages.get(stage).waitNanos += nanos;
    }

    public void recordWave(Stage stage, List<Cell> cells) {
        if (cells.isEmpty()) return;
        StageStats stats = stages.get(stage);
        stats.waves++;
        stats.cells += cells.size();
        for (Cell cell : cells) {
            int tasks = cell.taskCount();
            stats.tasks += tasks;
            stats.maxTasksPerCell = Math.max(stats.maxTasksPerCell, tasks);
        }
    }

    public String report() {
        StringBuilder report = new StringBuilder("Tick work (idle time excluded): ").append(ticks.summary());
        report.append("\nQueued stage execution across all worlds; collection and serial fallbacks excluded.");
        for (Stage stage : Stage.values()) {
            StageStats stats = stages.get(stage);
            report.append(String.format(Locale.ROOT,
                    "\n%s: %s; caller tail wait %.2fms total; waves=%d cells=%d tasks=%d; tasks/cell avg=%.1f max=%d",
                    stage, stats.durations.summary(), stats.waitNanos / 1e6,
                    stats.waves, stats.cells, stats.tasks,
                    stats.cells == 0 ? 0.0 : (double) stats.tasks / stats.cells, stats.maxTasksPerCell));
            report.append("; cell execution ").append(stats.cellDurations.summary());
        }
        phases.forEach((phase, samples) -> report.append('\n').append(phase).append(": ").append(samples.summary()));
        report.append("\nPreparation includes collection and inline fallbacks; world totals include waves. Do not add overlapping totals.");
        report.append("\nCells count executions, not unique positions. Timings include profiling overhead.");
        return report.toString();
    }

    private static final class StageStats {
        final Samples durations = new Samples();
        final Samples cellDurations = new Samples();
        long waitNanos;
        long waves;
        long cells;
        long tasks;
        int maxTasksPerCell;
    }

    /** Stores the first MAX_SAMPLES durations; totals continue so truncation is explicit. */
    static final class Samples {
        private long[] values = new long[256];
        private int size;
        private long count;
        private long total;

        void add(long nanos) {
            count++;
            total += nanos;
            if (size == MAX_SAMPLES) return;
            if (size == values.length) values = Arrays.copyOf(values, Math.min(MAX_SAMPLES, size * 2));
            values[size++] = nanos;
        }

        String summary() {
            if (size == 0) return "no samples";
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            return String.format(Locale.ROOT, "n=%d avg=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms%s",
                    count, total / (count * 1e6), percentile(sorted, 50) / 1e6,
                    percentile(sorted, 95) / 1e6, percentile(sorted, 99) / 1e6,
                    count > size ? " (percentiles limited to first " + size + ")" : "");
        }

        static long percentile(long[] sorted, int percent) {
            return sorted[(int) Math.ceil(sorted.length * percent / 100.0) - 1];
        }
    }
}
