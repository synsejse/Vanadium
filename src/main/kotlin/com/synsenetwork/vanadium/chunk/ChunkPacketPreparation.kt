package com.synsenetwork.vanadium.chunk

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.Preparation
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.world.level.chunk.LevelChunk
import java.util.IdentityHashMap

/**
 * Section serialization between world ticks, scoped to one chunk batch on the sending thread.
 * Block-entity callbacks and light snapshots stay on the caller.
 */
class ChunkPacketPreparation : AutoCloseable {
    private val previous: MutableMap<LevelChunk, ByteArray>? = ACTIVE.get()

    init {
        ACTIVE.set(IdentityHashMap())
    }

    override fun close() {
        if (previous == null) ACTIVE.remove() else ACTIVE.set(previous)
    }

    companion object {
        private const val MIN_PARALLEL_CHUNKS = 4
        private const val MAX_PREPARED_CHUNKS = 64
        private val ACTIVE = ThreadLocal<MutableMap<LevelChunk, ByteArray>?>()

        @JvmStatic
        fun prepare(chunks: List<LevelChunk>) {
            val batch = ACTIVE.get() ?: return
            val config = Vanadium.config
            if (!config.enabled || !config.parallelChunkPackets || chunks.size < MIN_PARALLEL_CHUNKS) return
            // C2ME may enlarge batches; cap retained buffers and let later chunks use the vanilla path.
            val count = minOf(MAX_PREPARED_CHUNKS, chunks.size)
            val payloads = arrayOfNulls<ByteArray>(count)
            Vanadium.scheduler.prepare(Preparation.CHUNK_PACKETS, List(count) { i -> Runnable { payloads[i] = serializeSections(chunks[i]) } })
            for (i in 0 until count) batch[chunks[i]] = payloads[i]!!
        }

        @JvmStatic
        fun payload(chunk: LevelChunk): ByteArray? = ACTIVE.get()?.get(chunk)

        private fun serializeSections(chunk: LevelChunk): ByteArray {
            val bytes = ByteArray(chunk.sections.sumOf { it.serializedSize })
            val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
            try {
                buffer.writerIndex(0)
                ClientboundLevelChunkPacketData.extractChunkData(buffer, chunk)
                return bytes
            } finally {
                buffer.release()
            }
        }
    }
}
