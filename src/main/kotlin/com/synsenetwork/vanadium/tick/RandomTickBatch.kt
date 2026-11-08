package com.synsenetwork.vanadium.tick

import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.ServerLevel

/**
 * Per-level CHUNK collection. While vanilla iterates entity-ticking positions, the server thread only
 * records each chunk holder; workers resolve the holder's ticking chunk (a future lookup that misses the
 * server thread's cache) and its cell. Holders without a ticking chunk are skipped, as vanilla skips them.
 */
class RandomTickBatch(private val level: ServerLevel) : BatchEnqueue.Mapper<ChunkHolder> {
    private val batch = BatchEnqueue(this)
    private var tickSpeed = 0

    /** Starts a pass; all chunks of one tick share the speed vanilla reads once per pass. */
    fun begin(tickSpeed: Int) {
        this.tickSpeed = tickSpeed
    }

    fun add(holder: ChunkHolder) = batch.add(holder)

    /** Every resolved chunk is queued into its cell; there are no serial items. */
    fun dispatch(scheduler: TickScheduler) = batch.dispatch(scheduler, Stage.CHUNK) { }

    override fun key(item: ChunkHolder): Long = item.tickingChunk?.pos?.pack() ?: BatchEnqueue.SKIP

    override fun task(item: ChunkHolder): Runnable {
        val chunk = item.tickingChunk!!
        val speed = tickSpeed
        return Runnable { level.tickChunk(chunk, speed) }
    }
}
