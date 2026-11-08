package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.chunk.ParallelChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object ChunkLookupChecks {
    fun run(level: ServerLevel) {
        val source = level.chunkSource
        check(source is ParallelChunkCache, "parallel chunk cache was not installed")
        // Untracked positions distinguish setLoaded publication from vanilla holder lookup.
        val positions = listOf(ChunkPos(-100000, -1), ChunkPos(-1, -100000), ChunkPos(100000, 0))
        val chunks = positions.map { LevelChunk(level, it) }
        val replacement = LevelChunk(level, positions.first())
        val enabled = Vanadium.config.enabled
        val parallelLoads = Vanadium.config.parallelChunkLoads
        val readers = Executors.newFixedThreadPool(4)
        try {
            chunks.forEach { it.setLoaded(true) }
            for (active in listOf(true, false)) {
                Vanadium.config.enabled = active
                for (parallel in listOf(true, false)) {
                    Vanadium.config.parallelChunkLoads = parallel
                    val jobs = List(4) {
                        readers.submit {
                            repeat(100) {
                                for (chunk in chunks) {
                                    val pos = chunk.pos
                                    check(source.getChunkNow(pos.x(), pos.z()) === chunk, "loaded lookup missed")
                                    check(source.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) === chunk, "FULL lookup missed")
                                    check(source.hasChunk(pos.x(), pos.z()), "loaded chunk reported absent")
                                }
                            }
                        }
                    }
                    jobs.forEach { it.get(10, TimeUnit.SECONDS) }
                    // A loaded full chunk must not override a request for a different status.
                    val pos = positions.first()
                    check(source.getChunk(pos.x(), pos.z(), ChunkStatus.BIOMES, false) !== chunks.first(), "status lookup bypassed vanilla")
                }
            }
            replacement.setLoaded(true)
            chunks.first().setLoaded(false)
            val pos = positions.first()
            check(source.getChunkNow(pos.x(), pos.z()) === replacement, "old unload removed the replacement")
            replacement.setLoaded(false)
            for (chunk in chunks) chunk.setLoaded(false)
            for (pos in positions) {
                check(source.getChunkNow(pos.x(), pos.z()) == null, "unloaded chunk retained")
                check(!source.hasChunk(pos.x(), pos.z()), "untracked chunk reported present")
                check(source.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) == null, "missing lookup returned a chunk")
            }
            // Exercise a real miss/load and publication with both lock policies, including disabled mode.
            for ((index, parallel) in listOf(true, false).withIndex()) {
                Vanadium.config.parallelChunkLoads = parallel
                val chunk = level.getChunk(-8 - index, -8)
                check(source.getChunkNow(chunk.pos.x(), chunk.pos.z()) === chunk, "loaded chunk was not published")
            }
            println("CHUNK_LOOKUP_CHECKS_PASSED enabled=on/off locks=per-chunk/global")
        } finally {
            readers.shutdownNow()
            try {
                check(readers.awaitTermination(10, TimeUnit.SECONDS), "chunk readers did not stop")
            } finally {
                chunks.forEach { it.setLoaded(false) }
                replacement.setLoaded(false)
                Vanadium.config.enabled = enabled
                Vanadium.config.parallelChunkLoads = parallelLoads
            }
        }
    }
}
