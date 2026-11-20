package com.synsenetwork.vanadium.stage

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.chunk.ParallelChunkCache
import com.synsenetwork.vanadium.collections.ConcurrentLongByteMap
import com.synsenetwork.vanadium.mixin.server.world.DistanceManagerAccessor
import com.synsenetwork.vanadium.mixin.world.SimulationChunkTrackerAccessor
import com.synsenetwork.vanadium.spawning.SpawnCounts
import com.synsenetwork.vanadium.tick.Stage
import com.synsenetwork.vanadium.tick.WorkerPool
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongConsumer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Consumer
import net.minecraft.server.level.ChunkLevel
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.gamerules.GameRules

/**
 * One level's SPAWNING and CHUNK stages: what `ServerChunkCache.tickChunks` queues, created with its chunk cache.
 * A tick runs, in vanilla's order:
 * 1. [beginTick] (`tickChunks` HEAD) starts the spawning stage. The spawn count ([spawnCounts]) follows.
 * 2. For each spawning chunk in vanilla's shuffled order, [thunder] then [spawn] (`tickSpawningChunk`).
 * 3. [tickChunks] (`forEachBlockTickingChunk`): the spawning wave, then the chunks it deferred, on this thread,
 *    then the separate block-ticking pass as the CHUNK wave. Both waves end before vanilla's next step.
 *
 * What runs where, by option:
 *
 * | `enabled` | `parallelChunkTicks` | `parallelSpawning` | thunder        | spawn attempt | chunk ticks |
 * |-----------|----------------------|--------------------|----------------|---------------|-------------|
 * | false     | any                  | any                | inline         | inline        | vanilla     |
 * | true      | false                | false              | inline         | inline        | vanilla     |
 * | true      | false                | true               | inline         | wave          | vanilla     |
 * | true      | true                 | false              | inline         | inline        | wave        |
 * | true      | true                 | true               | wave           | wave          | wave        |
 *
 * Inline, a chunk's thunder runs at vanilla's call, before that chunk's eligibility checks and spawn attempt,
 * whether or not an attempt follows. In the wave a chunk's thunder and attempt are tasks of the same chunk,
 * which run in enqueue order. Server-thread only, except the tasks.
 */
class ChunkStage(private val cache: ServerChunkCache, private val level: ServerLevel) {
    /** The spawn count `tickChunks` makes before spawning, skipped while nothing can use it. */
    val spawnCounts = SpawnCounts(level, cache.chunkMap)

    private val structures = cache as? ParallelChunkCache
    private val deferred = ConcurrentLinkedQueue<SpawnTask>()

    /** Read by chunk tasks during the wave; written before it. All chunks of one pass share the speed vanilla reads once. */
    @Volatile
    private var tickSpeed = 0
    private var version = UNVERSIONED
    private var positions = LongArray(INITIAL_CAPACITY)
    private var tasks = arrayOfNulls<ChunkTask>(INITIAL_CAPACITY)
    private var count = 0
    private var byPosition = Long2ObjectOpenHashMap<ChunkTask>()
    private var spare = Long2ObjectOpenHashMap<ChunkTask>()

    /** `tickChunks` HEAD: starts the spawning stage and drops what a failed wave left deferred. */
    fun beginTick() {
        Vanadium.scheduler.begin(Stage.SPAWNING)
        deferred.clear()
    }

    /** Replaces `tickSpawningChunk`'s `tickThunder`. */
    fun thunder(chunk: LevelChunk) {
        val config = Vanadium.config
        if (!config.enabled || !config.parallelChunkTicks || !config.parallelSpawning) {
            level.tickThunder(chunk)
            return
        }
        val pos = chunk.pos
        Vanadium.scheduler.enqueue(Stage.SPAWNING, pos.x(), pos.z(), Runnable { level.tickThunder(chunk) }, null)
    }

    /** Replaces `tickSpawningChunk`'s `spawnForChunk`. */
    fun spawn(chunk: LevelChunk, state: NaturalSpawner.SpawnState, categories: List<MobCategory>) {
        val config = Vanadium.config
        if (!config.enabled || !config.parallelSpawning) {
            NaturalSpawner.spawnForChunk(level, chunk, state, categories)
            return
        }
        val pos = chunk.pos
        val spawn = Runnable { NaturalSpawner.spawnForChunk(level, chunk, state, categories) }
        Vanadium.scheduler.enqueue(Stage.SPAWNING, pos.x(), pos.z(), SpawnTask(chunk, spawn), null)
    }

    /**
     * Replaces `tickChunks`' `forEachBlockTickingChunk`: finishes spawning before vanilla's block-ticking pass, then
     * runs that pass. With parallel chunk ticks the cached chunk tasks replace vanilla's iteration; otherwise
     * [original] ticks the chunks inline, between the same stage barriers.
     */
    fun tickChunks(map: ChunkMap, consumer: Consumer<LevelChunk>, original: Operation<Void>) {
        val scheduler = Vanadium.scheduler
        scheduler.run(Stage.SPAWNING)
        runDeferred()
        scheduler.begin(Stage.CHUNK)
        val config = Vanadium.config
        if (config.enabled && config.parallelChunkTicks) {
            tickSpeed = level.gameRules.get(GameRules.RANDOM_TICK_SPEED)
            refresh()
            for (i in 0 until count) {
                val pos = positions[i]
                scheduler.enqueue(Stage.CHUNK, ChunkPos.getX(pos), ChunkPos.getZ(pos), tasks[i]!!, null)
            }
        } else {
            original.call(map, consumer)
        }
        scheduler.run(Stage.CHUNK)
    }

    /** After the spawning wave: reruns the deferred chunks on this thread in the order they were deferred; returns how many. */
    internal fun runDeferred(): Int {
        var count = 0
        while (true) {
            val task = deferred.poll() ?: break
            count++
            task.spawn.run()
        }
        return count
    }

    /** Current entity-ticking positions, in visit order. For checks; server thread. */
    fun cachedPositions(): LongArray = positions.copyOf(count)

    /**
     * One chunk's spawn attempt in the spawning wave. Spawn checks look up structure starts, which can be chunks that
     * only the server thread can still load or generate, and the server thread is busy in the same wave (and loading
     * there would run chunk-system work alongside the workers). Such a chunk's spawning is deferred instead, then
     * reruns on the server thread after the wave ([runDeferred]), where vanilla's blocking lookup is safe. Deferred
     * chunks keep their thunder-then-spawn order; deferral is rare outside joins and exploration, when nearby
     * structure chunks are still generating.
     */
    internal inner class SpawnTask(private val chunk: ChunkAccess, val spawn: Runnable) : Runnable {
        override fun run() {
            // Checking first keeps the common deferral free of partial spawns; the signal catches other lookups.
            if (!structureStartsAvailable(chunk) || !WorkerPool.runDeferrable(spawn)) deferred.add(this)
        }
    }

    /** Whether every structure start referenced by [chunk] can be read without the server thread. */
    private fun structureStartsAvailable(chunk: ChunkAccess): Boolean {
        val structures = structures ?: return true
        if (!WorkerPool.isWorkerThread() && !WorkerPool.isParallelWaveCaller()) return true
        val references = chunk.allReferences
        if (references.isEmpty()) return true
        for (starts in references.values) {
            val iterator = starts.iterator()
            while (iterator.hasNext()) {
                if (!structures.isAvailable(iterator.nextLong(), ChunkStatus.STRUCTURE_STARTS)) return false
            }
        }
        return true
    }

    /**
     * One entity-ticking chunk position's random and precipitation ticks, the positions vanilla's
     * `forEachBlockTickingChunk` visits. The positions come from the simulation levels and are cached until that
     * map's [ConcurrentLongByteMap.version] changes ([refresh]), so an unchanged level only re-enqueues its cached
     * tasks. Each task resolves the visible holder and its ticking chunk when it runs, as vanilla does while it
     * iterates, so holders that appear, finish loading or unload without a level change are seen the same tick.
     * The order is the level map's, as before; vanilla shuffles only the separate spawning pass.
     */
    private inner class ChunkTask(private val pos: Long) : Runnable {
        override fun run() {
            val chunk = cache.getVisibleChunkIfPresent(pos)?.tickingChunk ?: return
            level.tickChunk(chunk, tickSpeed)
        }
    }

    private fun refresh() {
        val distances = cache.chunkMap.distanceManager
        val levels = ((distances as DistanceManagerAccessor).`vanadium$simulationChunkTracker`() as SimulationChunkTrackerAccessor)
            .`vanadium$chunks`() as? ConcurrentLongByteMap
        if (levels != null && levels.isVersioned) {
            // Version first: a write during the scan leaves the cache stale, and the next tick rebuilds it.
            val current = levels.version
            if (current == version) return
            version = current
            rebuild { visit -> levels.forEachEntry { key, level -> if (ChunkLevel.isEntityTicking(level.toInt())) visit.accept(key) } }
        } else {
            version = UNVERSIONED
            rebuild { visit -> distances.forEachEntityTickingChunk(visit) }
        }
    }

    private inline fun rebuild(scan: (LongConsumer) -> Unit) {
        val previous = byPosition
        val next = spare
        count = 0
        scan(LongConsumer { pos ->
            if (next.containsKey(pos)) return@LongConsumer
            val task = previous.remove(pos) ?: ChunkTask(pos)
            next.put(pos, task)
            if (count == positions.size) {
                positions = positions.copyOf(count * 2)
                tasks = tasks.copyOf(count * 2)
            }
            positions[count] = pos
            tasks[count++] = task
        })
        tasks.fill(null, count, tasks.size)
        previous.clear()
        byPosition = next
        spare = previous
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
        const val UNVERSIONED = Long.MIN_VALUE
    }
}
