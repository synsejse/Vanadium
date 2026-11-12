package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.tick.ChunkTickAccess
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos

/** The cached CHUNK positions against a fresh scan of the ticking chunks vanilla would visit. */
internal object ChunkTickCacheChecks {
    /**
     * True once the cache built by this tick's chunk pass equals a fresh scan and holds (or lacks) the chunk.
     * Ticket changes after the pass can make them differ for a tick, so callers wait for agreement.
     */
    fun matches(level: ServerLevel, chunkX: Int, chunkZ: Int, ticking: Boolean): Boolean {
        val cached = (level.chunkSource as ChunkTickAccess).`vanadium$chunkTicks`().cachedPositions()
        val distinct = LongOpenHashSet(cached)
        check(distinct.size == cached.size) { "the chunk tick cache holds a position twice" }
        val fresh = LongOpenHashSet()
        level.chunkSource.chunkMap.distanceManager.forEachEntityTickingChunk { fresh.add(it) }
        val pos = ChunkPos.pack(chunkX, chunkZ)
        return distinct == fresh && (pos in distinct) == ticking
    }
}
