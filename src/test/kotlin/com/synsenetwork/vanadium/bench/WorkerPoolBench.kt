package com.synsenetwork.vanadium.bench

import com.sun.management.ThreadMXBean
import com.synsenetwork.vanadium.tick.WorkerPool
import java.lang.management.ManagementFactory
import java.util.Locale

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
        println("sink=$sink")
    }

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
