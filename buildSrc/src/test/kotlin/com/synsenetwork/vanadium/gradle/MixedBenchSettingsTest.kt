package com.synsenetwork.vanadium.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class MixedBenchSettingsTest {
    @TempDir lateinit var directory: Path

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
    fun rejectsInvalidValuesBeforeLaunch() {
        assertFailsWith<IllegalArgumentException> { settings("mixed.mode" to "off") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.window" to "0") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.players" to "eight") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.sprint" to "yes") }
        assertFailsWith<IllegalArgumentException> { settings("mixed.heap" to "4") }
    }

    @Test
    fun liveRunWritesFixtureSettingsOverTheIsolatedDefaultsAndRefusesReuse() {
        val eula = directory.resolve("eula.txt").toFile().apply { writeText("eula=true\n") }
        val fixture = directory.resolve("fixture.jar").toFile().apply { writeText("jar") }
        val run = directory.resolve("run").toFile()
        val task = ProjectBuilder.builder().withProjectDir(directory.toFile()).build().tasks.register("live").get()
        val prepare = PrepareLiveRun(run, false, eula, emptyList(), fixture, "mixedBench", settings("mixed.mode" to "serial").liveSettings)
        prepare.execute(task)
        val properties = run.resolve("server.properties").readLines()
        assertTrue("level-type=minecraft:normal" in properties)
        assertTrue("max-tick-time=300000" in properties)
        assertTrue("server-ip=127.0.0.1" in properties)
        assertEquals(1, properties.count { it.startsWith("level-type=") })
        assertEquals("enabled = false\nworkers = 0\nisolation = 2\n", run.resolve("config/vanadium.toml").readText())
        assertFalse(run.resolve("config/c2me.toml").exists())
        assertTrue(run.resolve("mods/fixture.jar").isFile)
        assertFailsWith<IllegalStateException> { prepare.execute(task) }
    }

    @Test
    fun otherFixturesKeepTheirPreviousLaunchFiles() {
        val eula = directory.resolve("eula.txt").toFile().apply { writeText("eula=true\n") }
        val fixture = directory.resolve("fixture.jar").toFile().apply { writeText("jar") }
        val run = directory.resolve("run").toFile()
        val task = ProjectBuilder.builder().withProjectDir(directory.toFile()).build().tasks.register("live").get()
        PrepareLiveRun(run, false, eula, emptyList(), fixture, "integration").execute(task)
        val properties = run.resolve("server.properties").readLines()
        assertTrue("level-type=minecraft:flat" in properties)
        assertTrue("view-distance=4" in properties)
        assertTrue("max-tick-time=60000" in properties)
        assertEquals("workers = 4\nisolation = 2\n", run.resolve("config/vanadium.toml").readText())
        assertEquals("version = 3\nglobalExecutorParallelism = 4\n", run.resolve("config/c2me.toml").readText())
    }
}
