package com.synsenetwork.vanadium.diagnostics

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.TickThread
import net.minecraft.CrashReport

/**
 * Vanadium's section of the crash report vanilla's server watchdog writes for a hung tick, as C2ME adds its own:
 * the wave in progress and what each worker runs. It is built only when the watchdog fires, from state the tick
 * keeps anyway, and takes no lock or monitor a hung tick may hold.
 */
object WatchdogReport {
    /** Frames per busy worker; vanilla's thread dump stops at 8, too few to reach the task a worker runs. */
    private const val MAX_FRAMES = 32

    private val TICK_PACKAGE = TickThread::class.java.packageName + "."

    @JvmStatic
    fun addTo(report: CrashReport) {
        val category = report.addCategory("Vanadium")
        try {
            for ((key, value) in details(Vanadium.scheduler, Thread.getAllStackTraces())) category.setDetail(key, value)
        } catch (t: Throwable) {
            // The report is the server's last word on the hang: keep it, with this section's failure in it.
            category.setDetailError("Failed to describe the tick", t)
        }
    }

    /**
     * The section's details, from [scheduler] and the threads' [stacks]. A seam so that tests render it from a
     * stalled wave without a server.
     */
    internal fun details(scheduler: TickScheduler, stacks: Map<Thread, Array<StackTraceElement>>): List<Pair<String, String>> {
        val watch = scheduler.diagnostics?.watched
        val stage = scheduler.currentStage
        val wave = when {
            watch != null -> watch.summary()
            stage != null -> "stage=$stage dimension=${scheduler.currentDimension} " +
                "(the stage last begun; slowWaveMillis above 0 adds its wave's progress)"
            else -> "dimension=${scheduler.currentDimension}; no stage begun in it yet"
        }
        val busy = StringBuilder()
        val idle = ArrayList<String>()
        for ((thread, frames) in stacks.entries.sortedBy { it.key.threadId() }) {
            if (thread !is TickThread) continue
            // A worker between waves spins, parks or claims in the pool: no frame outside the JDK and the pool's package.
            if (frames.all { it.className.startsWith("java.") || it.className.startsWith("jdk.") || it.className.startsWith(TICK_PACKAGE) }) {
                idle.add(thread.name)
                continue
            }
            busy.append("\n").append(thread.name).append(" [").append(thread.state).append("] ")
            if (watch != null) busy.append(watch.activity(thread))
            for (frame in frames.take(MAX_FRAMES)) busy.append("\n\t\tat ").append(frame)
        }
        return listOf(
            "Scheduler" to "${scheduler.workerCount} workers + server thread, isolation ${scheduler.isolation} chunks",
            "Wave" to wave,
            // The pool sees a flat wave's tasks, but only the participants' loops of a region wave.
            "Latest parallel wave" to scheduler.latestWaveProgress() + " (a region wave's tasks are its participants)",
            "Busy workers" to busy.ifEmpty { "none" }.toString(),
            "Idle workers" to idle.ifEmpty { listOf("none") }.joinToString(", "),
        )
    }
}
