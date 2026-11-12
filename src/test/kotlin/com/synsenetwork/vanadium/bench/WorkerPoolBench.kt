package com.synsenetwork.vanadium.bench

import com.sun.management.ThreadMXBean
import com.synsenetwork.vanadium.tick.SpinPolicy
import com.synsenetwork.vanadium.tick.WorkerPool
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic dispatch benchmark; run via ./gradlew benchWorkerPool. No timing assertions. */
object WorkerPoolBench {
    private const val WARMUP = 2000
    private const val MEASURED = 5000
    private const val SPIN = 200

    @Volatile
    private var sink = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        println("warmup=$WARMUP measured=$MEASURED spin=$SPIN java=${System.getProperty("java.version")}")
        println("workers,tasks,workload,us/wave,bytes/wave")
        for (workers in intArrayOf(1, Runtime.getRuntime().availableProcessors())) {
            for (count in intArrayOf(1, 2, 16, 256, 1024)) {
                for (workload in listOf("noop", "balanced", "uneven")) measure(bean, workers, count, workload)
            }
        }
        wakeLatency()
        participantCost()
        println("sink=$sink")
    }

    /**
     * Time for a wave that needs every helper: each task spins until all have arrived, so the wave's wall time
     * is when the last helper joined. "caller" is how long the caller took to reach its own task (its share of
     * the wake-ups). Starts: spinning pool, fully parked pool woken by the fan-out or by the caller alone (the
     * old serial loop), and a parked pool woken in advance by tickStarted 200 µs before the wave.
     */
    private fun wakeLatency() {
        println("workers,start,callerWakes,joined us p50,joined us p90,caller us p50,caller us p90,caller unparks")
        val counts = intArrayOf(24, 48, 72).filter { it <= Runtime.getRuntime().availableProcessors() }
        for (workers in counts) {
            for ((start, callerWakes) in listOf("spinning" to 2, "parked" to 2, "parked" to Int.MAX_VALUE, "prewoken" to 2)) {
                val long = if (start == "spinning") 60_000_000_000L else 2_000_000L
                val pool = WorkerPool(workers, SpinPolicy(long, 60_000_000_000L, fixed = false), callerWakes)
                try {
                    val rounds = if (start == "spinning") 2000 else 300
                    val joined = LongArray(rounds)
                    val caller = LongArray(rounds)
                    var unparks = 0L
                    repeat(WAKE_WARMUP + rounds) { round ->
                        if (start != "spinning") {
                            pool.tickEnded(SLACK, sprinting = false)
                            while ((0 until workers).any { !pool.isParked(it) }) Thread.sleep(0, 200_000)
                            Thread.sleep(1) // Let the last ones settle into park.
                            if (start == "prewoken") {
                                pool.tickStarted()
                                val until = System.nanoTime() + 200_000
                                while (System.nanoTime() < until) Thread.onSpinWait()
                            }
                        }
                        val (all, own) = gatedWave(pool, workers + 1)
                        if (round >= WAKE_WARMUP) {
                            joined[round - WAKE_WARMUP] = all
                            caller[round - WAKE_WARMUP] = own
                            unparks += pool.lastCallerUnparks
                        }
                    }
                    joined.sort()
                    caller.sort()
                    val wakes = if (callerWakes == Int.MAX_VALUE) "all" else callerWakes.toString()
                    println(String.format(Locale.ROOT, "%d,%s,%s,%.1f,%.1f,%.1f,%.1f,%.1f", workers, start, wakes,
                        joined[rounds / 2] / 1000.0, joined[rounds * 9 / 10] / 1000.0,
                        caller[rounds / 2] / 1000.0, caller[rounds * 9 / 10] / 1000.0, unparks.toDouble() / rounds))
                } finally {
                    pool.close()
                }
            }
        }
    }

    /** Returns when the last participant arrived and when the caller reached its task, both from the call. */
    private fun gatedWave(pool: WorkerPool, count: Int): Pair<Long, Long> {
        val arrived = AtomicInteger()
        val caller = Thread.currentThread()
        var last = 0L
        var own = 0L
        val begin = System.nanoTime()
        pool.runWave(List(count) {
            Runnable {
                val now = System.nanoTime()
                if (Thread.currentThread() === caller) own = now - begin
                if (arrived.incrementAndGet() == count) last = now - begin
                val deadline = now + 5_000_000_000L
                while (arrived.get() < count && System.nanoTime() < deadline) Thread.onSpinWait()
            }
        })
        return last to own
    }

    /**
     * Wall time of balanced waves of a given total work by participant count, with spinning workers: the
     * participant cost c in TickScheduler is about the slope of the wall time once the work per participant
     * no longer dominates.
     */
    private fun participantCost() {
        val workers = minOf(72, Runtime.getRuntime().availableProcessors())
        val participants = intArrayOf(1, 2, 4, 8, 16, 24, 32, 48, 64, 73).filter { it <= workers + 1 }
        println("participant cost: workers=$workers, 512 balanced tasks per wave, spinning pool")
        println("work us," + participants.joinToString(",") { "p=$it us" })
        val pool = WorkerPool(workers, SpinPolicy(60_000_000_000L, 60_000_000_000L, fixed = false))
        try {
            val perIteration = measureBurn()
            for (workMicros in intArrayOf(10, 50, 200, 1000, 4000)) {
                val spin = maxOf(1, (workMicros * 1000.0 / 512 / perIteration).toInt())
                val tasks = List(512) { BurnTask(spin) }
                val row = participants.map { p ->
                    repeat(300) { pool.runWave(tasks, null, p) }
                    val rounds = 1000
                    val start = System.nanoTime()
                    repeat(rounds) { pool.runWave(tasks, null, p) }
                    (System.nanoTime() - start) / 1000.0 / rounds
                }
                for (task in tasks) sink += task.value
                println("$workMicros," + row.joinToString(",") { String.format(Locale.ROOT, "%.1f", it) })
            }
        } finally {
            pool.close()
        }
    }

    /** Nanoseconds per BurnTask iteration on the caller, to size tasks by time. */
    private fun measureBurn(): Double {
        val task = BurnTask(100_000)
        repeat(200) { task.run() }
        val start = System.nanoTime()
        repeat(200) { task.run() }
        sink += task.value
        return (System.nanoTime() - start).toDouble() / (200 * 100_000)
    }

    private const val WAKE_WARMUP = 50
    private const val SLACK = 3_600_000_000_000L

    private fun measure(bean: ThreadMXBean, workers: Int, count: Int, workload: String) {
        val pool = WorkerPool(workers)
        val burns = ArrayList<BurnTask>()
        val tasks = List(count) { i ->
            if (workload == "noop") Runnable {} else {
                // Concentrate the same total CPU work into every sixteenth task.
                val spin = if (workload == "balanced") SPIN else if (i % 16 == 0) SPIN * minOf(16, count - i) else 0
                BurnTask(spin).also(burns::add)
            }
        }
        try {
            repeat(WARMUP) { pool.runWave(tasks) }
            val ids = bean.allThreadIds
            val bytesBefore = allocated(bean, ids)
            val start = System.nanoTime()
            repeat(MEASURED) { pool.runWave(tasks) }
            val elapsed = System.nanoTime() - start
            val bytes = allocated(bean, ids) - bytesBefore
            println(String.format(Locale.ROOT, "%d,%d,%s,%.3f,%d", workers, count, workload, elapsed / 1000.0 / MEASURED, bytes / MEASURED))
            for (task in burns) sink += task.value
        } finally {
            pool.close()
        }
    }

    private fun allocated(bean: ThreadMXBean, ids: LongArray): Long = bean.getThreadAllocatedBytes(ids).filter { it > 0 }.sum()

    private class BurnTask(private val spin: Int) : Runnable {
        var value = 0L

        override fun run() {
            var acc = value
            for (i in 0 until spin) acc += (i * 31L + 7L) xor (acc shr 1)
            value = acc
        }
    }
}
