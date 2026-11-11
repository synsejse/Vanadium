package com.synsenetwork.vanadium.commands

import com.google.gson.JsonObject
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import java.util.Locale

/** Completed capture; chat views are built on demand, never on the tick path. */
internal class PerformanceReport(
    private val kind: String,
    private val result: JsonObject,
    private val configuration: String,
    private val file: String?,
) {
    private val profile get() = result.getAsJsonObject("profile")
    private val tickCount get() = profile.getAsJsonObject("ticks").number("count").coerceAtLeast(1.0)
    private val prefix get() = "/vanadium $kind report"

    fun view(section: String = "summary"): Component {
        val out = Component.empty().append(text("Vanadium $kind", ChatFormatting.AQUA))
            .append(text(" · ${format(result.number("seconds"), 1)}s", ChatFormatting.GRAY))
        when {
            section == "summary" && kind == "profile" -> {
                val ticks = profile.getAsJsonObject("ticks")
                if (ticks.number("count") == 0.0) out.append("\nNo ticks captured.")
                else out.append("\nTick ").append(metric(ticks.number("avgMs"), "avg", sampleDetails(ticks)))
                        .append(text(" · ", ChatFormatting.DARK_GRAY))
                        .append(metric(ticks.number("p95Ms"), "p95", "95% of measured ticks were at or below this duration.\n${sampleDetails(ticks)}"))
                val stages = stages().take(3)
                if (stages.isNotEmpty()) {
                    out.append("\n")
                    for ((index, entry) in stages.withIndex()) {
                        if (index > 0) out.append(text(" · ", ChatFormatting.DARK_GRAY))
                        out.append(stage(entry.key, entry.value.asJsonObject))
                    }
                }
            }
            section == "summary" -> out.append("\n")
                .append(hover(text("${format(result.number("avgTps"), 1)} TPS", when {
                    result.number("avgTps") >= 25 -> ChatFormatting.GREEN
                    result.number("avgTps") >= 20 -> ChatFormatting.GOLD
                    else -> ChatFormatting.RED
                }),
                    "Uncapped processing capacity, not the gameplay tick rate.\n${benchmarkDetails()}"))
                .append(text(" average · ${result.number("ticks").toLong()} ticks", ChatFormatting.GRAY))
            section == "stages" -> {
                out.append(text("\nAverage ms per server tick · all dimensions", ChatFormatting.GRAY))
                for (entry in stages()) out.append("\n").append(stage(entry.key, entry.value.asJsonObject))
                if (stages().isEmpty()) out.append("\nNo queued stage work.")
            }
            section == "worlds" -> {
                out.append(text("\nAverage world time per server tick", ChatFormatting.GRAY))
                val phases = profile.getAsJsonObject("phases")
                val worlds = phases.entrySet().filter { it.key.endsWith(" / world total") }
                    .sortedByDescending { it.value.asJsonObject.number("totalMs") }
                for ((name, value) in worlds) {
                    val world = name.removeSuffix(" / world total")
                    val details = phases.entrySet().filter { it.key.startsWith("$world / ") && it.key != name }
                        .sortedByDescending { it.value.asJsonObject.number("totalMs") }
                        .joinToString("\n") { "${it.key.substringAfter(" / ")}: ${format(it.value.asJsonObject.number("totalMs") / tickCount)} ms/tick" }
                    out.append("\n").append(hover(text("$world · ${format(value.asJsonObject.number("totalMs") / tickCount)} ms", ChatFormatting.WHITE),
                        "$details\nWorld totals include stages; do not add overlapping rows."))
                }
                if (worlds.isEmpty()) out.append("\nNo world timings recorded.")
            }
            kind == "benchmark" -> out.append("\n").append(benchmarkDetails())
            else -> {
                val split = profile.getAsJsonObject("split")
                out.append(text("\n$configuration", ChatFormatting.GRAY))
                out.append("\n").append(hover(text("Tick split", ChatFormatting.WHITE),
                    "Stages include planning/dispatch/waits. Preparation is independent worker jobs.\nThe remainder includes collection and inline work; it is not CPU-only time."))
                    .append(": stages ${format(split.number("stageWaves") * 100, 1)}% · preparation ${format(split.number("preparation") * 100, 1)}% · remainder ${format(split.number("serial") * 100, 1)}%")
                for ((name, value) in profile.getAsJsonObject("preparations").entrySet()) {
                    val jobs = value.asJsonObject
                    val duration = jobs.getAsJsonObject("duration")
                    out.append("\n").append(hover(text("$name · ${format(duration.number("totalMs") / tickCount)} ms/tick", ChatFormatting.WHITE),
                        "${jobs.number("jobs").toLong()} jobs across ${duration.number("count").toLong()} calls.\n${sampleDetails(duration)}\nAlready included in tick time; do not add again."))
                }
                out.append(text("\nIdle time excluded; profiling adds overhead.", ChatFormatting.GRAY))
            }
        }
        out.append("\n")
        val sections = if (kind == "profile") listOf("summary", "stages", "worlds", "details") else listOf("summary", "details")
        for (part in sections) if (part != section) {
            out.append(hover(text("[${part.replaceFirstChar(Char::uppercase)}] ", ChatFormatting.AQUA), "Show $part · $prefix ${if (part == "summary") "" else part}")
                .withStyle { it.withClickEvent(ClickEvent.RunCommand(if (part == "summary") prefix else "$prefix $part")) })
        }
        out.append(hover(text(if (file == null) "[Not saved]" else "[JSON path]", if (file == null) ChatFormatting.YELLOW else ChatFormatting.GRAY),
            file?.let { "$it\nClick to copy this server-relative path; the full capture is in this file." } ?: "Could not save the result file; the full JSON is in the server log.")
            .withStyle { if (file == null) it else it.withClickEvent(ClickEvent.CopyToClipboard(file)) })
        return out
    }

    private fun stages() = profile.getAsJsonObject("stages").entrySet()
        .filter { it.value.asJsonObject.number("tasks") > 0 }
        .sortedByDescending { it.value.asJsonObject.getAsJsonObject("execution").number("totalMs") }

    private fun stage(name: String, data: JsonObject): MutableComponent {
        val label = when (name) {
            "SCHEDULED_TICK" -> "Scheduled ticks"
            "SPAWNING" -> "Spawning/thunder"
            "CHUNK" -> "Chunk ticks"
            "ENTITY" -> "Entities"
            "BLOCK_ENTITY" -> "Block entities"
            "TRACKING" -> "Tracking"
            else -> name
        }
        val execution = data.getAsJsonObject("execution")
        val header = "$label · all dimensions\n${format(execution.number("totalMs") / tickCount)} ms per server tick, including planning, dispatch and waits.\n"
        val tasks = "${format(data.number("tasks") / tickCount, 1)} tasks/tick"
        val tail = "Caller tail wait: ${format(data.number("callerTailWaitMs") / tickCount)} ms/tick (already included)\n"
        // Entities run as one wave with no regions, so region figures do not apply to them.
        val tooltip = if (data.number("regions") == 0.0) {
            header + "$tasks in one wave, wherever they are (no regions)\n" + tail +
                "Serial entities and collection are outside this stage timing."
        } else {
            header + "$tasks · ${format(data.number("regions") / tickCount, 1)} regions/tick\n" +
                "Largest region: ${data.number("maxTasksPerRegion").toLong()} tasks\n" +
                "Participant utilization: ${format(data.number("utilization") * 100, 1)}% (region wall time / capacity, not CPU usage)\n" +
                tail +
                "Individual region durations: ${sampleDetails(data.getAsJsonObject("regionExecution"))}\n" +
                "Collection and inline fallbacks are outside this stage timing."
        }
        return hover(text("$label ${format(execution.number("totalMs") / tickCount)} ms", ChatFormatting.WHITE), tooltip)
    }

    private fun benchmarkDetails(): String {
        val average = result.number("avgTps")
        return "${result.number("ticks").toLong()} ticks in ${format(result.number("seconds"), 1)}s\n" +
            "Average ${format(average, 1)} TPS · elapsed/tick ${format(if (average > 0) 1000 / average else 0.0)} ms\n" +
            "Best window: ${result.number("peakTps").toLong()} ticks (approximately 1s; not normalized TPS)\n" +
            "$configuration\nElapsed/tick includes wall-time overhead; it is not profiled tick work."
    }

    private fun metric(ms: Double, label: String, tooltip: String) = hover(
        text("${format(ms)} ms $label", when { ms >= 50 -> ChatFormatting.RED; ms >= 40 -> ChatFormatting.GOLD; else -> ChatFormatting.GREEN }),
        "$tooltip\nColor indicates the 50ms budget at 20 TPS, not change versus a baseline.")

    private fun sampleDetails(samples: JsonObject): String = if (samples.number("count") == 0.0) "No samples." else
        "${samples.number("count").toLong()} samples · avg ${format(samples.number("avgMs"))} ms\n" +
            "p50 ${format(samples.number("p50Ms"))} · p95 ${format(samples.number("p95Ms"))} · p99 ${format(samples.number("p99Ms"))} ms" +
            if (samples.number("count") > samples.number("percentileSamples")) "\nPercentiles cover the first ${samples.number("percentileSamples").toLong()} samples only." else ""

    private fun hover(component: MutableComponent, details: String) = component.withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal(details))) }

    private fun text(value: String, color: ChatFormatting) = Component.literal(value).withStyle(color)

    private fun format(value: Double, decimals: Int = 2) = String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun JsonObject.number(key: String): Double = get(key)?.asDouble ?: 0.0
}
