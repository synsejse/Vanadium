package com.synsenetwork.vanadium.diagnostics

import com.google.gson.JsonObject
import com.synsenetwork.vanadium.Vanadium
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/** Wall-time benchmark using vanilla tick sprint. State is confined to the server thread. */
object TickBenchmark {
    const val DEFAULT_SECONDS = 30
    private const val WINDOW_NANOS = 1_000_000_000L

    private class Run(val source: CommandSourceStack, seconds: Int) {
        val durationNanos = seconds * 1_000_000_000L
        val startNanos = System.nanoTime()
        var windowStartNanos = startNanos
        var totalTicks = 0
        var windowTicks = 0
        var peakWindowTicks = 0
    }

    private var active: Run? = null
    private var completed: PerformanceReport? = null

    /** True while a benchmark runs; ServerTickRateManagerMixin mutes vanilla's sprint report then. */
    @JvmStatic
    val isActive: Boolean
        get() = active != null

    /** Starts the benchmark; false if one is already running or the server is already sprinting. */
    fun start(server: MinecraftServer, commandSource: CommandSourceStack, seconds: Int = DEFAULT_SECONDS): Boolean {
        if (isActive || server.tickRateManager().isSprinting) return false
        active = Run(commandSource, seconds)
        server.tickRateManager().requestGameToSprint(Int.MAX_VALUE)
        return true
    }

    fun onServerStopped() {
        active = null
        completed = null
    }

    fun lastReport(section: String = "summary"): Component {
        val report = completed ?: return Component.literal("Vanadium: no completed benchmark for this server")
        return report.view(section)
    }

    fun onTickEnd(server: MinecraftServer) {
        val run = active ?: return
        run.totalTicks++
        run.windowTicks++
        val now = System.nanoTime()
        if (now - run.windowStartNanos >= WINDOW_NANOS) {
            run.peakWindowTicks = maxOf(run.peakWindowTicks, run.windowTicks)
            run.windowStartNanos = now
            run.windowTicks = 0
        }
        if (now - run.startNanos >= run.durationNanos || !server.tickRateManager().isSprinting) finish(server, run, now)
    }

    private fun finish(server: MinecraftServer, run: Run, now: Long) {
        server.tickRateManager().stopSprinting() // while still active, so the vanilla report stays muted
        active = null
        val seconds = (now - run.startNanos) / 1.0e9
        val result = JsonObject()
        result.addProperty("requestedSeconds", run.durationNanos / 1e9)
        result.addProperty("seconds", seconds)
        result.addProperty("ticks", run.totalTicks)
        result.addProperty("avgTps", run.totalTicks / seconds)
        result.addProperty("peakTps", maxOf(run.peakWindowTicks, run.windowTicks))
        val configuration =
            "${Vanadium.scheduler.workerCount} helpers + server thread · isolation ${Vanadium.scheduler.isolation}"
        completed = PerformanceResults.report(run.source, "benchmark", result, configuration)
    }
}
