package com.synsenetwork.vanadium.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixedBenchSettingsTest {
    private fun settings(vararg properties: Pair<String, String>) = MixedBenchSettings(mapOf(*properties)::get)

    @Test
    fun defaultsDescribeAPacedEnabledRunOnGeneratedTerrain() {
        val settings = settings()
        assertEquals("enabled", settings.mode)
        assertEquals("1200", settings.systemProperties["vanadium.mixed.window"])
        assertEquals("false", settings.systemProperties["vanadium.mixed.sprint"])
        assertEquals(listOf("-Xms4g", "-Xmx4g", "-XX:+UseG1GC"), settings.jvmArgs)
        val live = settings.liveSettings
        assertEquals("minecraft:normal", live.serverProperties["level-type"])
        assertEquals("8", live.serverProperties["max-players"])
        assertEquals("true", live.vanadium["enabled"])
        assertEquals(null, live.c2meParallelism)
        assertEquals(15L + 7600 / 600, settings.timeoutMinutes)
    }

    @Test
    fun serialModeDisablesVanadiumAndKeepsEveryOtherSetting() {
        val enabled = settings("mixed.workers" to "24", "mixed.c2me" to "8", "mixed.jvmArgs" to " -XX:+AlwaysPreTouch  -XX:+UseTransparentHugePages ")
        val serial = settings("mixed.mode" to "serial", "mixed.workers" to "24", "mixed.c2me" to "8",
            "mixed.jvmArgs" to " -XX:+AlwaysPreTouch  -XX:+UseTransparentHugePages ")
        assertEquals("false", serial.liveSettings.vanadium["enabled"])
        assertEquals(enabled.liveSettings.vanadium - "enabled", serial.liveSettings.vanadium - "enabled")
        assertEquals(enabled.liveSettings.serverProperties, serial.liveSettings.serverProperties)
        assertEquals(8, serial.liveSettings.c2meParallelism)
        assertEquals(listOf("-XX:+AlwaysPreTouch", "-XX:+UseTransparentHugePages"), serial.jvmArgs.drop(3))
    }

    @Test
    fun serialSpawningArmWritesOnlyItsKey() {
        val auto = settings("mixed.workers" to "24")
        assertEquals("true", auto.systemProperties["vanadium.mixed.parallelSpawning"])
        assertFalse("parallelSpawning" in auto.liveSettings.vanadium, "default runs keep their earlier vanadium.toml")
        val serialSpawning = settings("mixed.workers" to "24", "mixed.parallelSpawning" to "false")
        assertEquals("false", serialSpawning.systemProperties["vanadium.mixed.parallelSpawning"])
        assertEquals(auto.liveSettings.vanadium + ("parallelSpawning" to "false"), serialSpawning.liveSettings.vanadium)
        assertEquals("true", serialSpawning.liveSettings.vanadium["enabled"])
        assertFailsWith<IllegalArgumentException> { settings("mixed.mode" to "serial", "mixed.parallelSpawning" to "false") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.parallelSpawning" to "no") }
    }

    @Test
    fun collectorIsChosenOnceAndGcLoggingIsOptIn() {
        assertEquals(200, settings().jfrLeadTicks)
        assertEquals("200", settings().systemProperties["vanadium.mixed.jfrLead"])
        assertEquals("1", settings("mixed.jfrLead" to "1").systemProperties["vanadium.mixed.jfrLead"])
        assertTrue(settings().jvmArgs.none { it.startsWith("-Xlog") })
        val zgc = settings("mixed.gc" to "zgc", "mixed.gcLog" to "true", "mixed.jvmArgs" to "-XX:+AlwaysPreTouch")
        assertEquals(listOf("-Xms4g", "-Xmx4g", "-XX:+UseZGC", MixedBenchSettings.GC_LOG, "-XX:+AlwaysPreTouch"), zgc.jvmArgs)
        assertEquals(1, zgc.jvmArgs.count { it.matches(Regex("-XX:\\+Use\\w+GC")) })
        assertTrue("uptimenanos" in MixedBenchSettings.GC_LOG && "file=gc.log" in MixedBenchSettings.GC_LOG)
        assertEquals("-XX:+UseG1GC", settings("mixed.gc" to "g1", "mixed.gcLog" to "false").jvmArgs[2])
        // A second collector would make the JVM refuse to start; the choice belongs to mixed.gc.
        for (flag in listOf("-XX:+UseZGC", "-XX:+UseG1GC", "-XX:-UseG1GC", "-XX:+UseParallelGC", "-XX:+UseShenandoahGC")) {
            assertFailsWith<IllegalArgumentException>(flag) { settings("mixed.jvmArgs" to "-XX:+AlwaysPreTouch $flag") }
        }
        assertEquals(listOf("-XX:+UseStringDeduplication", "-XX:ZUncommitDelay=60"),
            settings("mixed.gc" to "zgc", "mixed.jvmArgs" to "-XX:+UseStringDeduplication -XX:ZUncommitDelay=60").extraJvmArgs)
    }

    @Test
    fun requestedGcLogCannotBeRedirectedOrDisabled() {
        for (flag in listOf("-Xloggc:gc.log", "-Xlog:gc:file=gc.log", "-Xlog:all=off:file=./gc.log", "-Xlog:disable")) {
            assertFailsWith<IllegalArgumentException>(flag) { settings("mixed.gcLog" to "true", "mixed.jvmArgs" to flag) }
            // Without mixed.gcLog the flags are the user's own logging.
            assertEquals(listOf(flag), settings("mixed.jvmArgs" to flag).extraJvmArgs)
        }
        assertEquals(listOf("-Xlog:jit+compilation=debug:file=jit.log"),
            settings("mixed.gcLog" to "true", "mixed.jvmArgs" to "-Xlog:jit+compilation=debug:file=jit.log").extraJvmArgs)
    }

    @Test
    fun expectedOutputsCoverEveryTickWindowAndRequestedLog() {
        val outputs = settings("mixed.warmup" to "0", "mixed.window" to "20", "mixed.windows" to "3").expectedOutputs()
        assertEquals(4L, outputs["mixed-bench/windows.csv"])
        assertEquals(61L, outputs["mixed-bench/ticks.csv"])
        assertEquals(7L, outputs["mixed-bench/boundaries.csv"])
        assertTrue("vanadium/results/mixed-bench-*.json" in outputs)
        assertFalse("gc.log" in outputs)
        assertEquals(1L, settings("mixed.gcLog" to "true").expectedOutputs()["gc.log"])
    }

    @Test
    fun rejectsInvalidValuesBeforeLaunch() {
        assertFailsWith<IllegalArgumentException> { settings("mixed.mode" to "off") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.window" to "0") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.players" to "eight") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.sprint" to "yes") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.heap" to "4") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.gc" to "shenandoah") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.gcLog" to "1") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.jfrLead" to "-1") }
        // No lead would start JFR inside the first window's first gap.
        assertFailsWith<IllegalArgumentException> { settings("mixed.jfrLead" to "0") }
    }
}
