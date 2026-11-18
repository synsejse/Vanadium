package com.synsenetwork.vanadium.mixedbench

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.diagnostics.PerformanceResults
import com.synsenetwork.vanadium.live.LiveServer
import java.io.PrintWriter
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import jdk.jfr.Recording
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.gamerules.GameRules

/**
 * Manual realistic whole-server benchmark; packaged only by mixedBenchJar and run by `runMixedBench`.
 *
 * Setup builds player bases on generated terrain, joins real players over a mocked transport and
 * waits (at 20 TPS) until chunk loading around them settles. Warmup and the measured windows then
 * run a fixed number of ticks each, paced at 20 TPS unless `mixed.sprint` is set, from the same time
 * of day and weather, so enabled and serial runs advance the world equally. Writes
 * `mixed-bench/windows.csv`, `threads.csv`, `entities.csv` and a `mixed-bench` result JSON, then saves
 * and stops through [LiveServer.finish].
 *
 * Every tick from the end of setup to the last measured tick is kept in `ticks.csv` (ID, `tickServer` duration
 * and completion on the JVM-uptime clock of `-Xlog` decorations), buffered and written at each boundary;
 * `boundaries.csv` marks setup, readiness, the JFR start and every window edge on the same clock.
 */
class MixedBench : ModInitializer {
    override fun onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            try {
                Run(server).start()
            } catch (e: Exception) {
                throw IllegalStateException("Mixed benchmark setup failed", e)
            }
        }
    }

    private enum class Phase { READY, WARMUP, MEASURE, DONE }

    private class Run(private val server: MinecraftServer) {
        private val mode = System.getProperty("vanadium.mixed.mode", "enabled")
        private val players = Integer.getInteger("vanadium.mixed.players", 8)
        private val baseCount = Integer.getInteger("vanadium.mixed.bases", 4)
        private val warmupTicks = Integer.getInteger("vanadium.mixed.warmup", 1600)
        private val windowTicks = Integer.getInteger("vanadium.mixed.window", 1200)
        private val windows = Integer.getInteger("vanadium.mixed.windows", 5)
        private val sprint = System.getProperty("vanadium.mixed.sprint", "false").toBooleanStrict()
        private val parallelSpawning = System.getProperty("vanadium.mixed.parallelSpawning", "true").toBooleanStrict()
        private val jfr = java.lang.Boolean.getBoolean("vanadium.live.jfr")
        /** Ticks of JFR recording before the first window; with a shorter warmup it starts at the end of setup. */
        private val jfrLead = Integer.getInteger("vanadium.mixed.jfrLead", 200)
        private val clock = UptimeClock.calibrate()

        /**
         * The resolved spawning arm: `off` (Vanadium disabled), `serial-spawning` (`parallelSpawning = false`), or
         * `participants-<auto|N>` (`-Dvanadium.spawnParticipants`). See docs/PROFILING.md.
         */
        private val spawnArm: String get() = when {
            !Vanadium.config.enabled -> "off"
            !Vanadium.config.parallelSpawning -> "serial-spawning"
            else -> "participants-${Vanadium.scheduler.spawnParticipants.label}"
        }
        private val level = server.overworld()
        private val output: Path = Files.createDirectories(Path.of("mixed-bench"))
        private val bases = ArrayList<MixedBase>()
        private val walkers = ArrayList<Walker>()
        private val counters = WindowCounters(server)
        private val rows = JsonArray()
        private lateinit var csv: PrintWriter
        private lateinit var ticksCsv: PrintWriter
        private lateinit var boundariesCsv: PrintWriter
        private val tickLog = TickLog()
        /** Completion of the tick before the current window (its `t0`). */
        private var windowStart = 0L
        private var recording: Recording? = null
        private var jfrStartTick = -1
        private var measureStartTick = -1
        private var phase = Phase.READY
        private var phaseTicks = 0
        private var window = 0
        private var stableTicks = 0
        private var lastChunks = -1
        private var measureStart = 0L
        private var lastHealth = LongArray(3)

        fun start() {
            check(mode in listOf("enabled", "serial")) { "unknown mixed.mode $mode" }
            check(Vanadium.config.enabled == (mode == "enabled")) {
                "mixed.mode=$mode but vanadium.toml has enabled=${Vanadium.config.enabled}"
            }
            check(Vanadium.config.parallelSpawning == parallelSpawning) {
                "mixed.parallelSpawning=$parallelSpawning but vanadium.toml has parallelSpawning=${Vanadium.config.parallelSpawning}"
            }
            // Repeatable conditions: no weather changes, no block damage from mobs, the same dusk start.
            listOf("gamerule advance_weather false", "weather clear", "gamerule mob_griefing false", "time set 12000").forEach(::command)
            check(!server.gameRules.get(GameRules.ADVANCE_WEATHER) && !server.gameRules.get(GameRules.MOB_GRIEFING) && !level.isRaining) {
                "fixed weather and mob griefing rules were not applied"
            }
            val setupStart = System.nanoTime()
            repeat(baseCount) { bases.add(MixedBase.build(level, it)) }
            for (i in 0 until players) {
                val base = bases[i % baseCount]
                val round = i / baseCount
                walkers.add(Walker(level, base, explorer = round % 2 == 1, lane = round / 2))
            }
            // Generate and pave every route now; the first explorer pass (in warmup) generates the rest of its view.
            walkers.flatMap { it.routeChunks() }.toSet().forEach { level.getChunk(ChunkPos.getX(it), ChunkPos.getZ(it)) }
            walkers.forEach { it.pave() }
            walkers.forEachIndexed { i, walker ->
                walker.join("Mixed${if (walker.explorer) "Explorer" else "Builder"}$i")
                walker.client.player.apply {
                    setGameMode(GameType.SURVIVAL)
                    // Mobs target and hit the players, but no player dies, starves or leaves its route.
                    effect(MobEffects.RESISTANCE, 4)
                    effect(MobEffects.SATURATION, 0)
                    effect(MobEffects.REGENERATION, 1)
                }
            }
            println("MIXED_BENCH_SETUP bases=$baseCount players=$players spawnArm=$spawnArm elapsedMs=${(System.nanoTime() - setupStart) / 1_000_000}")
            csv = PrintWriter(Files.newBufferedWriter(output.resolve("windows.csv")))
            ticksCsv = PrintWriter(Files.newBufferedWriter(output.resolve("ticks.csv")))
            ticksCsv.println("phase,window,tick_id,tick_ns,end_uptime_ns")
            boundariesCsv = PrintWriter(Files.newBufferedWriter(output.resolve("boundaries.csv")))
            boundariesCsv.println("boundary,window,tick_id,tick_end_uptime_ns,mark_uptime_ns,work_ns,epoch_us")
            val now = clock.now()
            boundary("setup_end", -1, now, now)
            println("MIXED_BENCH_CLOCK uptimeNanos=$now calibrationUncertaintyNanos=${clock.uncertainty}")
            // Too short a warmup for the lead: record readiness too, rather than starting JFR inside a window.
            if (jfr && jfrLead > warmupTicks) startRecording(now)
            ServerTickEvents.END_SERVER_TICK.register { current -> if (current === server) onTickEnd() }
        }

        private fun ServerPlayer.effect(effect: Holder<MobEffect>, amplifier: Int) {
            addEffect(MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, amplifier, false, false))
        }

        private fun onTickEnd() {
            if (phase == Phase.DONE) return
            // Completion first, before this hook's own work (which belongs to the gap before the next completion).
            val end = clock.now()
            val tick = server.tickCount
            val nanos = server.tickTimesNanos[tick % 100]
            tickLog.add(tick, nanos, end)
            MixedBenchTickEvent().let {
                if (it.isEnabled) {
                    it.tick = tick
                    it.phase = phaseName()
                    it.window = if (phase == Phase.MEASURE) window else -1
                    it.tickNanos = nanos
                    it.endUptimeNanos = end
                    it.commit()
                }
            }
            phaseTicks++
            val moving = phase != Phase.READY
            walkers.forEach { it.step(moving) }
            bases.forEach { it.sample() }
            when (phase) {
                Phase.READY -> ready(end)
                Phase.WARMUP -> {
                    startRecordingIfDue(end)
                    if (phaseTicks >= warmupTicks) beginMeasuring(end)
                }
                Phase.MEASURE -> if (phaseTicks >= windowTicks) endWindow(end)
                Phase.DONE -> Unit
            }
        }

        private fun phaseName() = phase.name.lowercase()

        /** Writes the buffered ticks of the phase (or window) that just ended. */
        private fun flushTicks() {
            val name = phaseName()
            val index = if (phase == Phase.MEASURE) window else -1
            for (i in 0 until tickLog.size) {
                ticksCsv.append(name).append(',').append(index.toString()).append(',').append(tickLog.ids[i].toString()).append(',')
                    .append(tickLog.durations[i].toString()).append(',').println(tickLog.ends[i])
            }
            ticksCsv.flush()
            tickLog.clear()
        }

        /**
         * One row of `boundaries.csv`: the last completed tick and its completion [end], when this boundary's
         * server-thread work began ([mark]) and how long it took (snapshots, file writes, starting JFR).
         */
        private fun boundary(name: String, window: Int, end: Long, mark: Long) {
            val work = clock.now() - mark
            val wall = Instant.now()
            boundariesCsv.println("$name,$window,${server.tickCount},$end,$mark,$work,${wall.epochSecond * 1_000_000 + wall.nano / 1000}")
            boundariesCsv.flush()
            MixedBenchBoundaryEvent().let {
                if (it.isEnabled) {
                    it.boundary = name
                    it.window = window
                    it.tick = server.tickCount
                    it.endUptimeNanos = end
                    it.commit()
                }
            }
        }

        private fun startRecordingIfDue(end: Long) {
            if (jfr && recording == null && phase == Phase.WARMUP && phaseTicks >= warmupTicks - jfrLead) startRecording(end)
        }

        private fun startRecording(end: Long) {
            val mark = clock.now()
            recording = LiveServer.recording().also { it.start() }
            jfrStartTick = server.tickCount
            boundary("jfr_start", -1, end, mark)
        }

        /** Players loaded and entity-ticking, and the loaded-chunk count unchanged for five seconds. */
        private fun ready(end: Long) {
            if (phaseTicks > READY_LIMIT) {
                flushTicks()
                error("chunks around the players did not settle within $READY_LIMIT ticks")
            }
            val chunks = server.allLevels.sumOf { it.chunkSource.loadedChunksCount }
            stableTicks = if (chunks == lastChunks) stableTicks + 1 else 0
            lastChunks = chunks
            val joined = walkers.all { it.client.player.connection.hasClientLoaded() && level.isPositionEntityTicking(it.client.player.blockPosition()) }
            if (!joined || stableTicks < 100) return
            println("MIXED_BENCH_READY ticks=$phaseTicks loadedChunks=$chunks")
            val mark = clock.now()
            flushTicks()
            command("time set 12000")
            if (sprint) server.tickRateManager().requestGameToSprint(warmupTicks + windowTicks * windows + 1)
            boundary("ready_end", -1, end, mark)
            next(Phase.WARMUP)
            startRecordingIfDue(end)
            // No warmup: this readiness tick is also the first window's boundary.
            if (warmupTicks == 0) beginMeasuring(end)
        }

        private fun beginMeasuring(end: Long) {
            val mark = clock.now()
            flushTicks()
            measureStart = level.overworldClockTime
            measureStartTick = server.tickCount
            lastHealth = longArrayOf(bases.sumOf { it.redstoneToggles }, walkers.sumOf { it.corrections }, walkers.sumOf { it.stalls })
            next(Phase.MEASURE)
            windowStart = end
            boundary("measure_start", 0, end, mark)
            counters.begin()
        }

        private fun endWindow(end: Long) {
            val mark = clock.now()
            val health = longArrayOf(bases.sumOf { it.redstoneToggles }, walkers.sumOf { it.corrections }, walkers.sumOf { it.stalls })
            val timing = WindowTiming(windowStart, tickLog)
            val row = counters.end(tickLog.durations, tickLog.size, counters.world() + linkedMapOf<String, Number?>(
                "redstone_toggles" to health[0] - lastHealth[0],
                "smelted_total" to bases.sumOf { it.smelted() },
                "player_corrections" to health[1] - lastHealth[1],
                "walker_stalls" to health[2] - lastHealth[2],
                "players_online" to server.playerList.playerCount,
            ) + timing.columns())
            flushTicks()
            lastHealth = health
            if (window == 0) csv.println((listOf("mode", "window") + row.keys).joinToString(","))
            csv.println((listOf(mode, "$window") + row.values.map { format(it) }).joinToString(","))
            csv.flush()
            rows.add(WindowCounters.json(row).apply { addProperty("window", window) })
            println("MIXED_BENCH_WINDOW mode=$mode window=$window " + row.entries.joinToString(" ") { (k, v) -> "$k=${format(v)}" })
            boundary("window_end", window, end, mark)
            window++
            windowStart = end
            if (window < windows) {
                next(Phase.MEASURE)
                counters.begin()
            } else {
                finish()
            }
        }

        private fun finish() {
            next(Phase.DONE)
            if (sprint) server.tickRateManager().stopSprinting()
            recording?.let {
                it.stop()
                it.dump(Path.of("live.jfr"))
                it.close()
            }
            csv.close()
            ticksCsv.close()
            boundariesCsv.close()
            Files.newBufferedWriter(output.resolve("threads.csv")).use { out ->
                out.write("thread,cpu_ms,allocated_mb\n")
                counters.threadTotals.entries.sortedByDescending { it.value[0] }.forEach { (name, totals) ->
                    out.write("\"${name.replace("\"", "\"\"")}\",${format(totals[0] / 1e6)},${format(totals[1] / 1048576.0)}\n")
                }
            }
            // What the world contained at the end: entity types, and item entities by item.
            val census = HashMap<String, Int>()
            for (dimension in server.allLevels) for (entity in dimension.allEntities) {
                census.merge(EntityType.getKey(entity.type).toString(), 1, Int::plus)
                if (entity is ItemEntity) census.merge("item/" + BuiltInRegistries.ITEM.getKey(entity.item.item), 1, Int::plus)
            }
            Files.newBufferedWriter(output.resolve("entities.csv")).use { out ->
                out.write("type,count\n")
                census.entries.sortedByDescending { it.value }.forEach { (type, count) -> out.write("$type,$count\n") }
            }
            val body = JsonObject().apply {
                addProperty("mode", mode)
                addProperty("spawnArm", spawnArm)
                addProperty("parallelSpawning", Vanadium.config.parallelSpawning)
                addProperty("spawnParticipants", Vanadium.scheduler.spawnParticipants.label)
                addProperty("players", players)
                addProperty("bases", baseCount)
                addProperty("warmupTicks", warmupTicks)
                addProperty("windowTicks", windowTicks)
                addProperty("windows", windows)
                addProperty("sprint", sprint)
                addProperty("jfr", jfr)
                addProperty("jfrLeadTicks", jfrLead)
                // The recording's actual lead: ticks completed after it started and before the first window.
                addProperty("jfrRecordedLeadTicks", if (jfrStartTick < 0) null else measureStartTick - jfrStartTick)
                addProperty("uptimeCalibrationUncertaintyNanos", clock.uncertainty)
                add("rollingTpsTicks", JsonArray().apply { WindowTiming.ROLLING_TICKS.forEach { add(it) } })
                add("garbageCollectors", JsonArray().apply { ManagementFactory.getGarbageCollectorMXBeans().forEach { add(it.name) } })
                addProperty("dayTimeAtFirstWindow", measureStart)
                addProperty("viewDistance", server.playerList.viewDistance)
                addProperty("simulationDistance", server.playerList.simulationDistance)
                addProperty("jvmArgs", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
                add("windowResults", rows)
            }
            PerformanceResults.publish(server, "mixed-bench", body)
            walkers.forEach { it.client.close() }
            println("MIXED_BENCH_PASSED mode=$mode windows=$windows")
            LiveServer.finish(server, "mixedBench")
        }

        private fun next(phase: Phase) {
            this.phase = phase
            phaseTicks = 0
        }

        /** Command errors throw; the result is the command's value (false gamerules return 0). */
        private fun command(command: String) {
            server.commands.dispatcher.execute(command, server.createCommandSourceStack())
        }

        private fun format(value: Number?) = when (value) {
            null -> ""
            is Double -> "%.3f".format(java.util.Locale.ROOT, value)
            else -> value.toString()
        }
    }

    private companion object {
        /** Five minutes at 20 TPS for chunk generation around every player to finish. */
        const val READY_LIMIT = 6000
    }
}
