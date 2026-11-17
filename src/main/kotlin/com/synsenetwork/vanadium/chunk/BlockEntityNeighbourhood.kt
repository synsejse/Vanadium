package com.synsenetwork.vanadium.chunk

import net.minecraft.server.level.ServerChunkCache

/**
 * Whether the block entities of a chunk may tick on a worker: only once the chunk's ticking future has completed,
 * which vanilla completes only when every neighbouring chunk is FULL.
 *
 * Vanilla ticks block entities earlier, as soon as the chunk's ticket level allows block ticking, while its
 * neighbours may still be loading. A tick that reads past the chunk's edge then loads the neighbour on the server
 * thread: `BlockEntity.setChanged` alone (furnaces, hoppers, vaults, …) notifies comparators up to two blocks out,
 * through a solid block, without checking that the chunk there is loaded. On a worker such a read waits for the
 * server thread mid-wave. Until the neighbours are FULL the chunk's block entities therefore tick serially on the
 * server thread, as in vanilla; afterwards every read within 16 blocks lands in a FULL chunk.
 *
 * Server thread only. The answer for the last chunk asked about is kept until [reset]: tickers are registered chunk
 * by chunk, so consecutive block entities mostly share a chunk.
 */
class BlockEntityNeighbourhood(private val chunks: ServerChunkCache) {
    private var lastChunk = 0L
    private var lastReady = false
    private var cached = false

    /** Forgets the cached answer; call at the start of each block-entity phase. */
    fun reset() {
        cached = false
    }

    /** Whether packed chunk [chunkKey]'s neighbours are all FULL, so its block entities may tick on a worker. */
    fun ready(chunkKey: Long): Boolean {
        if (cached && chunkKey == lastChunk) return lastReady
        val ready = chunks.isPositionTicking(chunkKey)
        lastChunk = chunkKey
        lastReady = ready
        cached = true
        return ready
    }
}
