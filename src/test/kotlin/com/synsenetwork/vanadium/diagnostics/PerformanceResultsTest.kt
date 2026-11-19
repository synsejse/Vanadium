package com.synsenetwork.vanadium.diagnostics

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals

class PerformanceResultsTest {
    @Test
    fun profileSummaryAveragesStagesPerServerTickNotPerDimension() {
        val result = JsonParser.parseString("""
            {"seconds":30,"profile":{
              "ticks":{"count":600,"totalMs":12000,"avgMs":20,"p50Ms":18,"p95Ms":42,"p99Ms":55,"percentileSamples":600},
              "stages":{
                "SPAWNING":{"execution":{"count":1800,"totalMs":3600,"avgMs":2},"tasks":120000},
                "ENTITY":{"execution":{"count":1800,"totalMs":1200,"avgMs":0.67},"tasks":5000},
                "CHUNK":{"execution":{"count":1800,"totalMs":2400,"avgMs":1.33},"tasks":9000},
                "TRACKING":{"execution":{"count":1800,"totalMs":600,"avgMs":0.33},"tasks":100},
                "BLOCK_ENTITY":{"execution":{"count":0,"totalMs":0,"avgMs":0},"tasks":0}}
            }}
        """).asJsonObject
        assertEquals("600 ticks in 30.0s · tick avg 20.00 ms, p95 42.00 ms\n" +
            "Slowest stages, ms per tick: SPAWNING 6.00 · CHUNK 4.00 · ENTITY 2.00", PerformanceResults.summary("profile", result))
    }

    @Test
    fun emptyProfileSaysSo() {
        val result = JsonParser.parseString("""{"seconds":1.25,"profile":{"ticks":{"count":0},"stages":{}}}""").asJsonObject
        assertEquals("no ticks captured in 1.3s", PerformanceResults.summary("profile", result))
    }

    @Test
    fun benchmarkSummaryKeepsTheBestWindowInTicks() {
        val result = JsonParser.parseString("""{"seconds":10,"ticks":1000,"avgTps":100,"peakTps":130}""").asJsonObject
        assertEquals("100.0 TPS uncapped average · 1000 ticks in 10.0s · best ~1s window 130 ticks",
            PerformanceResults.summary("benchmark", result))
    }
}
