package com.synsenetwork.vanadium.featurebench

import com.sun.management.ThreadMXBean
import com.synsenetwork.vanadium.live.line
import jdk.jfr.Configuration
import jdk.jfr.Recording
import java.io.Writer
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Paired steady-state measurements; JFR runs separately from the timing samples. Write errors throw, and
 * [requireSelection] fails a run whose `bench.filter` selected no case (an empty filter selects every case).
 * The defaults are the fixture's `vanadium.bench.*` properties; tests pass their own.
 */
internal class Measurements(
    private val output: Path = Path.of("feature-bench"),
    private val filter: String = System.getProperty("vanadium.bench.filter", ""),
    private val rounds: Int = Integer.getInteger("vanadium.bench.rounds", 12),
    private val scale: Double = System.getProperty("vanadium.bench.scale", "1").toDouble(),
    private val jfr: Boolean = System.getProperty("vanadium.bench.jfr", "true").toBoolean(),
    /** Where samples go; null writes `samples.csv` in [output]. */
    sampleWriter: Writer? = null,
) : AutoCloseable {
    private val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val pattern = if (filter.isEmpty()) null else try {
        Regex(filter)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("bench.filter '$filter' is not a valid regular expression", e)
    }
    private val samples = sampleWriter ?: Files.newBufferedWriter(Files.createDirectories(output).resolve("samples.csv"))
    private val cases = ArrayList<String>()
    private var selected = 0

    init {
        samples.line("case,enabled,round,operations,wall_ns,caller_cpu_ns,worker_cpu_ns,allocated_bytes")
        threads.isThreadCpuTimeEnabled = true
        threads.isThreadAllocatedMemoryEnabled = true
    }

    fun compare(name: String, select: (Boolean) -> Unit, operation: () -> Unit) {
        cases.add(name)
        if (pattern != null && !name.matches(pattern)) return
        selected++
        println("FEATURE_BENCH_START $name")
        for (on in listOf(false, true)) {
            select(on)
            runFor(operation, 2.0)
        }
        // Alternating AB/BA pairs: off first in even rounds, on first in odd ones.
        for (round in 0 until rounds) {
            for (arm in 0 until 2) {
                val on = (round + arm) and 1 != 0
                select(on)
                val before = snapshot()
                val start = System.nanoTime()
                val operations = runFor(operation, 0.25)
                val elapsed = System.nanoTime() - start
                val after = snapshot()
                samples.line("$name,$on,$round,$operations,$elapsed,${after.callerCpu - before.callerCpu}," +
                    "${after.workerCpu - before.workerCpu},${after.allocated - before.allocated}")
                samples.flush()
            }
        }
        if (jfr) {
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

    /** After every case: a filter that matched none of them is a mistake, not an empty benchmark. */
    fun requireSelection() {
        check(selected > 0) { "bench.filter '$filter' matched no feature benchmark case; cases: ${cases.joinToString(", ")}" }
    }

    override fun close() = samples.close()
}
