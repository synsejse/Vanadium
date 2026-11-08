package com.synsenetwork.vanadium.commands

import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/**
 * A 30-second unlocked-TPS benchmark driven by vanilla's tick sprint: while sprinting the server loop
 * does not sleep between ticks, syncs clients, and suppresses overload warnings. This starts a sprint
 * far larger than 30s can consume, counts ticks at END_SERVER_TICK, and stops when time runs out.
 * All state is confined to the server thread (commands and tick events both run there).
 */
object TickBenchmark {
    private const val DURATION_NANOS = 30_000_000_000L
    private const val WINDOW_NANOS = 1_000_000_000L

    private var source: CommandSourceStack? = null
    private var startNanos = 0L
    private var windowStartNanos = 0L
    private var totalTicks = 0
    private var windowTicks = 0
    private var peakWindowTicks = 0

    /** True while a benchmark runs; ServerTickRateManagerMixin mutes vanilla's sprint report then. */
    @JvmStatic
    var isActive = false
        private set

    /** Starts the benchmark; false if one is already running or the server is already sprinting. */
    fun start(server: MinecraftServer, commandSource: CommandSourceStack): Boolean {
        if (isActive || server.tickRateManager().isSprinting) return false
        isActive = true
        source = commandSource
        startNanos = System.nanoTime()
        windowStartNanos = startNanos
        totalTicks = 0
        windowTicks = 0
        peakWindowTicks = 0
        server.tickRateManager().requestGameToSprint(Int.MAX_VALUE)
        return true
    }

    /** Drop the command source so an interrupted benchmark cannot retain a stopped server. */
    fun onServerStopped() {
        isActive = false
        source = null
    }

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
        // Finish on time, or early if something else ended the sprint (e.g. /tick sprint stop).
        if (now - startNanos >= DURATION_NANOS || !server.tickRateManager().isSprinting) finish(server, now)
    }

    private fun finish(server: MinecraftServer, now: Long) {
        server.tickRateManager().stopSprinting() // while still active, so the vanilla report stays muted
        isActive = false
        val seconds = (now - startNanos) / 1.0e9
        val message = Component.literal(String.format(
            "Vanadium: benchmark — %,d ticks in %.1fs, avg %.1f TPS, peak %,d TPS (best 1s window)",
            totalTicks, seconds, totalTicks / seconds, maxOf(peakWindowTicks, windowTicks)))
        source?.sendSuccess({ message }, true)
        source = null
    }
}
