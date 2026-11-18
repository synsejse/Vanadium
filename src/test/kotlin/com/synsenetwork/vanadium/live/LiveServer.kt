package com.synsenetwork.vanadium.live

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import jdk.jfr.Configuration
import jdk.jfr.Recording
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path

/** Completes a live fixture only after real ticks, a flushed save and normal server shutdown. */
object LiveServer {
    fun start() {
        if (!java.lang.Boolean.getBoolean("vanadium.live.jfr")) return
        val recording = recording()
        recording.start()
        ServerLifecycleEvents.SERVER_STOPPED.register {
            recording.stop()
            recording.dump(Path.of("live.jfr"))
            recording.close()
        }
    }

    /** The live JFR settings: profile events plus 100µs monitor/park thresholds and 5 ms execution samples. */
    fun recording() = Recording(Configuration.getConfiguration("profile")).apply {
        enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofNanos(100_000))
        enable("jdk.ThreadPark").withThreshold(Duration.ofNanos(100_000))
        enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5))
    }

    fun finish(server: MinecraftServer, mode: String, settleTicks: Int = 20) {
        require(settleTicks > 0)
        check(System.getProperty("vanadium.live.mode") == mode)
        check(server.commands.dispatcher.execute("vanadium status", server.createCommandSourceStack()) == 1) {
            "Vanadium status command was not registered or failed"
        }
        var ticks = 0
        var saved = false
        var halted = 0L
        ServerTickEvents.END_SERVER_TICK.register { current ->
            if (current === server && ++ticks == settleTicks) {
                val start = System.nanoTime()
                server.saveEverything(false, true, true)
                saved = true
                halted = System.nanoTime()
                println("LIVE_SAVE_DONE elapsedMs=${(halted - start) / 1_000_000}")
                server.halt(false)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { current ->
            if (current === server && saved) {
                // The halt request to a stopped server: vanilla's shutdown, including its own final save.
                println("LIVE_STOP_DONE elapsedMs=${(System.nanoTime() - halted) / 1_000_000}")
                Files.writeString(Path.of("vanadium-live-passed.txt"), mode)
            }
        }
    }
}
