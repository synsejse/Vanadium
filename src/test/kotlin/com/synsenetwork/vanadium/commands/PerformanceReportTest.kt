package com.synsenetwork.vanadium.commands

import com.google.gson.JsonParser
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerformanceReportTest {
    private fun profile(file: String? = "vanadium/results/profile.json") = PerformanceReport("profile", JsonParser.parseString("""
        {"seconds":30,"profile":{
          "ticks":{"count":600,"totalMs":12000,"avgMs":20,"p50Ms":18,"p95Ms":42,"p99Ms":55,"percentileSamples":600},
          "split":{"stageWaves":0.8,"preparation":0.05,"serial":0.15},
          "stages":{"SPAWNING":{"execution":{"count":1800,"totalMs":3600,"avgMs":2},
            "tasks":120000,"regions":60000,"waves":600,"maxTasksPerRegion":8,"utilization":0.05,"callerTailWaitMs":300,
            "regionExecution":{"count":60000,"avgMs":0.01,"p50Ms":0.005,"p95Ms":0.02,"p99Ms":0.1,"percentileSamples":60000}}},
          "preparations":{"CHUNK_BATCH":{"jobs":600,"duration":{"count":600,"totalMs":300,"avgMs":0.5,"p50Ms":0.4,"p95Ms":1,"p99Ms":2,"percentileSamples":600}}},
          "phases":{"minecraft:overworld / SPAWNING execution":{"totalMs":3600},
            "minecraft:overworld / world total":{"totalMs":12000},"minecraft:the_end / world total":{"totalMs":0}}
        }}
    """).asJsonObject, "12 helpers + server thread · isolation 2", file)

    private fun parts(component: Component): List<Component> = listOf(component) + component.siblings.flatMap(::parts)

    @Test
    fun summaryUsesServerTicksInsteadOfDimensionInvocationsAndKeepsDetailsInteractive() {
        val summary = profile().view()
        assertEquals(4, summary.string.lines().size)
        assertTrue("Spawning/thunder 6.00 ms" in summary.string, summary.string)
        assertFalse("Spawning/thunder 2.00 ms" in summary.string)
        val commands = parts(summary).mapNotNull { (it.style.clickEvent as? ClickEvent.RunCommand)?.command }
        assertEquals(setOf("/vanadium profile report stages", "/vanadium profile report worlds", "/vanadium profile report details"), commands.toSet())
        assertTrue(parts(summary).any { it.style.hoverEvent is HoverEvent.ShowText })
        assertTrue(parts(summary).any { (it.style.clickEvent as? ClickEvent.CopyToClipboard)?.value == "vanadium/results/profile.json" })
    }

    @Test
    fun worldAndStageViewsKeepNestedTimingsSeparate() {
        val report = profile()
        assertTrue("minecraft:overworld · 20.00 ms" in report.view("worlds").string)
        assertTrue("minecraft:the_end · 0.00 ms" in report.view("worlds").string)
        assertTrue("Spawning/thunder 6.00 ms" in report.view("stages").string)
        assertTrue("CHUNK_BATCH · 0.50 ms/tick" in report.view("details").string)
        assertFalse(parts(profile(null).view()).any { it.style.clickEvent is ClickEvent.CopyToClipboard })
    }

    @Test
    fun benchmarkSeparatesThroughputFromTickWorkAndPreservesWindowMeaning() {
        val data = JsonParser.parseString("""{"seconds":10,"ticks":1000,"avgTps":100,"peakTps":130}""").asJsonObject
        val report = PerformanceReport("benchmark", data, "4 helpers + server thread · isolation 2", "benchmark.json")
        assertEquals(3, report.view().string.lines().size)
        val details = report.view("details").string
        assertTrue("elapsed/tick 10.00 ms" in details)
        assertTrue("Best window: 130 ticks" in details)
        assertFalse("130 TPS" in details)
        assertTrue("not profiled tick work" in details)
        assertTrue(parts(report.view()).any { (it.style.clickEvent as? ClickEvent.RunCommand)?.command == "/vanadium benchmark report details" })
    }
}
