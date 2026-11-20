package com.synsenetwork.vanadium.stage

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.Stage
import java.util.concurrent.ConcurrentLinkedQueue
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.entity.TickingBlockEntity
import net.minecraft.world.level.chunk.LevelChunk

/**
 * One server level's BLOCK_ENTITY stage: what `Level.tickBlockEntities` queues, created with the level.
 * [tickers] is the level's active ticker list, which only the server thread changes, at [begin].
 *
 * A ticker runs in the wave only when parallel block entities are on, it ticks a block entity directly (not a
 * removed or rebound wrapper), no serial type rule names it, and its chunk's neighbours are all FULL ([ready]).
 * Every other ticker ticks inline on the server thread, in list order, as in vanilla.
 */
class BlockEntityStage(private val level: ServerLevel, private val tickers: MutableList<TickingBlockEntity>) {
    /** Registrations from any thread, also during a wave; they join [tickers] at the next [begin]. */
    private val pending = ConcurrentLinkedQueue<TickingBlockEntity>()
    private var lastChunk = 0L
    private var lastReady = false
    private var cached = false

    /** Replaces `addBlockEntityTicker`. */
    fun add(ticker: TickingBlockEntity) {
        pending.add(ticker)
    }

    /** `tickBlockEntities` HEAD: adds the pending tickers, as vanilla does with its own, and starts the stage. */
    fun begin() {
        while (true) {
            val ticker = pending.poll() ?: break
            tickers.add(ticker)
        }
        cached = false
        Vanadium.scheduler.begin(Stage.BLOCK_ENTITY)
    }

    /** Replaces `tickBlockEntities`' `TickingBlockEntity.tick()`: queues [ticker] for the wave or ticks it here. */
    fun tick(ticker: TickingBlockEntity) {
        val config = Vanadium.config
        if (!config.enabled || !config.parallelBlockEntities) {
            ticker.tick()
            return
        }
        if (ticker !is LevelChunk.RebindableTickingBlockEntityWrapper) {
            ticker.tick()
            return
        }
        val direct = ticker.ticker
        if (direct !is LevelChunk.BoundTickingBlockEntity<*>) {
            ticker.tick()
            return
        }
        val blockEntity = direct.blockEntity
        if (config.hasSerialBlockEntityRules()) {
            // No rule can name an unregistered type.
            val id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.type)
            if (id != null && config.isSerialBlockEntity(id)) {
                ticker.tick()
                return
            }
        }
        val chunkX = blockEntity.blockPos.x shr 4
        val chunkZ = blockEntity.blockPos.z shr 4
        if (!ready(ChunkPos.pack(chunkX, chunkZ))) {
            // A neighbouring chunk is still loading: a read past this chunk's edge would load it on the server
            // thread, so tick here, as vanilla does, rather than make a worker wait for it mid-wave.
            ticker.tick()
            return
        }
        val scheduler = Vanadium.scheduler
        val label = if (scheduler.detailedDiagnostics) BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.type).toString() else null
        scheduler.enqueue(Stage.BLOCK_ENTITY, chunkX, chunkZ, ticker::tick, label)
    }

    /**
     * Whether the block entities of packed chunk [chunkKey] may tick on a worker: only once the chunk's ticking
     * future has completed, which vanilla completes only when every neighbouring chunk is FULL.
     *
     * Vanilla ticks block entities earlier, as soon as the chunk's ticket level allows block ticking, while its
     * neighbours may still be loading. A tick that reads past the chunk's edge then loads the neighbour on the server
     * thread: `BlockEntity.setChanged` alone (furnaces, hoppers, vaults, …) notifies comparators up to two blocks out,
     * through a solid block, without checking that the chunk there is loaded. On a worker such a read waits for the
     * server thread mid-wave. Until the neighbours are FULL the chunk's block entities therefore tick serially on the
     * server thread, as in vanilla; afterwards every read within 16 blocks lands in a FULL chunk.
     *
     * The answer for the last chunk asked about is kept until the next [begin]: tickers are registered chunk by
     * chunk, so consecutive block entities mostly share a chunk.
     */
    internal fun ready(chunkKey: Long): Boolean {
        if (cached && chunkKey == lastChunk) return lastReady
        val ready = level.chunkSource.isPositionTicking(chunkKey)
        lastChunk = chunkKey
        lastReady = ready
        cached = true
        return ready
    }
}
