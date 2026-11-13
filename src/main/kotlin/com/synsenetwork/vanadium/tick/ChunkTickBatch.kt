package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.collections.ConcurrentLongByteMap
import com.synsenetwork.vanadium.mixin.server.world.DistanceManagerAccessor
import com.synsenetwork.vanadium.mixin.world.SimulationChunkTrackerAccessor
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongConsumer
import net.minecraft.server.level.ChunkLevel
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos

/**
 * CHUNK collection: one persistent task per entity-ticking chunk position, the positions vanilla's
 * `forEachBlockTickingChunk` visits. The positions come from the simulation levels and are cached until
 * that map's [ConcurrentLongByteMap.version] changes, so an unchanged level only re-enqueues its cached tasks.
 * Each task resolves the visible holder and its ticking chunk when it runs, as vanilla does while it
 * iterates, so holders that appear, finish loading or unload without a level change are seen the same tick.
 * The order is the level map's, as before; vanilla shuffles only the separate spawning pass.
 *
 * Server-thread only, except [Task.run].
 */
class ChunkTickBatch(private val cache: ServerChunkCache, private val level: ServerLevel) {
    inner class Task internal constructor(private val pos: Long) : Runnable {
        override fun run() {
            val chunk = cache.getVisibleChunkIfPresent(pos)?.tickingChunk ?: return
            level.tickChunk(chunk, tickSpeed)
        }
    }

    /** Read by tasks during the wave; written before it. All chunks of one pass share the speed vanilla reads once. */
    @Volatile
    private var tickSpeed = 0
    private var version = UNVERSIONED
    private var positions = LongArray(INITIAL_CAPACITY)
    private var tasks = arrayOfNulls<Task>(INITIAL_CAPACITY)
    private var count = 0
    private var byPosition = Long2ObjectOpenHashMap<Task>()
    private var spare = Long2ObjectOpenHashMap<Task>()

    /** Enqueues this tick's chunk tasks into [Stage.CHUNK]; the caller runs the stage. */
    fun dispatch(scheduler: TickScheduler, tickSpeed: Int) {
        this.tickSpeed = tickSpeed
        refresh()
        for (i in 0 until count) {
            val pos = positions[i]
            scheduler.enqueue(Stage.CHUNK, ChunkPos.getX(pos), ChunkPos.getZ(pos), tasks[i]!!, null)
        }
    }

    /** Current entity-ticking positions, in visit order. For checks; server thread. */
    fun cachedPositions(): LongArray = positions.copyOf(count)

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
            val task = previous.remove(pos) ?: Task(pos)
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
