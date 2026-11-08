package com.synsenetwork.vanadium.tick

import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.ServerLevel

/** Records holders on the caller; workers resolve ticking chunks and their cells. */
class ChunkTickBatch(private val level: ServerLevel) : BatchEnqueue.Mapper<ChunkHolder> {
    private val batch = BatchEnqueue(Preparation.CHUNK_BATCH, this)
    private var tickSpeed = 0

    /** Starts a pass; all chunks of one tick share the speed vanilla reads once per pass. */
    fun begin(tickSpeed: Int) {
        this.tickSpeed = tickSpeed
    }

    fun add(holder: ChunkHolder) = batch.add(holder)

    fun dispatch(scheduler: TickScheduler) = batch.dispatch(scheduler, Stage.CHUNK) { }

    override fun key(item: ChunkHolder): Long = item.tickingChunk?.pos?.pack() ?: BatchEnqueue.SKIP

    override fun task(item: ChunkHolder): Runnable {
        val chunk = item.tickingChunk!!
        val speed = tickSpeed
        return Runnable { level.tickChunk(chunk, speed) }
    }
}
