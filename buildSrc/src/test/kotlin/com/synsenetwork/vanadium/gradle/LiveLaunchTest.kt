package com.synsenetwork.vanadium.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class LiveLaunchTest {
    @TempDir lateinit var directory: Path

    private fun launch(fixture: LiveFixture, vararg properties: Pair<String, String>) = LiveLaunch.resolve(fixture, mapOf(*properties)::get)

    @Test
    fun integrationKeepsItsLaunchValues() {
        val launch = launch(LiveFixture.INTEGRATION, "live.ticks" to "800")
        assertEquals(mapOf("vanadium.live.mode" to "integration", "vanadium.live.jfr" to "false", "vanadium.live.ticks" to "800",
            "vanadium.live.sprint" to "true"), launch.systemProperties)
        assertEquals(listOf("-Xms2g", "-Xmx2g", "-XX:+UseG1GC"), launch.jvmArgs)
        assertEquals(LiveSettings(), launch.settings)
        assertEquals(5L, launch.timeoutMinutes)
        assertTrue(launch.outputs.isEmpty())
        assertFalse(launch.jfr)
        assertTrue(launch(LiveFixture.INTEGRATION, "live.jfr" to "true").jfr)
    }

    @Test
    fun benchmarkLaunchesCarryTheirSettings() {
        val mixed = launch(LiveFixture.MIXED_BENCH, "mixed.mode" to "serial", "live.jfr" to "true")
        val settings = MixedBenchSettings(mapOf("mixed.mode" to "serial")::get)
        assertEquals(mapOf("vanadium.live.mode" to "mixedBench", "vanadium.live.jfr" to "true") + settings.systemProperties, mixed.systemProperties)
        assertEquals(settings.jvmArgs, mixed.jvmArgs)
        assertEquals(settings.liveSettings, mixed.settings)
        assertEquals(settings.expectedOutputs(), mixed.outputs)
        assertEquals(settings.timeoutMinutes, mixed.timeoutMinutes)
        assertEquals(15L, launch(LiveFixture.MIXED_BENCH, "mixed.warmup" to "0", "mixed.window" to "20", "mixed.windows" to "1").timeoutMinutes)

        val cold = launch(LiveFixture.COLD_EXPLOSION, "cold.arm" to "warmup", "cold.classLog" to "true")
        val coldSettings = ColdExplosionSettings(mapOf("cold.arm" to "warmup", "cold.classLog" to "true")::get)
        assertEquals(coldSettings.jvmArgs, cold.jvmArgs)
        assertEquals("warmup", cold.systemProperties["vanadium.cold.arm"])
        assertEquals(coldSettings.expectedOutputs(), cold.outputs)
        assertEquals(10L, cold.timeoutMinutes)
    }

    @Test
    fun featureBenchExpectsSamplesForEveryRoundAndOptionalRecordings() {
        val launch = launch(LiveFixture.FEATURE_BENCH, "bench.filter" to "spawn-.*", "bench.rounds" to "2", "bench.scale" to "0.1")
        assertEquals(mapOf("vanadium.live.mode" to "featureBench", "vanadium.live.jfr" to "false", "vanadium.bench.workers" to "4",
            "vanadium.bench.rounds" to "2", "vanadium.bench.scale" to "0.1", "vanadium.bench.filter" to "spawn-.*", "vanadium.bench.jfr" to "true"),
            launch.systemProperties)
        assertEquals(listOf("-Xms2g", "-Xmx2g", "-XX:+UseG1GC"), launch.jvmArgs)
        assertEquals(20L, launch.timeoutMinutes)
        assertEquals("-1", launch.settings.serverProperties["max-tick-time"])
        assertEquals(LiveSettings().vanadium, launch.settings.vanadium)
        assertEquals(mapOf("feature-bench/environment.txt" to 1L, "feature-bench/samples.csv" to 5L, "feature-bench/*-off.jfr" to 1L,
            "feature-bench/*-on.jfr" to 1L), launch.outputs)
        assertEquals(setOf("feature-bench/environment.txt", "feature-bench/samples.csv"),
            launch(LiveFixture.FEATURE_BENCH, "bench.jfr" to "false").outputs.keys)
        assertEquals(25L, launch(LiveFixture.FEATURE_BENCH).outputs["feature-bench/samples.csv"])
        assertFailsWith<IllegalArgumentException> { launch(LiveFixture.FEATURE_BENCH, "bench.rounds" to "two") }
        assertFailsWith<IllegalArgumentException> { launch(LiveFixture.FEATURE_BENCH, "bench.jfr" to "yes") }
    }

    @Test
    fun malformedBenchmarkPropertiesFailOnlyTheirOwnFixture() {
        val malformed = arrayOf("mixed.window" to "0", "cold.arm" to "tnt", "bench.rounds" to "-1")
        assertFailsWith<IllegalArgumentException> { launch(LiveFixture.MIXED_BENCH, *malformed) }
        assertFailsWith<IllegalArgumentException> { launch(LiveFixture.COLD_EXPLOSION, *malformed) }
        assertFailsWith<IllegalArgumentException> { launch(LiveFixture.FEATURE_BENCH, *malformed) }
        assertEquals(launch(LiveFixture.INTEGRATION), launch(LiveFixture.INTEGRATION, *malformed))
    }

    @Test
    fun verifierRequiresEveryExpectedOutput() {
        val run = directory.resolve("run").toFile().apply { resolve("logs").mkdirs(); resolve("mixed-bench").mkdirs() }
        run.resolve("logs/latest.log").writeText("[Server thread/INFO]: done\n")
        run.resolve("vanadium-live-passed.txt").writeText("mixedBench")
        val outputs = mapOf("mixed-bench/windows.csv" to 3L, "results/mixed-bench-*.json" to 1L, "gc.log" to 1L)
        val launch = LiveLaunch("mixedBench", 10, jfr = false, outputs = outputs)
        assertFailsWith<IllegalStateException> { launch.verify(run) }
        run.resolve("mixed-bench/windows.csv").writeText("header\n0\n")
        run.resolve("results").mkdirs()
        run.resolve("results/mixed-bench-2026.json").writeText("{}\n")
        run.resolve("gc.log").writeText("[0.001s] gc\n")
        // One window row short.
        assertFailsWith<IllegalStateException> { launch.verify(run) }
        run.resolve("mixed-bench/windows.csv").appendText("1\n")
        launch.verify(run)
        run.resolve("gc.log").delete()
        assertFailsWith<IllegalStateException> { launch.verify(run) }
        run.resolve("gc.log").writeText("[0.001s] gc\n")
        // An empty recording is not a recording.
        run.resolve("live.jfr").createNewFile()
        assertFailsWith<IllegalStateException> { launch.copy(jfr = true).verify(run) }
        // A pass marker from another fixture does not count.
        assertFailsWith<IllegalStateException> { launch.copy(mode = "integration").verify(run) }
    }

    @Test
    fun featureBenchVerifierAcceptsTheFixtureFileNames() {
        val run = directory.resolve("run").toFile().apply { resolve("logs").mkdirs(); resolve("feature-bench").mkdirs() }
        run.resolve("logs/latest.log").writeText("[Server thread/INFO]: FEATURE_BENCH_PASSED\n")
        run.resolve("vanadium-live-passed.txt").writeText("featureBench")
        run.resolve("feature-bench/environment.txt").writeText("java=25\nworkers=4\n")
        val header = "case,enabled,round,operations,wall_ns,caller_cpu_ns,worker_cpu_ns,allocated_bytes\n"
        // An unmatched filter leaves the header alone.
        run.resolve("feature-bench/samples.csv").writeText(header)
        val launch = launch(LiveFixture.FEATURE_BENCH, "bench.rounds" to "1")
        assertFailsWith<IllegalStateException> { launch.verify(run) }
        run.resolve("feature-bench/samples.csv").appendText("spawn-x,false,0,1,1,1,1,1\nspawn-x,true,0,1,1,1,1,1\n")
        assertFailsWith<IllegalStateException> { launch.verify(run) }
        run.resolve("feature-bench/spawn-x-off.jfr").writeBytes(byteArrayOf(1, 2))
        assertFailsWith<IllegalStateException> { launch.verify(run) }
        run.resolve("feature-bench/spawn-x-on.jfr").writeBytes(byteArrayOf(1, 2))
        launch.verify(run)
        launch(LiveFixture.FEATURE_BENCH, "bench.rounds" to "1", "bench.jfr" to "false").verify(run)
    }

    @Test
    fun liveRunWritesFixtureSettingsOverTheIsolatedDefaultsAndRefusesReuse() {
        val eula = directory.resolve("eula.txt").toFile().apply { writeText("eula=true\n") }
        val fixture = directory.resolve("fixture.jar").toFile().apply { writeText("jar") }
        val run = directory.resolve("run").toFile()
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val task = project.tasks.register("live").get()
        val settings = project.provider { launch(LiveFixture.MIXED_BENCH, "mixed.mode" to "serial").settings }
        val prepare = PrepareLiveRun(run, false, eula, emptyList(), fixture, settings)
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
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val task = project.tasks.register("live").get()
        for ((name, maxTickTime) in listOf(LiveFixture.INTEGRATION to "60000", LiveFixture.FEATURE_BENCH to "-1")) {
            val run = directory.resolve(name.runName).toFile()
            PrepareLiveRun(run, false, eula, emptyList(), fixture, project.provider { launch(name).settings }).execute(task)
            val properties = run.resolve("server.properties").readLines()
            assertTrue("level-type=minecraft:flat" in properties)
            assertTrue("view-distance=4" in properties)
            assertEquals(listOf("max-tick-time=$maxTickTime"), properties.filter { it.startsWith("max-tick-time=") })
            assertEquals("workers = 4\nisolation = 2\n", run.resolve("config/vanadium.toml").readText())
            assertEquals("version = 3\nglobalExecutorParallelism = 4\n", run.resolve("config/c2me.toml").readText())
        }
    }
}
