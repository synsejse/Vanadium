package com.synsenetwork.vanadium.diagnostics

import com.google.gson.JsonObject
import java.util.Locale
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent

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

    /** The chat view of [section], one of [sections] for this report's kind; anything else shows the details. */
    fun view(section: String = "summary"): Component {
        val out = Component.empty()
        out.append(text("Vanadium $kind", ChatFormatting.AQUA))
        out.append(text(" · ${format(result.number("seconds"), 1)}s", ChatFormatting.GRAY))
        when {
            section == "summary" && kind == "profile" -> profileSummary(out)
            section == "summary" -> benchmarkSummary(out)
            section == "stages" -> stagesView(out)
            section == "worlds" -> worldsView(out)
            kind == "benchmark" -> out.append("\n").append(benchmarkDetails())
            else -> profileDetails(out)
        }
        out.append("\n")
        navigation(out, section)
        return out
    }

    private fun profileSummary(out: MutableComponent) {
        val ticks = profile.getAsJsonObject("ticks")
        if (ticks.number("count") == 0.0) {
            out.append("\nNo ticks captured.")
        } else {
            val samples = sampleDetails(ticks)
            out.append("\nTick ")
            out.append(metric(ticks.number("avgMs"), "avg", samples))
            out.append(text(" · ", ChatFormatting.DARK_GRAY))
            out.append(metric(ticks.number("p95Ms"), "p95", "95% of measured ticks were at or below this duration.\n$samples"))
        }
        val stages = stages().take(3)
        if (stages.isNotEmpty()) {
            out.append("\n")
            for ((index, entry) in stages.withIndex()) {
                if (index > 0) out.append(text(" · ", ChatFormatting.DARK_GRAY))
                out.append(stage(entry.key, entry.value.asJsonObject))
            }
        }
    }

    private fun benchmarkSummary(out: MutableComponent) {
        val tps = result.number("avgTps")
        val color = when {
            tps >= 25 -> ChatFormatting.GREEN
            tps >= 20 -> ChatFormatting.GOLD
            else -> ChatFormatting.RED
        }
        out.append("\n")
        out.append(hover(text("${format(tps, 1)} TPS", color),
            "Uncapped processing capacity, not the gameplay tick rate.\n${benchmarkDetails()}"))
        out.append(text(" average · ${result.number("ticks").toLong()} ticks", ChatFormatting.GRAY))
    }

    private fun stagesView(out: MutableComponent) {
        out.append(text("\nAverage ms per server tick · all dimensions", ChatFormatting.GRAY))
        val stages = stages()
        for (entry in stages) out.append("\n").append(stage(entry.key, entry.value.asJsonObject))
        if (stages.isEmpty()) out.append("\nNo queued stage work.")
    }

    private fun worldsView(out: MutableComponent) {
        out.append(text("\nAverage world time per server tick", ChatFormatting.GRAY))
        val phases = profile.getAsJsonObject("phases")
        val worlds = phases.entrySet().filter { it.key.endsWith(" / world total") }
            .sortedByDescending { it.value.asJsonObject.number("totalMs") }
        for ((name, value) in worlds) {
            val world = name.removeSuffix(" / world total")
            val details = phases.entrySet().filter { it.key.startsWith("$world / ") && it.key != name }
                .sortedByDescending { it.value.asJsonObject.number("totalMs") }
                .joinToString("\n") { "${it.key.substringAfter(" / ")}: ${perTick(it.value.asJsonObject.number("totalMs"))} ms/tick" }
            val label = text("$world · ${perTick(value.asJsonObject.number("totalMs"))} ms", ChatFormatting.WHITE)
            out.append("\n")
            out.append(hover(label, "$details\nWorld totals include stages; do not add overlapping rows."))
        }
        if (worlds.isEmpty()) out.append("\nNo world timings recorded.")
    }

    private fun profileDetails(out: MutableComponent) {
        val split = profile.getAsJsonObject("split")
        out.append(text("\n$configuration", ChatFormatting.GRAY))
        out.append("\n")
        out.append(hover(text("Tick split", ChatFormatting.WHITE),
            "Stages include planning/dispatch/waits. Preparation is independent worker jobs.\n" +
                "The remainder includes collection and inline work; it is not CPU-only time."))
        out.append(": stages ${format(split.number("stageWaves") * 100, 1)}%" +
            " · preparation ${format(split.number("preparation") * 100, 1)}%" +
            " · remainder ${format(split.number("serial") * 100, 1)}%")
        for ((name, value) in profile.getAsJsonObject("preparations").entrySet()) {
            val jobs = value.asJsonObject
            val duration = jobs.getAsJsonObject("duration")
            val label = text("$name · ${perTick(duration.number("totalMs"))} ms/tick", ChatFormatting.WHITE)
            out.append("\n")
            out.append(hover(label,
                "${jobs.number("jobs").toLong()} jobs across ${duration.number("count").toLong()} calls.\n" +
                    "${sampleDetails(duration)}\nAlready included in tick time; do not add again."))
        }
        out.append(text("\nIdle time excluded; profiling adds overhead.", ChatFormatting.GRAY))
    }

    /** Buttons for the other views, then the result file's path (or why there is none). */
    private fun navigation(out: MutableComponent, section: String) {
        for (part in sections(kind)) {
            if (part == section) continue
            val command = if (part == "summary") prefix else "$prefix $part"
            val button = hover(text("[${part.replaceFirstChar(Char::uppercase)}] ", ChatFormatting.AQUA),
                "Show $part · $prefix ${if (part == "summary") "" else part}")
            out.append(button.withStyle { it.withClickEvent(ClickEvent.RunCommand(command)) })
        }
        if (file == null) {
            out.append(hover(text("[Not saved]", ChatFormatting.YELLOW),
                "Could not save the result file; the full JSON is in the server log."))
            return
        }
        val path = hover(text("[JSON path]", ChatFormatting.GRAY),
            "$file\nClick to copy this server-relative path; the full capture is in this file.")
        out.append(path.withStyle { it.withClickEvent(ClickEvent.CopyToClipboard(file)) })
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
        val header = "$label · all dimensions\n" +
            "${perTick(execution.number("totalMs"))} ms per server tick, including planning, dispatch and waits.\n"
        val tasks = "${perTick(data.number("tasks"), 1)} tasks/tick"
        val tail = "Caller tail wait: ${perTick(data.number("callerTailWaitMs"))} ms/tick (already included)\n"
        // Entities run as one wave with no regions, so region figures do not apply to them.
        val tooltip = if (data.number("regions") == 0.0) {
            header + "$tasks in one wave, wherever they are (no regions)\n" + tail +
                "Serial entities and collection are outside this stage timing."
        } else {
            header + "$tasks · ${perTick(data.number("regions"), 1)} regions/tick\n" +
                "Largest region: ${data.number("maxTasksPerRegion").toLong()} tasks\n" +
                "Participant utilization: ${format(data.number("utilization") * 100, 1)}% (region wall time / capacity, not CPU usage)\n" +
                tail +
                "Individual region durations: ${sampleDetails(data.getAsJsonObject("regionExecution"))}\n" +
                "Collection and inline fallbacks are outside this stage timing."
        }
        return hover(text("$label ${perTick(execution.number("totalMs"))} ms", ChatFormatting.WHITE), tooltip)
    }

    private fun benchmarkDetails(): String {
        val average = result.number("avgTps")
        return "${result.number("ticks").toLong()} ticks in ${format(result.number("seconds"), 1)}s\n" +
            "Average ${format(average, 1)} TPS · elapsed/tick ${format(if (average > 0) 1000 / average else 0.0)} ms\n" +
            "Best window: ${result.number("peakTps").toLong()} ticks (approximately 1s; not normalized TPS)\n" +
            "$configuration\nElapsed/tick includes wall-time overhead; it is not profiled tick work."
    }

    private fun metric(ms: Double, label: String, tooltip: String): MutableComponent {
        val color = when {
            ms >= 50 -> ChatFormatting.RED
            ms >= 40 -> ChatFormatting.GOLD
            else -> ChatFormatting.GREEN
        }
        return hover(text("${format(ms)} ms $label", color),
            "$tooltip\nColor indicates the 50ms budget at 20 TPS, not change versus a baseline.")
    }

    private fun sampleDetails(samples: JsonObject): String {
        val count = samples.number("count")
        if (count == 0.0) return "No samples."
        val details = "${count.toLong()} samples · avg ${format(samples.number("avgMs"))} ms\n" +
            "p50 ${format(samples.number("p50Ms"))} · p95 ${format(samples.number("p95Ms"))} · p99 ${format(samples.number("p99Ms"))} ms"
        val percentileSamples = samples.number("percentileSamples")
        if (count <= percentileSamples) return details
        return details + "\nPercentiles cover the first ${percentileSamples.toLong()} samples only."
    }

    private fun hover(component: MutableComponent, details: String) =
        component.withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal(details))) }

    /** [total] over the whole capture as an average per server tick. */
    private fun perTick(total: Double, decimals: Int = 2) = format(total / tickCount, decimals)

    private fun text(value: String, color: ChatFormatting) = Component.literal(value).withStyle(color)

    private fun format(value: Double, decimals: Int = 2) = String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun JsonObject.number(key: String): Double = get(key)?.asDouble ?: 0.0

    companion object {
        /** The views of a report of [kind] (`profile` or `benchmark`), summary first: its `report` subcommands. */
        fun sections(kind: String): List<String> = if (kind == "profile") PROFILE_SECTIONS else BENCHMARK_SECTIONS

        private val PROFILE_SECTIONS = listOf("summary", "stages", "worlds", "details")
        private val BENCHMARK_SECTIONS = listOf("summary", "details")
    }
}
