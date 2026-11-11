package com.synsenetwork.vanadium.gradle

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.tasks.JavaExec
import java.io.File
import java.net.ServerSocket

class PrepareLiveRun(
    private val run: File,
    private val configurationCache: Boolean,
    private val eula: File,
    private val runtimeMods: List<File>,
    private val fixture: File,
    private val mode: String,
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
        run.resolve("server.properties").writeText("""
            server-ip=127.0.0.1
            server-port=$port
            online-mode=true
            level-seed=12345
            level-type=minecraft:flat
            generator-settings={"layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}],"biome":"minecraft:plains","structure_overrides":[]}
            max-tick-time=${if (mode == "featureBench") -1 else 60000}
            view-distance=4
            simulation-distance=4
            max-players=1
            spawn-protection=0
            pause-when-empty-seconds=-1
        """.trimIndent() + "\n")
        run.resolve("config/vanadium.toml").writeText("workers = 4\nisolation = 2\n")
        run.resolve("config/c2me.toml").writeText("version = 3\nglobalExecutorParallelism = 4\n")
        task.logger.lifecycle("Isolated live run: $run")
    }
}

class VerifyLiveRun(private val mode: String, private val jfr: Boolean) : Action<Task> {
    override fun execute(task: Task) {
        val run = (task as JavaExec).workingDir
        val marker = run.resolve("vanadium-live-passed.txt")
        check(marker.isFile && marker.readText().trim() == mode) {
            "Live checks/tick/save/shutdown did not complete; inspect $run"
        }
        check(!jfr || run.resolve("live.jfr").isFile) { "Missing JFR recording in $run" }
        val log = run.resolve("logs/latest.log")
        check(log.isFile) { "Missing server log in $run" }
        check(log.useLines { lines -> lines.none { "/ERROR]" in it || " ERROR]" in it || "Exception in server tick loop" in it } }) {
            "Server logged errors; inspect $log"
        }
        task.logger.lifecycle("$mode passed; evidence retained in $run")
    }
}

abstract class BuildFeaturesAccess {
    @get:javax.inject.Inject abstract val features: BuildFeatures
}
