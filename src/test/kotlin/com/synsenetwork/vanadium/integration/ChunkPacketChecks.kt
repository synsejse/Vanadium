package com.synsenetwork.vanadium.integration

import com.mojang.authlib.GameProfile
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.chunk.ChunkPacketPreparation
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.Connection
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.PlayerChunkSender
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.level.block.Blocks
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object ChunkPacketChecks {
    fun run(level: ServerLevel) {
        try {
            val chunks = (0 until 8).map { x ->
                level.setChunkForced(x, 0, true)
                level.getChunk(x, 0)
            }
            level.setBlockAndUpdate(BlockPos(0, 100, 0), Blocks.CHEST.defaultBlockState())
            val reference = chunks.map { bytes(level, ClientboundLevelChunkPacketData(it)) }
            ChunkPacketPreparation().use {
                ChunkPacketPreparation.prepare(chunks)
                for ((i, chunk) in chunks.withIndex()) {
                    check(ChunkPacketPreparation.payload(chunk) != null, "missing prepared section data")
                    check(reference[i].contentEquals(bytes(level, ClientboundLevelChunkPacketData(chunk))), "prepared packet differs from vanilla")
                }
            }
            check(ChunkPacketPreparation.payload(chunks.first()) == null, "packet scope retained chunks")
            val previous = Vanadium.config.parallelChunkPackets
            try {
                ChunkPacketPreparation().use {
                    Vanadium.config.parallelChunkPackets = false
                    ChunkPacketPreparation.prepare(chunks)
                    check(ChunkPacketPreparation.payload(chunks.first()) == null, "disabled packet preparation ran")
                }
            } finally {
                Vanadium.config.parallelChunkPackets = previous
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            val source = level.chunkSource
            fun ready() = chunks.all { source.chunkMap.getChunkToSend(it.pos.pack()) != null }
            source.mainThreadProcessor.managedBlock { System.nanoTime() >= deadline || ready() }
            check(ready(), "fixture chunks not ready for sending")
            val profile = GameProfile(UUID.randomUUID(), "PacketChecks")
            val player = ServerPlayer(level.server, level, profile, ClientInformation.createDefault())
            val packets = ArrayList<Packet<*>>()
            val caller = Thread.currentThread()
            player.connection = connection(level, player, profile) { packet ->
                check(Thread.currentThread() === caller, "packet send moved off the caller")
                if (packet is ClientboundLevelChunkWithLightPacket) {
                    check(ChunkPacketPreparation.payload(chunks[packet.x]) != null, "sender did not prepare packets")
                    check(reference[packet.x].contentEquals(bytes(level, packet.chunkData)), "sender changed chunk data")
                }
                packets.add(packet)
            }
            player.setPos(0.0, 100.0, 0.0)
            val sender = PlayerChunkSender(false)
            chunks.forEach(sender::markChunkPendingToSend)
            sender.sendNextChunks(player)
            check(packets.first() is ClientboundChunkBatchStartPacket, "missing batch start")
            check(packets.last() is ClientboundChunkBatchFinishedPacket, "missing batch finish")
            check(packets.count { it is ClientboundLevelChunkWithLightPacket } == 8, "wrong packet batch size")
            check(ChunkPacketPreparation.payload(chunks.first()) == null, "sender leaked prepared buffers")
            val sent = packets.size
            sender.sendNextChunks(player)
            check(packets.size == sent, "sender ignored batch acknowledgment limit")

            player.connection = connection(level, player, profile) { throw IllegalStateException("fixture send failure") }
            val failing = PlayerChunkSender(false)
            chunks.forEach(failing::markChunkPendingToSend)
            try {
                failing.sendNextChunks(player)
                throw AssertionError("send failure swallowed")
            } catch (expected: IllegalStateException) {
                check(expected.message == "fixture send failure", "unexpected send failure")
            }
            check(ChunkPacketPreparation.payload(chunks.first()) == null, "failed sender leaked prepared buffers")
            println("CHUNK_PACKET_CHECKS_PASSED chunks=8 blockEntity=chest")
        } finally {
            for (x in 0 until 8) level.setChunkForced(x, 0, false)
        }
    }

    private fun connection(level: ServerLevel, player: ServerPlayer, profile: GameProfile, onSend: (Packet<*>) -> Unit) =
        object : ServerGamePacketListenerImpl(level.server, Connection(PacketFlow.SERVERBOUND), player,
            CommonListenerCookie.createInitial(profile, false)) {
            override fun send(packet: Packet<*>) = onSend(packet)
        }

    private fun bytes(level: ServerLevel, data: ClientboundLevelChunkPacketData): ByteArray {
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess())
        try {
            data.write(buffer)
            return ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) }
        } finally {
            buffer.release()
        }
    }
}
