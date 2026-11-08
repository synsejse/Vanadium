package com.synsenetwork.vanadium.commands

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.config.VanadiumConfig
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Machine-readable command results. Each result is logged as one `VANADIUM_RESULT {json}` line, so
 * scripts can read the console, and saved under `vanadium/results/` in the server directory.
 * Every result records the configuration and versions it was measured with.
 */
object Results {
    const val LOG_PREFIX = "VANADIUM_RESULT "

    private val COMPACT = GsonBuilder().serializeSpecialFloatingPointValues().create()
    private val PRETTY = GsonBuilder().serializeSpecialFloatingPointValues().setPrettyPrinting().create()
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    /** Logs and saves [body] as a [kind] result; returns the saved file, or null if writing failed. */
    fun publish(server: MinecraftServer, kind: String, body: JsonObject): Path? {
        val result = JsonObject().apply {
            addProperty("kind", kind)
            addProperty("time", LocalDateTime.now().toString())
            add("environment", environment(server))
            body.entrySet().forEach { (key, value) -> add(key, value) }
        }
        Vanadium.LOGGER.info("{}{}", LOG_PREFIX, COMPACT.toJson(result))
        val file = server.serverDirectory.resolve("vanadium/results/$kind-${LocalDateTime.now().format(FILE_TIME)}.json")
        return try {
            Files.createDirectories(file.parent)
            Files.writeString(file, PRETTY.toJson(result) + "\n")
            file
        } catch (e: IOException) {
            Vanadium.LOGGER.warn("Could not save Vanadium result to {}", file, e)
            null
        }
    }

    /** Server-relative path for chat messages, falling back to the absolute path. */
    fun describe(server: MinecraftServer, file: Path?): String = when (file) {
        null -> "not saved (see log)"
        else -> runCatching { server.serverDirectory.toAbsolutePath().relativize(file.toAbsolutePath()).toString() }
            .getOrDefault(file.toString())
    }

    private fun environment(server: MinecraftServer) = JsonObject().apply {
        addProperty("minecraft", server.serverVersion)
        addProperty("dedicated", server.isDedicatedServer)
        val loader = FabricLoader.getInstance()
        for (mod in listOf("vanadium", "c2me", "fabricloader", "fabric-api")) {
            loader.getModContainer(mod).ifPresent { addProperty(mod, it.metadata.version.friendlyString) }
        }
        addProperty("java", Runtime.version().toString())
        addProperty("availableProcessors", Runtime.getRuntime().availableProcessors())
        addProperty("maxHeapMiB", Runtime.getRuntime().maxMemory() / (1024 * 1024))
        addProperty("activeWorkers", Vanadium.scheduler.workerCount)
        addProperty("activeCellSize", Vanadium.scheduler.cellSize)
        addProperty("resolvedCellSize", VanadiumConfig.resolveCellSize())
        add("config", COMPACT.toJsonTree(Vanadium.config))
    }
}
