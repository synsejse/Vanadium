package com.synsenetwork.vanadium.commands

import com.google.gson.JsonObject
import com.synsenetwork.vanadium.Vanadium
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/** Wall-time benchmark using vanilla tick sprint. State is confined to the server thread. */
object TickBenchmark {
    const val DEFAULT_SECONDS = 30
    private const val WINDOW_NANOS = 1_000_000_000L

    private var source: CommandSourceStack? = null
    private var durationNanos = DEFAULT_SECONDS * 1_000_000_000L
    private var startNanos = 0L
    private var windowStartNanos = 0L
    private var totalTicks = 0
    private var windowTicks = 0
    private var peakWindowTicks = 0
    private var lastReport: PerformanceReport? = null

    /** True while a benchmark runs; ServerTickRateManagerMixin mutes vanilla's sprint report then. */
    @JvmStatic
    var isActive = false
        private set

    /** Starts the benchmark; false if one is already running or the server is already sprinting. */
    fun start(server: MinecraftServer, commandSource: CommandSourceStack, seconds: Int = DEFAULT_SECONDS): Boolean {
        if (isActive || server.tickRateManager().isSprinting) return false
        isActive = true
        source = commandSource
        durationNanos = seconds * 1_000_000_000L
        startNanos = System.nanoTime()
        windowStartNanos = startNanos
        totalTicks = 0
        windowTicks = 0
        peakWindowTicks = 0
        server.tickRateManager().requestGameToSprint(Int.MAX_VALUE)
        return true
    }

    fun onServerStopped() {
        isActive = false
        source = null
        lastReport = null
    }

    fun lastReport(section: String = "summary"): Component = lastReport?.view(section)
        ?: Component.literal("Vanadium: no completed benchmark for this server")

    fun onTickEnd(server: MinecraftServer) {
        if (!isActive) return
        totalTicks++
        windowTicks++
        val now = System.nanoTime()
        if (now - windowStartNanos >= WINDOW_NANOS) {
            peakWindowTicks = maxOf(peakWindowTicks, windowTicks)
            windowStartNanos = now
            windowTicks = 0
        }
        if (now - startNanos >= durationNanos || !server.tickRateManager().isSprinting) finish(server, now)
    }

    private fun finish(server: MinecraftServer, now: Long) {
        server.tickRateManager().stopSprinting() // while still active, so the vanilla report stays muted
        isActive = false
        val seconds = (now - startNanos) / 1.0e9
        val peak = maxOf(peakWindowTicks, windowTicks)
        val result = JsonObject().apply {
            addProperty("requestedSeconds", durationNanos / 1e9)
            addProperty("seconds", seconds)
            addProperty("ticks", totalTicks)
            addProperty("avgTps", totalTicks / seconds)
            addProperty("peakTps", peak)
        }
        val file = Results.publish(server, "benchmark", result)
        val report = PerformanceReport("benchmark", result,
            "${Vanadium.scheduler.workerCount} helpers + server thread · isolation ${Vanadium.scheduler.isolation}",
            file?.let { Results.describe(server, it) })
        lastReport = report
        val message = report.view()
        Vanadium.LOGGER.info(message.string)
        source?.sendSuccess({ message }, false)
        source = null
    }
}
