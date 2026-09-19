package com.synsenetwork.vanadium.featurebench;

import com.sun.management.ThreadMXBean;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Consumer;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Paired steady-state measurements; JFR runs separately from the timing samples. */
final class Measurements implements AutoCloseable {
    private final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final PrintWriter samples;
    private final Path output;
    private final int fork = Integer.getInteger("vanadium.bench.fork", 0);
    private final double scale = Double.parseDouble(System.getProperty("vanadium.bench.scale", "1"));
    private final int rounds = Integer.getInteger("vanadium.bench.rounds", 12);

    Measurements() throws Exception {
        output = Path.of("feature-bench");
        Files.createDirectories(output);
        samples = new PrintWriter(Files.newBufferedWriter(output.resolve("samples.csv")));
        samples.println("case,enabled,round,operations,wall_ns,caller_cpu_ns,worker_cpu_ns,allocated_bytes");
        threads.setThreadCpuTimeEnabled(true);
        threads.setThreadAllocatedMemoryEnabled(true);
    }

    void compare(String name, Consumer<Boolean> select, Runnable operation) throws Exception {
        String filter = System.getProperty("vanadium.bench.filter", "");
        if (!filter.isEmpty() && !name.matches(filter)) return;
        System.out.println("FEATURE_BENCH_START " + name);
        for (boolean on : new boolean[]{false, true}) {
            select.accept(on);
            runFor(operation, 2);
        }
        // Alternating AB/BA pairs, with the first order reversed in odd-numbered JVM forks.
        for (int round = 0; round < rounds; round++) {
            for (int arm = 0; arm < 2; arm++) {
                boolean on = ((round + arm + fork) & 1) != 0;
                select.accept(on);
                Snapshot before = snapshot();
                long start = System.nanoTime();
                long operations = runFor(operation, 0.25);
                long elapsed = System.nanoTime() - start;
                Snapshot after = snapshot();
                samples.printf("%s,%s,%d,%d,%d,%d,%d,%d%n", name, on, round, operations, elapsed,
                        after.callerCpu - before.callerCpu, after.workerCpu - before.workerCpu,
                        after.allocated - before.allocated);
                samples.flush();
            }
        }
        if (Boolean.parseBoolean(System.getProperty("vanadium.bench.jfr", "true"))) {
            for (boolean on : new boolean[]{false, true}) {
                select.accept(on);
                try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
                    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5));
                    recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1));
                    recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1));
                    recording.start();
                    runFor(operation, 2);
                    recording.stop();
                    recording.dump(output.resolve(name + "-" + (on ? "on" : "off") + ".jfr"));
                }
            }
        }
        select.accept(true);
        System.out.println("FEATURE_BENCH_DONE " + name);
    }

    private long runFor(Runnable operation, double seconds) {
        long end = System.nanoTime() + (long) (seconds * scale * 1e9);
        long operations = 0;
        do {
            operation.run();
            operations++;
        } while (System.nanoTime() < end);
        return operations;
    }

    private Snapshot snapshot() {
        long caller = Thread.currentThread().threadId();
        long[] ids = Arrays.stream(threads.getAllThreadIds()).filter(id -> {
            var info = threads.getThreadInfo(id);
            return id == caller || info != null && info.getThreadName().startsWith("Vanadium-Worker-");
        }).toArray();
        long workerCpu = 0;
        long allocated = 0;
        for (long id : ids) {
            if (id != caller) workerCpu += Math.max(0, threads.getThreadCpuTime(id));
            allocated += Math.max(0, threads.getThreadAllocatedBytes(id));
        }
        return new Snapshot(threads.getThreadCpuTime(caller), workerCpu, allocated);
    }

    private record Snapshot(long callerCpu, long workerCpu, long allocated) {}

    @Override public void close() { samples.close(); }
}
