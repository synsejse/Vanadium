package com.synsenetwork.vanadium.integration

import com.mojang.serialization.JsonOps
import com.synsenetwork.vanadium.diagnostics.TickBenchmark
import com.synsenetwork.vanadium.diagnostics.TickProfiler
import com.synsenetwork.vanadium.live.runCommand
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.server.MinecraftServer

internal object ReportChecks {
    fun start(server: MinecraftServer) {
        val source = server.createCommandSourceStack()
        // The benchmark owns its own sprint and rejects an already-sprinting server.
        server.tickRateManager().stopSprinting()
        check(server.runCommand("vanadium profile 30") == 1)
        // Finish after one tick without waiting for the normal five-second command minimum.
        check(TickBenchmark.start(server, source, 0))
    }

    fun finish(server: MinecraftServer) {
        check(server.runCommand("vanadium profile stop") == 1)
        for (command in listOf("profile report", "profile report stages", "profile report worlds", "profile report details",
            "benchmark report", "benchmark report details")) {
            check(server.runCommand("vanadium $command") == 1)
        }
        check(!TickBenchmark.isActive)
        for (component in listOf(TickProfiler.lastReport(), TickBenchmark.lastReport())) {
            val encoded = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component).getOrThrow()
            val json = encoded.toString()
            check("run_command" in json && "show_text" in json && "copy_to_clipboard" in json) {
                "Report interactions did not survive component encoding: $json"
            }
            val decoded = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow()
            check(decoded.string == component.string)
        }
        println("REPORT_CHECKS_PASSED")
    }
}
