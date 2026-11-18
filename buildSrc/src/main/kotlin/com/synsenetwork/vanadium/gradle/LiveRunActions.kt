package com.synsenetwork.vanadium.gradle

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.tasks.JavaExec
import java.io.File
import java.net.ServerSocket
import java.nio.file.FileSystems

class PrepareLiveRun(
    private val run: File,
    private val configurationCache: Boolean,
    private val eula: File,
    private val runtimeMods: List<File>,
    private val fixture: File,
    private val mode: String,
    private val settings: LiveSettings = LiveSettings(),
) : Action<Task> {
    override fun execute(task: Task) {
        check(!configurationCache) { "Live runs require --no-configuration-cache for fresh launch directories" }
        check(eula.isFile && eula.readLines().any { it.trim() == "eula=true" }) {
            "Accept the Minecraft EULA in .vanadium/eula.txt before running live tests"
        }
        check(!run.exists()) { "Refusing to reuse a live-test world: $run" }
        run.resolve("mods").mkdirs()
        run.resolve("config").mkdirs()
        runtimeMods.forEach { it.copyTo(run.resolve("mods/${it.name}")) }
        fixture.copyTo(run.resolve("mods/${fixture.name}"))
        eula.copyTo(run.resolve("eula.txt"))
        val port = ServerSocket(0).use { it.localPort }
        val properties = linkedMapOf(
            "server-ip" to "127.0.0.1",
            "server-port" to "$port",
            "online-mode" to "true",
            "level-seed" to "12345",
            "level-type" to "minecraft:flat",
            "generator-settings" to """{"layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},""" +
                """{"height":1,"block":"minecraft:grass_block"}],"biome":"minecraft:plains","structure_overrides":[]}""",
            "max-tick-time" to if (mode == "featureBench") "-1" else "60000",
            "view-distance" to "4",
            "simulation-distance" to "4",
            "max-players" to "1",
            "spawn-protection" to "0",
            "pause-when-empty-seconds" to "-1",
        )
        properties.putAll(settings.serverProperties)
        run.resolve("server.properties").writeText(properties.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" })
        run.resolve("config/vanadium.toml").writeText(settings.vanadium.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key = $value" })
        settings.c2meParallelism?.let { run.resolve("config/c2me.toml").writeText("version = 3\nglobalExecutorParallelism = $it\n") }
        task.logger.lifecycle("Isolated live run: $run")
    }
}

/**
 * Per-fixture launch settings. [serverProperties] override the isolated defaults; [vanadium] is the whole
 * `vanadium.toml`; a null [c2meParallelism] leaves C2ME on its own default.
 */
data class LiveSettings(
    val serverProperties: Map<String, String> = emptyMap(),
    val vanadium: Map<String, String> = linkedMapOf("workers" to "4", "isolation" to "2"),
    val c2meParallelism: Int? = 4,
) : java.io.Serializable

/**
 * Checks a finished live run: the pass marker, the JFR recording when requested, every file in [outputs] (run-relative
 * path, `*` matching within its directory, to its minimum line count) and an error-free server log.
 */
class VerifyLiveRun(
    private val mode: String,
    private val jfr: Boolean,
    private val outputs: Map<String, Long> = emptyMap(),
) : Action<Task> {
    override fun execute(task: Task) {
        verify((task as JavaExec).workingDir)
        task.logger.lifecycle("$mode passed; evidence retained in ${task.workingDir}")
    }

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
}

abstract class BuildFeaturesAccess {
    @get:javax.inject.Inject abstract val features: BuildFeatures
}
