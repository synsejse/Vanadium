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
        private val jfr = java.lang.Boolean.getBoolean("vanadium.live.jfr")
        private val level = server.overworld()
        private val output: Path = Files.createDirectories(Path.of("mixed-bench"))
        private val bases = ArrayList<MixedBase>()
        private val walkers = ArrayList<Walker>()
        private val counters = WindowCounters(server)
        private val rows = JsonArray()
        private lateinit var csv: PrintWriter
        private var recording: Recording? = null
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
            println("MIXED_BENCH_SETUP bases=$baseCount players=$players elapsedMs=${(System.nanoTime() - setupStart) / 1_000_000}")
            csv = PrintWriter(Files.newBufferedWriter(output.resolve("windows.csv")))
            ServerTickEvents.END_SERVER_TICK.register { current -> if (current === server) onTickEnd() }
        }

        private fun ServerPlayer.effect(effect: Holder<MobEffect>, amplifier: Int) {
            addEffect(MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, amplifier, false, false))
        }

        private fun onTickEnd() {
            if (phase == Phase.DONE) return
            phaseTicks++
            val moving = phase != Phase.READY
            walkers.forEach { it.step(moving) }
            bases.forEach { it.sample() }
            when (phase) {
                Phase.READY -> ready()
                Phase.WARMUP -> if (phaseTicks >= warmupTicks) beginMeasuring()
                Phase.MEASURE -> {
                    counters.tick(server.tickTimesNanos[server.tickCount % 100])
                    if (phaseTicks >= windowTicks) endWindow()
                }
                Phase.DONE -> Unit
            }
        }

        /** Players loaded and entity-ticking, and the loaded-chunk count unchanged for five seconds. */
        private fun ready() {
            check(phaseTicks <= READY_LIMIT) { "chunks around the players did not settle within $READY_LIMIT ticks" }
            val chunks = server.allLevels.sumOf { it.chunkSource.loadedChunksCount }
            stableTicks = if (chunks == lastChunks) stableTicks + 1 else 0
            lastChunks = chunks
            val joined = walkers.all { it.client.player.connection.hasClientLoaded() && level.isPositionEntityTicking(it.client.player.blockPosition()) }
            if (!joined || stableTicks < 100) return
            println("MIXED_BENCH_READY ticks=$phaseTicks loadedChunks=$chunks")
            command("time set 12000")
            if (sprint) server.tickRateManager().requestGameToSprint(warmupTicks + windowTicks * windows + 1)
            next(Phase.WARMUP)
            if (warmupTicks == 0) beginMeasuring()
        }

        private fun beginMeasuring() {
            measureStart = level.overworldClockTime
            lastHealth = longArrayOf(bases.sumOf { it.redstoneToggles }, walkers.sumOf { it.corrections }, walkers.sumOf { it.stalls })
            if (jfr) recording = LiveServer.recording().also { it.start() }
            next(Phase.MEASURE)
            counters.begin()
        }

        private fun endWindow() {
            val health = longArrayOf(bases.sumOf { it.redstoneToggles }, walkers.sumOf { it.corrections }, walkers.sumOf { it.stalls })
            val row = counters.end(counters.world() + linkedMapOf<String, Number>(
                "redstone_toggles" to health[0] - lastHealth[0],
                "smelted_total" to bases.sumOf { it.smelted() },
                "player_corrections" to health[1] - lastHealth[1],
                "walker_stalls" to health[2] - lastHealth[2],
                "players_online" to server.playerList.playerCount,
            ))
            lastHealth = health
            if (window == 0) csv.println((listOf("mode", "window") + row.keys).joinToString(","))
            csv.println((listOf(mode, "$window") + row.values.map { format(it) }).joinToString(","))
            csv.flush()
            rows.add(WindowCounters.json(row).apply { addProperty("window", window) })
            println("MIXED_BENCH_WINDOW mode=$mode window=$window " + row.entries.joinToString(" ") { (k, v) -> "$k=${format(v)}" })
            window++
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
                addProperty("players", players)
                addProperty("bases", baseCount)
                addProperty("warmupTicks", warmupTicks)
                addProperty("windowTicks", windowTicks)
                addProperty("windows", windows)
                addProperty("sprint", sprint)
                addProperty("jfr", jfr)
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

        private fun format(value: Number) = when (value) {
            is Double -> "%.3f".format(java.util.Locale.ROOT, value)
            else -> value.toString()
        }
    }

    private companion object {
        /** Five minutes at 20 TPS for chunk generation around every player to finish. */
        const val READY_LIMIT = 6000
    }
}
