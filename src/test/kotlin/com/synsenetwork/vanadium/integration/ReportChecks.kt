package com.synsenetwork.vanadium.integration

import com.mojang.serialization.JsonOps
import com.synsenetwork.vanadium.diagnostics.TickBenchmark
import com.synsenetwork.vanadium.diagnostics.TickProfiler
import com.synsenetwork.vanadium.live.runCommand
import java.nio.file.Files
import net.minecraft.locale.Language
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.server.MinecraftServer

internal object ReportChecks {
    private var sprintReports: LogCapture? = null

    fun schedule(server: MinecraftServer, ticked: TickedChecks) {
        ticked.then("start command reports") { start(server) }
        ticked.then("command reports complete with their result files") { finish(server) }
        ticked.await("vanilla sprint report only outside the benchmark", 20) { sprintReportsChecked() }
    }

    private fun start(server: MinecraftServer) {
        val source = server.createCommandSourceStack()
        // The benchmark owns its own sprint and rejects an already-sprinting server; the live run's own sprint
        // ends here, before the sprint reports are counted.
        server.tickRateManager().stopSprinting()
        val reports = captureSprintReports().also { sprintReports = it }
        reports.mark("manual")
        // A manual /tick sprint keeps vanilla's "Sprint completed" line.
        check(server.runCommand("tick sprint 20") == 1)
        check(server.runCommand("tick sprint stop") == 1)
        reports.mark("benchmark")
        check(server.runCommand("vanadium profile 30") == 1)
        // Finish after one tick without waiting for the normal five-second command minimum.
        check(TickBenchmark.start(server, source, 0))
    }

    private fun finish(server: MinecraftServer) {
        check(server.runCommand("vanadium profile stop") == 1)
        check(server.runCommand("vanadium profile report") == 1)
        check(server.runCommand("vanadium benchmark report") == 1)
        check(!TickBenchmark.isActive)
        for ((kind, summary) in listOf("profile" to TickProfiler.lastReport(), "benchmark" to TickBenchmark.lastReport())) {
            val encoded = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, summary).getOrThrow()
            val json = encoded.toString()
            check("copy_to_clipboard" in json && "show_text" in json) { "The $kind summary's path lost its interactions: $json" }
            val text = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().string
            check(text == summary.string && text.startsWith("Vanadium $kind · ")) { "Unexpected $kind summary: $text" }
            // The summary is short and ends with the path of the result it wrote.
            val path = text.lines().last().removePrefix("Result: ")
            check(text.lines().size <= 3 && path.endsWith(".json")) { "Unexpected $kind summary: $text" }
            val result = server.serverDirectory.resolve(path)
            check(Files.isRegularFile(result) && "\"kind\": \"$kind\"" in Files.readString(result)) { "$kind result missing at $result" }
        }
        checkNotNull(sprintReports).mark("end")
        println("REPORT_CHECKS_PASSED")
    }

    /** Once every mark has been logged: exactly the manual sprint printed vanilla's report, the benchmark nothing. */
    private fun sprintReportsChecked(): Boolean {
        val reports = checkNotNull(sprintReports)
        if (!reports.marked("end")) return false
        val seen = reports.until("end").map { it.removePrefix(LogCapture.MARK) }
        reports.close()
        sprintReports = null
        check(seen == listOf("manual", REPORT, "benchmark")) {
            "vanilla sprint reports: expected one for the manual /tick sprint and none for the benchmark, got $seen"
        }
        println("SPRINT_REPORT_CHECKS_PASSED manual=reported benchmark=muted")
        return true
    }

    /** Vanilla's sprint report as the server console logs it, matched up to its first argument in the server's language. */
    private fun captureSprintReports(): LogCapture {
        val prefix = Language.getInstance().getOrDefault("commands.tick.sprint.report").substringBefore('%')
        check(prefix.isNotBlank()) { "vanilla sprint report text missing" }
        return LogCapture { message -> if (prefix in message) REPORT else null }
    }

    private const val REPORT = "report"
}
