package com.synsenetwork.vanadium.commands

import com.google.gson.JsonObject
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.TickProfile
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

/**
 * One bounded profile at a time, started/stopped and sampled exclusively on the server thread. Finished
 * profiles are published through [Results] as well as reported in chat and the log.
 */
object TickProfiler {
    private var active: TickProfile? = null
    private var source: CommandSourceStack? = null
    private var requestedSeconds = 0
    private var startedAt = 0L
    private var deadline = 0L
    private var tickStart = 0L
    private var configuration = ""
    private var lastReport: PerformanceReport? = null

    fun start(commandSource: CommandSourceStack, seconds: Int): Boolean {
        if (active != null) return false
        active = TickProfile()
        source = commandSource
        requestedSeconds = seconds
        startedAt = System.nanoTime()
        deadline = startedAt + seconds * 1_000_000_000L
        tickStart = 0
        configuration = "workers=${Vanadium.scheduler.workerCount}, isolation=${Vanadium.scheduler.isolation}; " +
                "keep configuration fixed during capture"
        return true
    }

    fun onTickStart() {
        val profile = active ?: return
        tickStart = System.nanoTime()
        Vanadium.scheduler.profile = profile
    }

    fun onTickEnd() {
        val profile = active ?: return
        if (tickStart == 0L) return
        val now = System.nanoTime()
        profile.recordTick(now - tickStart)
        tickStart = 0
        if (now >= deadline || profile.isFull()) stop()
    }

    fun stop(): Boolean {
        val profile = active ?: return false
        Vanadium.scheduler.profile = null
        val recipient = source
        val result = JsonObject().apply {
            addProperty("requestedSeconds", requestedSeconds)
            addProperty("seconds", (System.nanoTime() - startedAt) / 1e9)
            add("profile", profile.toJson())
        }
        val file = recipient?.let { Results.publish(it.server, "profile", result) }
        val report = PerformanceReport("profile", result, configuration,
            if (file == null) null else Results.describe(recipient.server, file))
        lastReport = report
        active = null
        source = null
        tickStart = 0
        val summary = report.view()
        Vanadium.LOGGER.info(summary.string)
        recipient?.sendSuccess({ summary }, false)
        return true
    }

    fun lastReport(section: String = "summary"): Component = lastReport?.view(section)
        ?: Component.literal("Vanadium: no completed profile for this server")

    fun onServerStopped() {
        if (active != null) Vanadium.scheduler.profile = null
        active = null
        source = null
        lastReport = null
        configuration = ""
        tickStart = 0
    }
}
