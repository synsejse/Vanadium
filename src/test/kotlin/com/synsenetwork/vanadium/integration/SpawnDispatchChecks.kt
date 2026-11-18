package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.Stage
import java.util.function.Supplier
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.profiling.Profiler
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.util.profiling.metrics.MetricCategory
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.chunk.LevelChunk

/**
 * How `tickSpawningChunk` dispatches a chunk's thunder and spawn attempt, called directly on a simulated chunk
 * between ticks, on the server thread. The two are told apart by their profiler zones: `tickThunder` pushes
 * `thunder`, `spawnForChunk` pushes `spawner`. Only the server thread's zones are recorded.
 *
 * - Serial spawning (`parallelSpawning = false`, parallel chunk ticks): vanilla's order, inline. The thunder runs at
 *   its own call, before the eligibility checks and the attempt, and nothing is queued for the spawning wave.
 * - Parallel spawning: both are queued, and the spawning wave runs them in enqueue order (one chunk is one region,
 *   which the wave runs inline on its caller).
 *
 * The attempt uses the stored spawn state and no nearby player, so the local cap admits no mob.
 */
internal object SpawnDispatchChecks {
    private const val THUNDER = "thunder"
    private const val SPAWNER = "spawner"

    /** Whether [pos] runs both halves of `tickSpawningChunk`: thunder needs entity ticking, the attempt spawnability. */
    fun ready(level: ServerLevel, pos: ChunkPos): Boolean =
        level.chunkSource.chunkMap.distanceManager.inEntityTickingRange(pos.pack()) && level.canSpawnEntitiesInChunk(pos) &&
            level.chunkSource.getChunkNow(pos.x(), pos.z()) != null

    fun run(level: ServerLevel, pos: ChunkPos) {
        val source = level.chunkSource
        check(ready(level, pos)) { "dispatch chunk $pos is not entity ticking and spawnable" }
        val chunk = checkNotNull(source.getChunkNow(pos.x(), pos.z())) { "dispatch chunk $pos not loaded" }
        val state = checkNotNull(source.lastSpawnState) { "no spawn state" }
        val categories = listOf(MobCategory.MONSTER)
        val config = Vanadium.config
        check(config.enabled, "Vanadium disabled")
        val previousChunkTicks = config.parallelChunkTicks
        val previousSpawning = config.parallelSpawning
        try {
            config.parallelChunkTicks = true
            config.parallelSpawning = false
            val serial = dispatch(source, chunk, categories, state)
            check(serial.inline == listOf(THUNDER, SPAWNER)) { "serial spawning ran ${serial.inline} inline, not thunder then the attempt" }
            check(serial.wave.isEmpty()) { "serial spawning queued ${serial.wave} for the spawning wave" }

            config.parallelSpawning = true
            val parallel = dispatch(source, chunk, categories, state)
            check(parallel.inline.isEmpty()) { "parallel spawning ran ${parallel.inline} inline instead of queueing" }
            check(parallel.wave == listOf(THUNDER, SPAWNER)) { "parallel spawning wave ran ${parallel.wave}, not thunder then the attempt" }
        } finally {
            config.parallelChunkTicks = previousChunkTicks
            config.parallelSpawning = previousSpawning
        }
        println("SPAWN_DISPATCH_CHECKS_PASSED chunk=$pos")
    }

    private class Dispatch(val inline: List<String>, val wave: List<String>)

    private fun dispatch(source: ServerChunkCache, chunk: LevelChunk, categories: List<MobCategory>,
                         state: NaturalSpawner.SpawnState): Dispatch {
        val scheduler = Vanadium.scheduler
        val zones = ZoneRecorder()
        scheduler.begin(Stage.SPAWNING)
        // Throws if another profiler is active on the server thread (metrics recording, tick profiling).
        Profiler.use(zones).use {
            TICK_SPAWNING_CHUNK.invoke(source, chunk, categories, state)
            val inline = zones.take()
            scheduler.run(Stage.SPAWNING)
            return Dispatch(inline, zones.take())
        }
    }

    /** Records `thunder` and `spawner` zones in order. */
    private class ZoneRecorder : ProfilerFiller {
        private val zones = ArrayList<String>()

        fun take(): List<String> = zones.toList().also { zones.clear() }

        private fun record(name: String) {
            if (name == THUNDER || name == SPAWNER) zones.add(name)
        }

        override fun startTick() {}
        override fun endTick() {}
        override fun push(name: String) = record(name)
        override fun push(name: Supplier<String>) = record(name.get())
        override fun pop() {}
        override fun popPush(name: String) = record(name)
        override fun popPush(name: Supplier<String>) = record(name.get())
        override fun markForCharting(category: MetricCategory) {}
        override fun incrementCounter(name: String, amount: Int) {}
        override fun incrementCounter(name: Supplier<String>, amount: Int) {}
    }

    private val TICK_SPAWNING_CHUNK = ServerChunkCache::class.java.getDeclaredMethod("tickSpawningChunk",
        LevelChunk::class.java, List::class.java, NaturalSpawner.SpawnState::class.java).apply { isAccessible = true }
}
