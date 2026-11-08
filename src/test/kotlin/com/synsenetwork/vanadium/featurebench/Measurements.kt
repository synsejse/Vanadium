package com.synsenetwork.vanadium.featurebench

import com.sun.management.ThreadMXBean
import jdk.jfr.Configuration
import jdk.jfr.Recording
import java.io.PrintWriter
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Paired steady-state measurements; JFR runs separately from the timing samples. */
internal class Measurements : AutoCloseable {
    private val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val output: Path = Files.createDirectories(Path.of("feature-bench"))
    private val samples = PrintWriter(Files.newBufferedWriter(output.resolve("samples.csv")))
    private val fork = Integer.getInteger("vanadium.bench.fork", 0)
    private val scale = System.getProperty("vanadium.bench.scale", "1").toDouble()
    private val rounds = Integer.getInteger("vanadium.bench.rounds", 12)

    init {
        samples.println("case,enabled,round,operations,wall_ns,caller_cpu_ns,worker_cpu_ns,allocated_bytes")
        threads.isThreadCpuTimeEnabled = true
        threads.isThreadAllocatedMemoryEnabled = true
    }

    fun compare(name: String, select: (Boolean) -> Unit, operation: () -> Unit) {
        val filter = System.getProperty("vanadium.bench.filter", "")
        if (filter.isNotEmpty() && !name.matches(Regex(filter))) return
        println("FEATURE_BENCH_START $name")
        for (on in listOf(false, true)) {
            select(on)
            runFor(operation, 2.0)
        }
        // Alternating AB/BA pairs, with the first order reversed in odd-numbered JVM forks.
        for (round in 0 until rounds) {
            for (arm in 0 until 2) {
                val on = (round + arm + fork) and 1 != 0
                select(on)
                val before = snapshot()
                val start = System.nanoTime()
                val operations = runFor(operation, 0.25)
                val elapsed = System.nanoTime() - start
                val after = snapshot()
                samples.printf("%s,%s,%d,%d,%d,%d,%d,%d%n", name, on, round, operations, elapsed,
                    after.callerCpu - before.callerCpu, after.workerCpu - before.workerCpu, after.allocated - before.allocated)
                samples.flush()
            }
        }
        if (System.getProperty("vanadium.bench.jfr", "true").toBoolean()) {
            for (on in listOf(false, true)) {
                select(on)
                Recording(Configuration.getConfiguration("profile")).use { recording ->
                    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5))
                    recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1))
                    recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1))
                    recording.start()
                    runFor(operation, 2.0)
                    recording.stop()
                    recording.dump(output.resolve("$name-${if (on) "on" else "off"}.jfr"))
                }
            }
        }
        select(true)
        println("FEATURE_BENCH_DONE $name")
    }

    private fun runFor(operation: () -> Unit, seconds: Double): Long {
        val end = System.nanoTime() + (seconds * scale * 1e9).toLong()
        var operations = 0L
        do {
            operation()
            operations++
        } while (System.nanoTime() < end)
        return operations
    }

    private class Snapshot(val callerCpu: Long, val workerCpu: Long, val allocated: Long)

    private fun snapshot(): Snapshot {
        val caller = Thread.currentThread().threadId()
        val ids = threads.allThreadIds.filter { id ->
            id == caller || threads.getThreadInfo(id)?.threadName?.startsWith("Vanadium-Worker-") == true
        }
        val workerCpu = ids.filter { it != caller }.sumOf { maxOf(0, threads.getThreadCpuTime(it)) }
        val allocated = ids.sumOf { maxOf(0, threads.getThreadAllocatedBytes(it)) }
        return Snapshot(threads.getThreadCpuTime(caller), workerCpu, allocated)
    }

    override fun close() = samples.close()
}
