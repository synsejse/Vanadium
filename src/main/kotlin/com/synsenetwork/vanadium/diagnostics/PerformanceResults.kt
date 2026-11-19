package com.synsenetwork.vanadium.diagnostics

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.synsenetwork.vanadium.Vanadium
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.MinecraftServer

/**
 * Machine-readable command results. Each result is logged as one `VANADIUM_RESULT {json}` line, so
 * scripts can read the console, and saved under `vanadium/results/` in the server directory.
 * Every result records the configuration and versions it was measured with.
 */
object PerformanceResults {
    const val LOG_PREFIX = "VANADIUM_RESULT "

    private val COMPACT = GsonBuilder().serializeSpecialFloatingPointValues().create()
    private val PRETTY = GsonBuilder().serializeSpecialFloatingPointValues().setPrettyPrinting().create()
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private const val SUMMARY_STAGES = 3

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

    /**
     * Publishes a finished capture, then logs a short summary with the result file's path and sends it to
     * [recipient]. Returns the summary, which `/vanadium <kind> report` shows again.
     */
    internal fun report(recipient: CommandSourceStack, kind: String, body: JsonObject): Component {
        val server = recipient.server
        val file = publish(server, kind, body)
        val message = Component.literal("Vanadium $kind · ${summary(kind, body)}\n")
        if (file == null) {
            message.append(Component.literal("Result file not saved; the full JSON is in the server log's ${LOG_PREFIX.trim()} line")
                .withStyle(ChatFormatting.YELLOW))
        } else {
            val path = describe(server, file)
            message.append(Component.literal("Result: $path").withStyle { style ->
                style.withColor(ChatFormatting.GRAY)
                    .withClickEvent(ClickEvent.CopyToClipboard(path))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to copy this server-relative path")))
            })
        }
        Vanadium.LOGGER.info(message.string)
        recipient.sendSuccess({ message }, false)
        return message
    }

    /** The headline numbers of a [kind] result (`profile` or `benchmark`); the result file holds the rest. */
    internal fun summary(kind: String, result: JsonObject): String {
        val seconds = format(result.number("seconds"), 1)
        if (kind == "benchmark") {
            return "${format(result.number("avgTps"), 1)} TPS uncapped average · ${result.number("ticks").toLong()} ticks in ${seconds}s" +
                " · best ~1s window ${result.number("peakTps").toLong()} ticks"
        }
        val profile = result.getAsJsonObject("profile")
        val ticks = profile.getAsJsonObject("ticks")
        val count = ticks.number("count")
        if (count == 0.0) return "no ticks captured in ${seconds}s"
        val headline = "${count.toLong()} ticks in ${seconds}s · tick avg ${format(ticks.number("avgMs"))} ms, p95 ${format(ticks.number("p95Ms"))} ms"
        // Stage totals cover every dimension: per server tick, not per dimension's run of the stage.
        val stages = profile.getAsJsonObject("stages").entrySet()
            .filter { it.value.asJsonObject.number("tasks") > 0 }
            .map { it.key to it.value.asJsonObject.getAsJsonObject("execution").number("totalMs") / count }
            .sortedByDescending { it.second }
            .take(SUMMARY_STAGES)
        if (stages.isEmpty()) return headline
        return headline + "\nSlowest stages, ms per tick: " + stages.joinToString(" · ") { (stage, ms) -> "$stage ${format(ms)}" }
    }

    /** Server-relative path for chat messages, falling back to the absolute path. */
    private fun describe(server: MinecraftServer, file: Path): String = try {
        server.serverDirectory.toAbsolutePath().relativize(file.toAbsolutePath()).toString()
    } catch (_: IllegalArgumentException) {
        // Paths on different Windows drives cannot be made relative to each other.
        file.toString()
    }

    private fun format(value: Double, decimals: Int = 2) = String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun JsonObject.number(key: String): Double = get(key)?.asDouble ?: 0.0

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
        addProperty("isolation", Vanadium.scheduler.isolation)
        addProperty("spawnParticipants", Vanadium.scheduler.spawnParticipantsLabel)
        add("config", COMPACT.toJsonTree(Vanadium.config))
    }
}
