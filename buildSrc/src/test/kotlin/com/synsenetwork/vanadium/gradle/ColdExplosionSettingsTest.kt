package com.synsenetwork.vanadium.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColdExplosionSettingsTest {
    private fun settings(vararg properties: Pair<String, String>) = ColdExplosionSettings(mapOf(*properties)::get)

    @Test
    fun defaultsDescribeAnEnabledShamArmWithoutClassLogging() {
        val settings = settings()
        assertEquals("sham", settings.systemProperties["vanadium.cold.arm"])
        assertEquals("8", settings.systemProperties["vanadium.cold.blasts"])
        assertEquals(listOf("-Xms2g", "-Xmx2g", "-XX:+UseG1GC"), settings.jvmArgs)
        assertEquals("true", settings.liveSettings.vanadium["enabled"])
        assertEquals("0", settings.liveSettings.vanadium["workers"])
        assertEquals(1L + 8, settings.expectedOutputs()["cold-explosion/blasts.csv"])
        assertFalse("class-load.log" in settings.expectedOutputs())
    }

    @Test
    fun armsDifferOnlyInTheArmAndTheWarmupBlastRow() {
        val sham = settings("cold.blasts" to "4")
        val warmup = settings("cold.blasts" to "4", "cold.arm" to "warmup")
        assertEquals(sham.liveSettings, warmup.liveSettings)
        assertEquals(sham.jvmArgs, warmup.jvmArgs)
        assertEquals(sham.systemProperties - "vanadium.cold.arm", warmup.systemProperties - "vanadium.cold.arm")
        assertEquals(6L, warmup.expectedOutputs()["cold-explosion/blasts.csv"])
        assertEquals("false", settings("cold.mode" to "serial").liveSettings.vanadium["enabled"])
    }

    @Test
    fun classLoggingAddsTheUptimeDecoratedLogAndItsOutputs() {
        val settings = settings("cold.classLog" to "true", "cold.arm" to "load")
        assertTrue(ColdExplosionSettings.CLASS_LOG in settings.jvmArgs)
        assertTrue(settings.jvmArgs.last().endsWith(":uptimenanos"))
        assertEquals(1L, settings.expectedOutputs()["cold-explosion/blast-classes.csv"])
        assertEquals(1L, settings.expectedOutputs()["class-load.log"])
    }

    @Test
    fun invalidValuesAreRejectedBeforeLaunch() {
        assertFailsWith<IllegalArgumentException> { settings("cold.arm" to "tnt") }
        assertFailsWith<IllegalArgumentException> { settings("cold.blasts" to "12") }
        assertFailsWith<IllegalArgumentException> { settings("cold.blasts" to "1") }
        assertFailsWith<IllegalArgumentException> { settings("cold.spacing" to "30") }
        assertFailsWith<IllegalArgumentException> { settings("cold.classLog" to "yes") }
        assertFailsWith<IllegalArgumentException> { settings("cold.mode" to "off") }
    }
}
