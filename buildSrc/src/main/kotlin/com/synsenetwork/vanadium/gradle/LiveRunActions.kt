package com.synsenetwork.vanadium.gradle

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import java.io.File
import java.net.ServerSocket

/** Writes a fresh live-run directory. [settings] is read only when the run starts (see [LiveLaunch]). */
class PrepareLiveRun(
    private val run: File,
    private val configurationCache: Boolean,
    private val eula: File,
    private val runtimeMods: List<File>,
    private val fixture: File,
    private val settings: Provider<LiveSettings>,
) : Action<Task> {
    override fun execute(task: Task) {
        check(!configurationCache) { "Live runs require --no-configuration-cache for fresh launch directories" }
        check(eula.isFile && eula.readLines().any { it.trim() == "eula=true" }) {
            "Accept the Minecraft EULA in .vanadium/eula.txt before running live tests"
        }
        check(!run.exists()) { "Refusing to reuse a live-test world: $run" }
        val settings = settings.get()
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
            "max-tick-time" to "60000",
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

/** Checks a finished live run against what its [LiveLaunch] expects, resolved when the run ends. */
class VerifyLiveRun(private val launch: Provider<LiveLaunch>) : Action<Task> {
    override fun execute(task: Task) {
        val launch = launch.get()
        launch.verify((task as JavaExec).workingDir)
        task.logger.lifecycle("${launch.mode} passed; evidence retained in ${task.workingDir}")
    }
}

abstract class BuildFeaturesAccess {
    @get:javax.inject.Inject abstract val features: BuildFeatures
}
