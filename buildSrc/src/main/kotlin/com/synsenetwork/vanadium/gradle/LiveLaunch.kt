package com.synsenetwork.vanadium.gradle

import java.io.File
import java.io.Serializable
import java.nio.file.FileSystems

/**
 * Everything one live fixture launches with and must leave behind, resolved from its `-P` properties in one place.
 * The build reads it lazily: Loom resolves run configurations only for a run that executes, so a malformed
 * `mixed.*` or `cold.*` value fails that run and never an unrelated task. (The run directory is not part of it:
 * Loom finalises run directories during configuration.)
 *
 * [outputs] maps run-relative paths (a `*` matches within one directory level) to minimum line counts.
 */
data class LiveLaunch(
    val mode: String,
    val timeoutMinutes: Long,
    val jfr: Boolean,
    val systemProperties: Map<String, String> = emptyMap(),
    val jvmArgs: List<String> = DEFAULT_JVM_ARGS,
    val settings: LiveSettings = LiveSettings(),
    val outputs: Map<String, Long> = emptyMap(),
) : Serializable {
    /** Checks a finished run: the pass marker, the JFR recording when requested, every output and an error-free log. */
    fun verify(run: File) {
        val marker = run.resolve("vanadium-live-passed.txt")
        check(marker.isFile && marker.readText().trim() == mode) {
            "Live checks/tick/save/shutdown did not complete; inspect $run"
        }
        check(!jfr || run.resolve("live.jfr").let { it.isFile && it.length() > 0 }) { "Missing JFR recording in $run" }
        for ((path, minimumLines) in outputs) {
            val file = File(path).let { relative ->
                val directory = run.resolve(relative.parent ?: ".")
                val matcher = FileSystems.getDefault().getPathMatcher("glob:${relative.name}")
                directory.listFiles().orEmpty().filter { it.isFile && matcher.matches(it.toPath().fileName) }.maxByOrNull { it.name }
            }
            checkNotNull(file) { "Missing $path in $run" }
            val lines = file.useLines { it.count() }
            check(lines >= minimumLines) { "$file has $lines lines, expected at least $minimumLines" }
        }
        val log = run.resolve("logs/latest.log")
        check(log.isFile) { "Missing server log in $run" }
        check(log.useLines { lines -> lines.none { "/ERROR]" in it || " ERROR]" in it || "Exception in server tick loop" in it } }) {
            "Server logged errors; inspect $log"
        }
    }

    companion object {
        val DEFAULT_JVM_ARGS = listOf("-Xms2g", "-Xmx2g", "-XX:+UseG1GC")

        fun resolve(fixture: LiveFixture, property: (String) -> String?): LiveLaunch {
            // Passed through as given: the fixtures and the verifier read it leniently.
            val jfr = property("live.jfr") ?: "false"
            val common = linkedMapOf("vanadium.live.mode" to fixture.runName, "vanadium.live.jfr" to jfr)
            val base = LiveLaunch(fixture.runName, fixture.timeoutMinutes, jfr.toBoolean(), common)
            return when (fixture) {
                LiveFixture.INTEGRATION -> base.copy(systemProperties = common + linkedMapOf(
                    "vanadium.live.ticks" to (property("live.ticks") ?: "500"),
                    "vanadium.live.sprint" to (property("live.sprint") ?: "true"),
                ))
                LiveFixture.FEATURE_BENCH -> FeatureBenchSettings(property).let {
                    base.copy(systemProperties = common + it.systemProperties, settings = it.liveSettings, outputs = it.expectedOutputs())
                }
                LiveFixture.MIXED_BENCH -> MixedBenchSettings(property).let {
                    base.copy(timeoutMinutes = maxOf(fixture.timeoutMinutes, it.timeoutMinutes), systemProperties = common + it.systemProperties,
                        jvmArgs = it.jvmArgs, settings = it.liveSettings, outputs = it.expectedOutputs())
                }
                LiveFixture.COLD_EXPLOSION -> ColdExplosionSettings(property).let {
                    base.copy(systemProperties = common + it.systemProperties, jvmArgs = it.jvmArgs, settings = it.liveSettings,
                        outputs = it.expectedOutputs())
                }
            }
        }
    }
}
