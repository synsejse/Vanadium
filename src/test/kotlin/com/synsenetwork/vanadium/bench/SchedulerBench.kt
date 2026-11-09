package com.synsenetwork.vanadium.bench

import com.sun.management.ThreadMXBean
import com.synsenetwork.vanadium.tick.Stage
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.WorkerPool
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.LongAdder

/** Standalone micro-benchmark of the tick scheduler hot path (no Minecraft). Run via ./gradlew benchScheduler. */
object SchedulerBench {
    /** Consumed at the end so the loaded task's work isn't eliminated by the JIT. */
    private val SINK = LongAdder()

    /** A few microseconds of CPU per chunk, so loaded waves are barrier-bound. */
    private const val SPIN = 200
    private const val ISOLATION = 2
    private const val RADIUS = 16 // ticking area is (2 * RADIUS)^2 chunks
    private const val WARMUP = 2000
    private const val MEASURED = 5000

    @JvmStatic
    fun main(args: Array<String>) {
        val threads = Runtime.getRuntime().availableProcessors()
        val pool = WorkerPool(threads)
        val scheduler = TickScheduler(pool, ISOLATION)
        val noop = Runnable {} // measures scheduler overhead, not task cost
        val loaded = Runnable(::burn)
        repeat(WARMUP) {
            tick(scheduler, noop)
            tick(scheduler, loaded)
        }

        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        // Snapshot the warmed thread set once so the before/after totals cover the same threads.
        val threadIds = bean.allThreadIds
        val allocBefore = allocated(bean, threadIds)
        val noopStart = System.nanoTime()
        repeat(MEASURED) { tick(scheduler, noop) }
        val noopNanos = System.nanoTime() - noopStart
        val allocBytes = allocated(bean, threadIds) - allocBefore
        val loadedStart = System.nanoTime()
        repeat(MEASURED) { tick(scheduler, loaded) }
        val loadedNanos = System.nanoTime() - loadedStart

        println("threads=$threads isolation=$ISOLATION areaChunks=${4 * RADIUS * RADIUS} ticks=$MEASURED spin=$SPIN")
        println("  noop   alloc/tick = ${allocBytes / MEASURED} bytes")
        println("  noop   time/tick  = %.1f us".format(noopNanos / 1000.0 / MEASURED))
        println("  loaded time/tick  = %.1f us  (sink=%d)".format(loadedNanos / 1000.0 / MEASURED, SINK.sum()))
        pool.close()
    }

    private fun burn() {
        var acc = 0L
        for (i in 0 until SPIN) acc += (i * 31L + 7L) xor (acc shr 1)
        SINK.add(acc)
    }

    private fun tick(scheduler: TickScheduler, task: Runnable) {
        scheduler.begin(Stage.CHUNK)
        for (x in -RADIUS until RADIUS) for (z in -RADIUS until RADIUS) scheduler.enqueue(Stage.CHUNK, x, z, task, null)
        scheduler.run(Stage.CHUNK)
    }

    private fun allocated(bean: ThreadMXBean, ids: LongArray): Long = bean.getThreadAllocatedBytes(ids).filter { it > 0 }.sum()
}
