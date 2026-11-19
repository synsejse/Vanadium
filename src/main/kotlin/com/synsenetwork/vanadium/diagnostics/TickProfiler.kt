package com.synsenetwork.vanadium.diagnostics

import com.google.gson.JsonObject
import com.synsenetwork.vanadium.Vanadium
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

/**
 * One bounded profile at a time, started/stopped and sampled exclusively on the server thread. Finished
 * profiles are published through [PerformanceResults] as well as reported in chat and the log.
 */
object TickProfiler {
    private class Capture(val source: CommandSourceStack, val requestedSeconds: Int) {
        val profile = TickProfile()
        val startedAt = System.nanoTime()
        val deadline = startedAt + requestedSeconds * 1_000_000_000L
    }

    private var active: Capture? = null
    private var tickStart = 0L
    private var completed: Component? = null

    fun start(commandSource: CommandSourceStack, seconds: Int): Boolean {
        if (active != null) return false
        active = Capture(commandSource, seconds)
        tickStart = 0
        return true
    }

    fun onTickStart() {
        val capture = active ?: return
        tickStart = System.nanoTime()
        Vanadium.scheduler.profile = capture.profile
    }

    fun onTickEnd() {
        val capture = active ?: return
        if (tickStart == 0L) return
        val now = System.nanoTime()
        capture.profile.recordTick(now - tickStart)
        tickStart = 0
        if (now >= capture.deadline || capture.profile.isFull()) stop()
    }

    fun stop(): Boolean {
        val capture = active ?: return false
        Vanadium.scheduler.profile = null
        active = null
        tickStart = 0
        val result = JsonObject()
        result.addProperty("requestedSeconds", capture.requestedSeconds)
        result.addProperty("seconds", (System.nanoTime() - capture.startedAt) / 1e9)
        result.add("profile", capture.profile.toJson())
        completed = PerformanceResults.report(capture.source, "profile", result)
        return true
    }

    fun lastReport(): Component = completed ?: Component.literal("Vanadium: no completed profile for this server")

    fun onServerStopped() {
        if (active != null) Vanadium.scheduler.profile = null
        active = null
        completed = null
        tickStart = 0
    }
}
