package com.synsenetwork.vanadium.tick

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.LongSupplier

/** Opt-in watchdog. It reads progress and stacks without acquiring monitors used by tick tasks. */
class WaveDiagnostics internal constructor(
    private val reporter: Consumer<String>,
    private val clock: LongSupplier,
    private val automatic: Boolean,
) : AutoCloseable {
    constructor(reporter: Consumer<String>) : this(reporter, LongSupplier(System::nanoTime), true)

    private var executor: ScheduledExecutorService? = null
    private var polling: ScheduledFuture<*>? = null

    @Volatile
    private var current: Watch? = null
    private var thresholdNanos = 0L
    private var detailedLabels = false
    private var reported = false
    private var lastReport = 0L

    /** Server-thread only, between waves. Zero disables monitoring and cancels polling. */
    fun configure(thresholdMillis: Int, detailed: Boolean) {
        thresholdNanos = TimeUnit.MILLISECONDS.toNanos(maxOf(0, thresholdMillis).toLong())
        detailedLabels = detailed
        if (thresholdNanos == 0L) {
            polling?.cancel(false)
            polling = null
        }
    }

    val enabled: Boolean get() = thresholdNanos > 0

    val detailed: Boolean get() = enabled && detailedLabels

    fun watch(stage: Stage, dimension: String, isolation: Int): Watch? {
        if (!enabled) return null
        if (automatic && polling == null) {
            val scheduler = executor ?: Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "Vanadium-Wave-Watchdog").apply { isDaemon = true }
            }.also { executor = it }
            polling = scheduler.scheduleWithFixedDelay(::poll, 100, 100, TimeUnit.MILLISECONDS)
        }
        return Watch(stage, dimension, isolation, thresholdNanos, detailedLabels, clock.asLong).also { current = it }
    }

    /** Called only by the watchdog (or by deterministic tests using an injected clock). */
    internal fun poll() {
        val watch = current ?: return
        if (watch.closed) return
        val now = clock.asLong
        if (now - watch.started < watch.thresholdNanos) return
        if (reported && now - lastReport < REPORT_INTERVAL_NANOS) return
        val report = watch.describe(now)
        if (watch.closed) return
        reported = true
        lastReport = now
        reporter.accept(report)
    }

    override fun close() {
        current?.close()
        executor?.close()
        polling = null
        executor = null
        thresholdNanos = 0
    }

    inner class Watch internal constructor(
        private val stage: Stage,
        private val dimension: String,
        private val isolation: Int,
        internal val thresholdNanos: Long,
        private val detailed: Boolean,
        internal val started: Long,
    ) : AutoCloseable {
        private val running = ConcurrentHashMap<Thread, Progress>()

        @Volatile
        internal var closed = false
            private set

        /** Wraps only monitored waves, leaving the worker pool's normal task loop untouched. */
        internal fun wrap(tasks: List<Runnable>): List<Runnable> = tasks.map { task -> Runnable { runTask(task) } }

        private fun runTask(task: Runnable) {
            val thread = Thread.currentThread()
            val progress = Progress(task as? Region)
            running[thread] = progress
            try {
                if (detailed && task is Region) {
                    task.run { next -> progress.task = (next as? NamedTask)?.label ?: next.javaClass.name }
                } else {
                    task.run()
                }
            } finally {
                running.remove(thread)
            }
        }

        internal fun describe(now: Long): String = buildString {
            append(String.format(Locale.ROOT,
                "Vanadium slow wave: stage=%s dimension=%s isolation=%d chunks elapsed=%.0fms; active participants=%d",
                stage, dimension, isolation, (now - started) / 1e6, running.size))
            for ((shown, entry) in running.entries.withIndex()) {
                if (shown == MAX_STACKS) {
                    append("\nAdditional participant stacks omitted (limit ").append(MAX_STACKS).append(")")
                    break
                }
                val (thread, progress) = entry
                append("\n").append(thread.name).append(" [").append(thread.state).append("] ")
                progress.region?.let { append("region=").append(it) }
                progress.task?.let { append(" task=").append(it) }
                thread.stackTrace.take(12).forEach { append("\n  at ").append(it) }
            }
        }

        override fun close() {
            closed = true
            if (current === this) current = null
        }
    }

    private class Progress(val region: Region?) {
        @Volatile
        var task: String? = null
    }

    /** A region task carrying a registry label for detailed slow-wave reports. */
    internal class NamedTask(val label: String, private val action: Runnable) : Runnable by action

    private companion object {
        val REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30)
        const val MAX_STACKS = 8
    }
}
