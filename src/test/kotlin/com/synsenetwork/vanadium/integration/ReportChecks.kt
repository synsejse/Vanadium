package com.synsenetwork.vanadium.integration

import com.mojang.serialization.JsonOps
import com.synsenetwork.vanadium.diagnostics.TickBenchmark
import com.synsenetwork.vanadium.diagnostics.TickProfiler
import com.synsenetwork.vanadium.live.runCommand
import java.util.concurrent.ConcurrentLinkedQueue
import net.minecraft.locale.Language
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.server.MinecraftServer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property

internal object ReportChecks {
    private val logger = LogManager.getLogger("VanadiumReportChecks")
    private var sprintReports: SprintReports? = null

    fun schedule(server: MinecraftServer, ticked: TickedChecks) {
        ticked.then("start command reports") { start(server) }
        ticked.then("command reports complete and retain their interactions") { finish(server) }
        ticked.await("vanilla sprint report only outside the benchmark", 20) { sprintReportsChecked() }
    }

    private fun start(server: MinecraftServer) {
        val source = server.createCommandSourceStack()
        // The benchmark owns its own sprint and rejects an already-sprinting server; the live run's own sprint
        // ends here, before the sprint reports are counted.
        server.tickRateManager().stopSprinting()
        val reports = SprintReports().also { sprintReports = it }
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
        checkNotNull(sprintReports).mark("end")
        println("REPORT_CHECKS_PASSED")
    }

    /** Once every mark has been logged: exactly the manual sprint printed vanilla's report, the benchmark nothing. */
    private fun sprintReportsChecked(): Boolean {
        val reports = checkNotNull(sprintReports)
        val seen = reports.seen.toList()
        if ("end" !in seen) return false
        reports.close()
        sprintReports = null
        check(seen == listOf("manual", SprintReports.REPORT, "benchmark", "end")) {
            "vanilla sprint reports: expected one for the manual /tick sprint and none for the benchmark, got $seen"
        }
        println("SPRINT_REPORT_CHECKS_PASSED manual=reported benchmark=muted")
        return true
    }

    /**
     * Records vanilla's sprint report as the server console logs it, in order with marks logged through the same
     * configuration, so a late asynchronous log event still lands before the mark that follows it.
     */
    private class SprintReports : AbstractAppender("VanadiumSprintReports", null, null, true, Property.EMPTY_ARRAY) {
        private val context = LogManager.getContext(false) as LoggerContext

        /** The report's text up to its first argument, in the server's language. */
        private val prefix = Language.getInstance().getOrDefault("commands.tick.sprint.report").substringBefore('%')
        val seen = ConcurrentLinkedQueue<String>()

        init {
            check(prefix.isNotBlank()) { "vanilla sprint report text missing" }
            start()
            context.configuration.rootLogger.addAppender(this, null, null)
            context.updateLoggers()
        }

        fun mark(name: String) = logger.info("{}{}", MARK, name)

        override fun append(event: LogEvent) {
            val message = event.message.formattedMessage
            when {
                message.startsWith(MARK) -> seen.add(message.removePrefix(MARK))
                prefix in message -> seen.add(REPORT)
            }
        }

        fun close() {
            context.configuration.rootLogger.removeAppender(name)
            context.updateLoggers()
            stop()
        }

        companion object {
            const val REPORT = "report"
            const val MARK = "VANADIUM_SPRINT_REPORT_MARK "
        }
    }
}
