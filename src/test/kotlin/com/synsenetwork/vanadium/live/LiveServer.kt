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
        val recording = Recording(Configuration.getConfiguration("profile"))
        recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofNanos(100_000))
        recording.enable("jdk.ThreadPark").withThreshold(Duration.ofNanos(100_000))
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5))
        recording.start()
        ServerLifecycleEvents.SERVER_STOPPED.register {
            recording.stop()
            recording.dump(Path.of("live.jfr"))
            recording.close()
        }
    }

    fun finish(server: MinecraftServer, mode: String) {
        check(System.getProperty("vanadium.live.mode") == mode)
        check(server.commands.dispatcher.execute("vanadium status", server.createCommandSourceStack()) == 1) {
            "Vanadium status command was not registered or failed"
        }
        var ticks = 0
        var saved = false
        ServerTickEvents.END_SERVER_TICK.register { current ->
            if (current === server && ++ticks == 20) {
                server.saveEverything(false, true, true)
                saved = true
                server.halt(false)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { current ->
            if (current === server && saved) Files.writeString(Path.of("vanadium-live-passed.txt"), mode)
        }
    }
}
