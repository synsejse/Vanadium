package com.synsenetwork.vanadium.coldexplosion

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.diagnostics.PerformanceResults
import com.synsenetwork.vanadium.live.LiveServer
import com.synsenetwork.vanadium.live.TestPlayer
import com.synsenetwork.vanadium.live.TickLog
import com.synsenetwork.vanadium.live.UptimeClock
import java.io.Writer
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import jdk.jfr.Recording
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3

/**
 * Manual cold-first-use explosion benchmark; packaged only by coldExplosionJar and run by `runColdExplosion`.
 *
 * One fresh JVM per run compares the first ordinary creeper explosion with later, warmed ones on the same path: a
 * creeper's own fuse, ticked in the flat entity wave (TNT runs serially before the wave, so it cannot reach that
 * route). Twelve identical arenas sit on a ring around one observer player in a fixed, force-loaded flat world with
 * natural spawning, weather, time and mob griefing off, so the only explosions are the fixture's. Every arena has a
 * no-AI creeper and a no-AI pig five blocks outward that the blast damages; a fenced pen of AI cows inside the ring
 * makes the entity wave large enough to use workers.
 *
 * After readiness the schedule is fixed in ticks: at tick [ARM_AT] the arm acts on arena 0 (`warmup`: ignite its
 * creeper; `sham`: nothing, and the creeper is discarded when the warmup blast would happen; `load`: load and
 * initialise `ServerExplosion` on the server thread with no explosion, then the same discard). Measured blasts 1..N
 * ignite arena k's creeper at [FIRST_AT] + (k - 1) × spacing; each explodes 30 ticks later. Writes
 * `cold-explosion/blasts.csv`, `ticks.csv` (every tick from setup) and a `cold-explosion` result JSON, then saves and
 * stops through [LiveServer.finish]. A run fails on any other explosion, a missing or repeated blast, or a blast
 * before its arm says it may.
 */
class ColdExplosion : ModInitializer {
    override fun onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            try {
                Run(server).start()
            } catch (e: Exception) {
                throw IllegalStateException("Cold explosion setup failed", e)
            }
        }
    }

    private class Arena(val index: Int, val creeper: Creeper, val witness: Mob) {
        var igniteTick = -1
    }

    private class Run(private val server: MinecraftServer) {
        private val arm = System.getProperty("vanadium.cold.arm", "sham")
        private val mode = System.getProperty("vanadium.cold.mode", "enabled")
        private val blasts = Integer.getInteger("vanadium.cold.blasts", 8)
        private val spacing = Integer.getInteger("vanadium.cold.spacing", 100)
        private val filler = Integer.getInteger("vanadium.cold.filler", 200)
        private val classLog = java.lang.Boolean.getBoolean("vanadium.cold.classLog")
        private val jfr = java.lang.Boolean.getBoolean("vanadium.live.jfr")
        private val clock = UptimeClock.calibrate()
        private val level = server.overworld()
        private val output: Path = Files.createDirectories(Path.of("cold-explosion"))
        private val ticks = TickLog(4096)
        private val arenas = ArrayList<Arena>()
        private val ourCreepers = HashSet<Entity>()
        private lateinit var observer: TestPlayer
        private lateinit var centre: BlockPos
        private var recording: Recording? = null
        private var ready = false
        private var readyTicks = 0
        private var stableTicks = 0
        private var lastChunks = -1
        private var readyTick = -1
        /** Ticks completed since readiness; the schedule's clock. */
        private var t = 0
        private var done = false
        private var armTick = -1
        private var armNanos = 0L
        private val endAt get() = FIRST_AT + (blasts - 1) * spacing + FUSE + TAIL

        fun start() {
            check(arm in ARMS) { "unknown cold.arm $arm" }
            check(mode in listOf("enabled", "serial")) { "unknown cold.mode $mode" }
            check(blasts in 2..ARENAS - 1) { "cold.blasts must be 2..${ARENAS - 1}, not $blasts" }
            check(spacing >= FUSE + NEXT_TICKS + BASELINE_TICKS) { "cold.spacing $spacing leaves blasts overlapping" }
            check(Vanadium.config.enabled == (mode == "enabled")) {
                "cold.mode=$mode but vanadium.toml has enabled=${Vanadium.config.enabled}"
            }
            ExplosionLog.prime()
            // Only the fixture's explosions: no spawning, weather or day–night changes, no block damage from mobs.
            listOf("gamerule spawn_mobs false", "gamerule spawn_patrols false", "gamerule spawn_wandering_traders false",
                "gamerule advance_weather false", "weather clear", "gamerule advance_time false", "time set 6000",
                "gamerule mob_griefing false").forEach(::command)
            check(!server.gameRules.get(GameRules.SPAWN_MOBS) && !server.gameRules.get(GameRules.MOB_GRIEFING) &&
                    !server.gameRules.get(GameRules.ADVANCE_TIME) && !level.isRaining) { "fixed world rules were not applied" }
            check(ExplosionLog.records.isEmpty()) { "an explosion happened before setup: ${describe(ExplosionLog.records.first())}" }
            build()
            Files.newBufferedWriter(output.resolve("ticks.csv")).use { it.line("tick_id,tick_ns,end_uptime_ns") }
            println("COLD_EXPLOSION_SETUP arm=$arm mode=$mode blasts=$blasts spacing=$spacing filler=$filler classLog=$classLog " +
                    "jfr=$jfr calibrationUncertaintyNanos=${clock.uncertainty}")
            ServerTickEvents.END_SERVER_TICK.register { current -> if (current === server) onTickEnd() }
            ServerLifecycleEvents.SERVER_STOPPING.register { current -> if (current === server) stopRecording() }
        }

        /** The ring of arenas, the cow pen and the observer, in chunks that stay force-loaded for the whole run. */
        private fun build() {
            val y = level.getChunk(0, 0).getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8) + 1
            centre = BlockPos(8, y, 8)
            val reach = RING + WITNESS + 8
            for (cx in ((centre.x - reach) shr 4)..((centre.x + reach) shr 4)) for (cz in ((centre.z - reach) shr 4)..((centre.z + reach) shr 4)) {
                level.setChunkForced(cx, cz, true)
                level.getChunk(cx, cz)
            }
            // Entity damage is warm in every arm (the first blast should not also be the JVM's first hurt).
            spawn(EntityTypes.PIG, centre.offset(2, 0, 2)).apply {
                hurtServer(level, level.damageSources().explosion(null as Entity?, null), 1f)
                hurtServer(level, level.damageSources().generic(), 1f)
                discard()
            }
            for (i in 0 until ARENAS) {
                val angle = 2 * Math.PI * i / ARENAS
                fun at(radius: Int) = centre.offset((radius * cos(angle)).roundToInt(), 0, (radius * sin(angle)).roundToInt())
                val creeper = spawn(EntityTypes.CREEPER, at(RING))
                arenas.add(Arena(i, creeper, spawn(EntityTypes.PIG, at(RING + WITNESS))))
                ourCreepers.add(creeper)
            }
            val fence = ArrayList<BlockPos>()
            for (dx in -PEN..PEN) for (dz in -PEN..PEN) if (dx == -PEN || dx == PEN || dz == -PEN || dz == PEN) fence.add(centre.offset(dx, 0, dz))
            fence.forEach { level.setBlock(it, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL) }
            fence.forEach { level.setBlock(it, Block.updateFromNeighbourShapes(level.getBlockState(it), level, it), Block.UPDATE_ALL) }
            // The observer stands on a pillar in the pen's middle, above the cows, within packet range of every blast.
            for (dy in 0..2) level.setBlock(centre.above(dy), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL)
            var placed = 0
            outer@ for (dx in -(PEN - 2)..PEN - 2) for (dz in -(PEN - 2)..PEN - 2) {
                if (placed == filler) break@outer
                if (dx == 0 && dz == 0) continue
                spawn(EntityTypes.COW, centre.offset(dx, 0, dz), noAi = false)
                placed++
            }
            check(placed == filler) { "the pen holds at most $placed cows, not $filler" }
            observer = TestPlayer(level, "ColdObserver", Vec3(centre.x + 0.5, centre.y + 3.0, centre.z + 0.5))
            observer.player.setGameMode(GameType.CREATIVE)
        }

        private fun <T : Mob> spawn(type: EntityType<T>, pos: BlockPos, noAi: Boolean = true): T =
            checkNotNull(type.spawn(level, pos, EntitySpawnReason.COMMAND)) { "could not spawn $type at $pos" }.apply {
                setNoAi(noAi)
                setPersistenceRequired()
            }

        private fun onTickEnd() {
            if (done) return
            val end = clock.now()
            val tick = server.tickCount
            ticks.add(tick, server.tickTimesNanos[tick % 100], end)
            if (!ready) {
                awaitReady()
                return
            }
            t++
            if (t == ARM_AT) armAction()
            if (t == ARM_AT + FUSE && arm != "warmup") arenas[0].creeper.discard()
            if (t >= FIRST_AT && (t - FIRST_AT) % spacing == 0) {
                val k = 1 + (t - FIRST_AT) / spacing
                if (k <= blasts) ignite(arenas[k])
            }
            if (t == endAt) finish()
        }

        /** The observer loaded, every fixture mob entity-ticking and the loaded-chunk count unchanged for five seconds. */
        private fun awaitReady() {
            check(++readyTicks <= READY_LIMIT) { "the arenas did not settle within $READY_LIMIT ticks" }
            val chunks = server.allLevels.sumOf { it.chunkSource.loadedChunksCount }
            stableTicks = if (chunks == lastChunks) stableTicks + 1 else 0
            lastChunks = chunks
            val ticking = arenas.all { level.isPositionEntityTicking(it.creeper.blockPosition()) && level.isPositionEntityTicking(it.witness.blockPosition()) }
            if (!observer.player.connection.hasClientLoaded() || !ticking || stableTicks < 100) return
            ready = true
            readyTick = server.tickCount
            println("COLD_EXPLOSION_READY ticks=$readyTicks loadedChunks=$chunks")
            if (jfr) {
                recording = LiveServer.recording().also {
                    // Which classes the first explosion loads, and where they come from.
                    it.enable("jdk.ClassLoad").withThreshold(Duration.ZERO).withStackTrace()
                    it.enable("jdk.ClassDefine").withStackTrace()
                    it.setDestination(Path.of("live.jfr"))
                    it.setDumpOnExit(true)
                    it.start()
                }
            }
        }

        private fun armAction() {
            armTick = server.tickCount
            when (arm) {
                "warmup" -> ignite(arenas[0])
                "load" -> {
                    val event = ColdExplosionEvent().apply { begin() }
                    val start = System.nanoTime()
                    Class.forName(SERVER_EXPLOSION, true, ColdExplosion::class.java.classLoader)
                    armNanos = System.nanoTime() - start
                    event.end()
                    if (event.shouldCommit()) {
                        event.tick = armTick
                        event.source = "load-only control"
                        event.route = ExplosionLog.Route.OUTSIDE_ENTITY_WAVE.name
                        event.commit()
                    }
                }
                "sham" -> Unit
            }
            println("COLD_EXPLOSION_ARM arm=$arm tick=$armTick actionMs=${format(armNanos / 1e6)}")
        }

        private fun ignite(arena: Arena) {
            check(arena.creeper.isAlive) { "arena ${arena.index}'s creeper is gone before ignition" }
            arena.creeper.ignite()
            arena.igniteTick = server.tickCount
        }

        private fun finish() {
            done = true
            stopRecording()
            val records = ExplosionLog.records.toList()
            records.firstOrNull { it.source !in ourCreepers }?.let { error("incidental explosion: ${describe(it)}") }
            val series = TickSeries(ticks)
            val loads = if (classLog) ClassLoads.parse(Files.readAllLines(Path.of("class-load.log")).asSequence()) else null
            val rows = ArrayList<Map<String, Any?>>()
            val classRows = StringBuilder()
            val used = if (arm == "warmup") arenas.subList(0, blasts + 1) else arenas.subList(1, blasts + 1)
            val first = records.firstOrNull()
            for (arena in used) {
                val mine = records.filter { it.source === arena.creeper }
                check(mine.size == 1) { "arena ${arena.index}'s creeper exploded ${mine.size} times" }
                val blast = mine[0]
                val fuse = blast.tick - arena.igniteTick
                check(fuse in FUSE..FUSE + 2) { "arena ${arena.index} exploded $fuse ticks after ignition" }
                val start = clock.at(blast.startNanos)
                val end = clock.at(blast.endNanos)
                val inExplode = loads?.between(start, end)
                val inGap = loads?.between(series.end(blast.tick - 1), series.end(blast.tick))
                inGap?.forEach { classRows.append(arena.index).append(',').append(loads.uptimes[it]).append(',')
                    .append(it in inExplode!!).append(',').append(loads.names[it]).append('\n') }
                val baseline = series.median(arena.igniteTick - BASELINE_TICKS + 1, arena.igniteTick)
                rows.add(linkedMapOf(
                    "arm" to arm,
                    "mode" to mode,
                    "blast" to arena.index,
                    "kind" to if (arena.index == 0) "warmup" else "measured",
                    "first_in_jvm" to (blast === first),
                    "ignite_tick" to arena.igniteTick,
                    "blast_tick" to blast.tick,
                    "fuse_ticks" to fuse,
                    "thread" to blast.thread,
                    "route" to blast.route.name.lowercase(),
                    "explode_ms" to (blast.endNanos - blast.startNanos) / 1e6,
                    "explode_start_uptime_ns" to start,
                    "explode_end_uptime_ns" to end,
                    "baseline_p50_ms" to baseline / 1e6,
                    "prime_tick_ms" to series.nanos(arena.igniteTick + 1) / 1e6,
                    "blast_tick_ms" to series.nanos(blast.tick) / 1e6,
                    "blast_excess_ms" to (series.nanos(blast.tick) - baseline) / 1e6,
                    "blast_gap_ms" to series.gapNanos(blast.tick) / 1e6,
                ).apply {
                    for (n in 1..NEXT_TICKS) put("next${n}_ms", series.nanos(blast.tick + n) / 1e6)
                    put("classes_in_explode", inExplode?.size)
                    put("classes_in_gap", inGap?.size)
                })
            }
            // Ordering the arm promises: sham and load see their first explosion at measured blast 1, warmup at arena 0.
            val expectedFirst = if (arm == "warmup") arenas[0].creeper else arenas[1].creeper
            check(first?.source === expectedFirst) { "the JVM's first explosion was not the arm's: ${first?.let(::describe)}" }
            check(records.none { it.source === arenas[0].creeper } || arm == "warmup") { "arena 0 exploded in the $arm arm" }
            writeCsv("blasts.csv", rows)
            Files.newBufferedWriter(output.resolve("ticks.csv"), java.nio.file.StandardOpenOption.APPEND).use { out ->
                for (i in 0 until ticks.size) out.line("${ticks.ids[i]},${ticks.durations[i]},${ticks.ends[i]}")
            }
            if (loads != null) Files.writeString(output.resolve("blast-classes.csv"), "blast,uptime_ns,in_explode,class\n$classRows")
            rows.forEach { row -> println("COLD_EXPLOSION_BLAST " + row.entries.joinToString(" ") { (k, v) -> "$k=${format(v)}" }) }
            val measured = rows.filter { it["kind"] == "measured" }
            val warm = measured.drop(1)
            fun median(key: String) = warm.map { it[key] as Double }.sorted().let { it[it.size / 2] }
            val summary = linkedMapOf<String, Any?>(
                "firstMeasuredExplodeMs" to measured[0]["explode_ms"],
                "warmExplodeMedianMs" to median("explode_ms"),
                "firstMeasuredTickMs" to measured[0]["blast_tick_ms"],
                "warmTickMedianMs" to median("blast_tick_ms"),
                "firstMeasuredRoute" to measured[0]["route"],
            )
            println("COLD_EXPLOSION_SUMMARY arm=$arm mode=$mode " + summary.entries.joinToString(" ") { (k, v) -> "$k=${format(v)}" })
            val explosionLoad = loads?.firstLoad(SERVER_EXPLOSION)
            val body = JsonObject().apply {
                addProperty("arm", arm)
                addProperty("mode", mode)
                addProperty("blasts", blasts)
                addProperty("spacingTicks", spacing)
                addProperty("filler", filler)
                addProperty("jfr", jfr)
                addProperty("classLog", classLog)
                addProperty("readyTick", readyTick)
                addProperty("armTick", armTick)
                addProperty("armActionMs", armNanos / 1e6)
                addProperty("serverExplosionLoadUptimeNs", explosionLoad)
                addProperty("serverExplosionLoadTick", explosionLoad?.let(series::tickAt))
                addProperty("explosionsObserved", records.size)
                addProperty("entityWorkers", Vanadium.scheduler.workerCount)
                addProperty("uptimeCalibrationUncertaintyNanos", clock.uncertainty)
                addProperty("jvmArgs", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
                add("summary", json(summary))
                add("blastResults", JsonArray().apply { rows.forEach { add(json(it)) } })
            }
            checkNotNull(PerformanceResults.publish(server, "cold-explosion", body)) { "the cold-explosion result could not be saved" }
            observer.close()
            println("COLD_EXPLOSION_PASSED arm=$arm mode=$mode blasts=$blasts")
            LiveServer.finish(server, "coldExplosion")
        }

        private fun stopRecording() {
            val recording = recording ?: return
            this.recording = null
            recording.stop()
            recording.close()
        }

        private fun describe(record: ExplosionLog.Record) =
            "${record.source?.let { EntityType.getKey(it.type) }} at tick ${record.tick} on ${record.thread} (${record.route})"

        private fun writeCsv(name: String, rows: List<Map<String, Any?>>) {
            Files.newBufferedWriter(output.resolve(name)).use { out ->
                out.line(rows[0].keys.joinToString(","))
                rows.forEach { row -> out.line(row.values.joinToString(",") { format(it) }) }
            }
        }

        private fun json(map: Map<String, Any?>) = JsonObject().apply {
            map.forEach { (key, value) ->
                when (value) {
                    null -> add(key, null)
                    is Number -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                    else -> addProperty(key, value.toString())
                }
            }
        }

        /** Command errors throw. */
        private fun command(command: String) {
            server.commands.dispatcher.execute(command, server.createCommandSourceStack())
        }

        private fun format(value: Any?) = when (value) {
            null -> ""
            is Double -> "%.3f".format(java.util.Locale.ROOT, value)
            else -> value.toString()
        }
    }

    private companion object {
        val ARMS = listOf("sham", "warmup", "load")
        const val SERVER_EXPLOSION = "net.minecraft.world.level.ServerExplosion"
        /** Arena 0 belongs to the arm; 1..11 are measured. */
        const val ARENAS = 12
        /** Ring radius around the observer and the witness pig's distance beyond each creeper, in blocks. */
        const val RING = 32
        const val WITNESS = 5
        /** Half-width of the cow pen around the observer. */
        const val PEN = 12
        /** A creeper's fuse: ignited at the end of tick T, it explodes in tick T + 30 (`maxSwell`). */
        const val FUSE = 30
        const val ARM_AT = 100
        const val FIRST_AT = 300
        const val TAIL = 100
        const val BASELINE_TICKS = 20
        const val NEXT_TICKS = 5
        const val READY_LIMIT = 2400

        /** Writer errors throw, unlike `PrintWriter`'s. */
        fun Writer.line(text: String): Writer = append(text).append('\n')
    }
}
