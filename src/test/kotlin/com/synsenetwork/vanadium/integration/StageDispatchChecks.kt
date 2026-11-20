package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.stage.BlockEntityStage
import com.synsenetwork.vanadium.tick.Stage
import java.util.function.Supplier
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.profiling.Profiler
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.util.profiling.metrics.MetricCategory
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.TickingBlockEntity
import net.minecraft.world.level.chunk.LevelChunk

/**
 * What the spawning and block-entity stages run inline and what they queue, for each option that switches them
 * off, called directly on a simulated chunk between ticks, on the server thread. Work is told apart by its
 * profiler zones (`tickThunder` pushes `thunder`, `spawnForChunk` pushes `spawner`, a block entity's ticker its
 * type). Only the server thread's zones are recorded: the fixture's wave has one region, which runs inline on its
 * caller, so zones recorded during the call are inline work and zones recorded by the stage's run are the wave.
 *
 * - Spawning ([spawning]): thunder and the spawn attempt queue only with `enabled`, `parallelChunkTicks` and
 *   `parallelSpawning` all on; inline, each keeps vanilla's order (the thunder at its own call, before the
 *   eligibility checks and the attempt). `parallelChunkTicks = false` keeps thunder inline but still queues the
 *   attempt. The attempt uses the stored spawn state and no nearby player, so the local cap admits no mob.
 * - Block entities ([blockEntities]): a furnace's ticker queues only with `enabled` and `parallelBlockEntities` on
 *   and no serial rule naming it.
 */
internal object StageDispatchChecks {
    private const val THUNDER = "thunder"
    private const val SPAWNER = "spawner"
    private const val FURNACE = "minecraft:furnace"

    /** Whether [pos] runs both halves of `tickSpawningChunk`: thunder needs entity ticking, the attempt spawnability. */
    fun ready(level: ServerLevel, pos: ChunkPos): Boolean =
        level.chunkSource.chunkMap.distanceManager.inEntityTickingRange(pos.pack()) && level.canSpawnEntitiesInChunk(pos) &&
            level.chunkSource.getChunkNow(pos.x(), pos.z()) != null

    fun spawning(level: ServerLevel, pos: ChunkPos) {
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
            for ((options, expected) in listOf(
                Triple(true, false, true) to Dispatch(listOf(THUNDER, SPAWNER), emptyList()),
                Triple(true, true, false) to Dispatch(listOf(THUNDER), listOf(SPAWNER)),
                Triple(false, true, true) to Dispatch(listOf(THUNDER, SPAWNER), emptyList()),
                Triple(true, true, true) to Dispatch(emptyList(), listOf(THUNDER, SPAWNER)),
            )) {
                config.enabled = options.first
                config.parallelSpawning = options.second
                config.parallelChunkTicks = options.third
                Vanadium.scheduler.begin(Stage.SPAWNING)
                val ran = record(Stage.SPAWNING) { TICK_SPAWNING_CHUNK.invoke(source, chunk, categories, state) }
                check(ran == expected) {
                    "enabled=${options.first} parallelSpawning=${options.second} parallelChunkTicks=${options.third} " +
                        "ran ${ran.inline} inline and ${ran.wave} in the wave, not ${expected.inline} and ${expected.wave}"
                }
            }
        } finally {
            config.enabled = true
            config.parallelChunkTicks = previousChunkTicks
            config.parallelSpawning = previousSpawning
        }
        println("SPAWN_DISPATCH_CHECKS_PASSED chunk=$pos")
    }

    /** [pos] must be a chunk whose neighbours are all FULL, so that its block entities may tick in the wave. */
    fun blockEntities(level: ServerLevel, pos: ChunkPos) {
        val block = BlockPos(pos.minBlockX + 8, 200, pos.minBlockZ + 8)
        val config = Vanadium.config
        check(config.enabled, "Vanadium disabled")
        val previousParallel = config.parallelBlockEntities
        val previousRules = config.serialBlockEntityTypes
        try {
            // A stage of its own: the level's keeps its pending tickers and cached neighbourhood answer.
            val stage = BlockEntityStage(level, ArrayList())
            check(stage.ready(pos.pack())) { "block-entity fixture chunk $pos is not ready" }
            for ((options, expected) in listOf(
                Triple(true, false, emptyList<String>()) to Dispatch(listOf(FURNACE), emptyList()),
                Triple(false, true, emptyList<String>()) to Dispatch(listOf(FURNACE), emptyList()),
                Triple(true, true, listOf(FURNACE)) to Dispatch(listOf(FURNACE), emptyList()),
                Triple(true, true, emptyList<String>()) to Dispatch(emptyList(), listOf(FURNACE)),
            )) {
                // A fresh furnace each time: Lithium puts an idle one to sleep after its first tick.
                level.setBlock(block, Blocks.AIR.defaultBlockState(), 3)
                check(level.setBlock(block, Blocks.FURNACE.defaultBlockState(), 3)) { "furnace fixture not placed at $block" }
                @Suppress("UNCHECKED_CAST")
                val ticker = checkNotNull((TICKERS_IN_LEVEL.get(level.getChunkAt(block)) as Map<BlockPos, TickingBlockEntity>)[block]) {
                    "the furnace at $block registered no ticker"
                }
                config.enabled = options.first
                config.parallelBlockEntities = options.second
                config.serialBlockEntityTypes = options.third.toMutableList()
                config.refreshSerialRules()
                stage.begin()
                val ran = record(Stage.BLOCK_ENTITY) { stage.tick(ticker) }
                check(ran == expected) {
                    "enabled=${options.first} parallelBlockEntities=${options.second} serial=${options.third} " +
                        "ran ${ran.inline} inline and ${ran.wave} in the wave, not ${expected.inline} and ${expected.wave}"
                }
            }
        } finally {
            config.enabled = true
            config.parallelBlockEntities = previousParallel
            config.serialBlockEntityTypes = previousRules
            config.refreshSerialRules()
            level.setBlock(block, Blocks.AIR.defaultBlockState(), 3)
        }
        println("BLOCK_ENTITY_DISPATCH_CHECKS_PASSED chunk=$pos")
    }

    private data class Dispatch(val inline: List<String>, val wave: List<String>)

    /** Runs [dispatch], then [stage]'s wave, which the caller began, recording the zones of each. */
    private inline fun record(stage: Stage, dispatch: () -> Unit): Dispatch {
        val scheduler = Vanadium.scheduler
        val zones = ZoneRecorder()
        // Throws if another profiler is active on the server thread (metrics recording, tick profiling).
        Profiler.use(zones).use {
            dispatch()
            val inline = zones.take()
            scheduler.run(stage)
            return Dispatch(inline, zones.take())
        }
    }

    /** Records `thunder`, `spawner` and furnace zones in order. */
    private class ZoneRecorder : ProfilerFiller {
        private val zones = ArrayList<String>()

        fun take(): List<String> = zones.toList().also { zones.clear() }

        private fun record(name: String) {
            if (name == THUNDER || name == SPAWNER || name == FURNACE) zones.add(name)
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

    private val TICKERS_IN_LEVEL = LevelChunk::class.java.getDeclaredField("tickersInLevel").apply { isAccessible = true }
}
