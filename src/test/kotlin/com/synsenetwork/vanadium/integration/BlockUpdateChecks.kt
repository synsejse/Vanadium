package com.synsenetwork.vanadium.integration

import com.mojang.authlib.GameProfile
import it.unimi.dsi.fastutil.longs.LongSet
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ChunkResult
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Live Fabric regression checks; packaged only by blockUpdateChecksJar. */
class BlockUpdateChecks : ModInitializer {
    override fun onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            checkBlocks(server)
            checkDirty(server)
            val level = server.overworld()
            ChunkLookupChecks.run(level)
            TickSafetyChecks.run(level)
            NavigationChecks.run(level)
            TrackingChecks.run(level)
            SpawnChecks.run(level)
            ItemChecks.run(level)
            ChunkPacketChecks.run(level)
            println("BLOCK_AND_DIRTY_CHECKS_PASSED")
        }
    }

    private fun checkBlocks(server: MinecraftServer) {
        val level = server.overworld()
        val pos = BlockPos(1000, 100, 1000)
        val chunk = level.getChunkAt(pos)
        val profile = GameProfile(UUID.randomUUID(), "BlockChecks")
        val player = ServerPlayer(server, level, profile, ClientInformation.createDefault())
        val packets = ArrayList<Packet<*>>()
        player.connection = object : ServerGamePacketListenerImpl(server, Connection(PacketFlow.SERVERBOUND), player,
            CommonListenerCookie.createInitial(profile, false)) {
            override fun send(packet: Packet<*>) {
                packets.add(packet)
            }
        }
        player.setPos(1000.5, 100.0, 1002.5)
        val holder = object : ChunkHolder(chunk.pos, 31, level, level.chunkSource.lightEngine,
            { _, _, _, _ -> }, { _, _ -> listOf(player) }) {
            override fun getTickingChunk(): LevelChunk = chunk

            // C2ME's no-tick view distance reads the full-chunk future instead.
            override fun getFullChunkFuture(): CompletableFuture<ChunkResult<LevelChunk>> =
                CompletableFuture.completedFuture(ChunkResult.of(chunk))
        }
        check(holder.tickingChunk === chunk, "fixture ticking chunk missing")
        var sequence = 0
        for (mode in listOf(GameType.CREATIVE, GameType.SURVIVAL)) {
            player.setGameMode(mode)
            player.setOnGround(true)
            for (count in intArrayOf(1, 2, 1)) {
                val changed = ArrayList<BlockPos>()
                for (i in 0 until count) {
                    val target = pos.offset(i, 0, 0)
                    level.setBlockAndUpdate(target, Blocks.DIRT.defaultBlockState())
                    player.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        Direction.UP, level.maxY, ++sequence)
                    if (mode == GameType.SURVIVAL) {
                        repeat(40) { player.gameMode.tick() }
                        player.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                            Direction.UP, level.maxY, ++sequence)
                    }
                    check(level.getBlockState(target).isAir) { "$mode server did not break block $target" }
                    holder.blockChanged(target)
                    check(holder.hasChangesToBroadcast(), "block update was not queued")
                    changed.add(target)
                }
                packets.clear()
                holder.broadcastChanges(chunk)
                val sent = ArrayList<BlockPos>()
                for (packet in packets) {
                    when (packet) {
                        is ClientboundBlockUpdatePacket -> {
                            check(packet.blockState.isAir, "wrong single-block state")
                            sent.add(packet.pos)
                        }
                        is ClientboundSectionBlocksUpdatePacket -> packet.runUpdates { p, state ->
                            check(state.isAir, "wrong section-block state")
                            sent.add(p.immutable())
                        }
                    }
                }
                check(sent.size == changed.size && sent.containsAll(changed)) { "$mode missing block updates: expected=$changed actual=$sent" }
            }
        }
        println("BLOCK_PACKETS_PASSED")
    }

    private fun checkDirty(server: MinecraftServer) {
        val map = server.overworld().chunkSource.chunkMap
        val mark = ChunkMap::class.java.getDeclaredMethod("setChunkUnsaved", ChunkPos::class.java).apply { isAccessible = true }
        val dirty = ChunkMap::class.java.getDeclaredField("chunksToEagerlySave").apply { isAccessible = true }.get(map) as LongSet
        val pool = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().factory())
        try {
            val start = CountDownLatch(1)
            val tasks = (0 until 4).map { worker ->
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS), "start timeout")
                    for (i in 0 until 4096) mark.invoke(map, ChunkPos(100000 + worker, i))
                }
            }
            start.countDown()
            for (task in tasks) task.get(15, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(5, TimeUnit.SECONDS), "dirty-chunk workers did not stop")
        }
        // C2ME can replace vanilla eager saving. The regular chunk-map tick must also collect worker
        // notifications before C2ME's idle autosave scans the vanilla set.
        ChunkMap::class.java.getDeclaredMethod("tick", BooleanSupplier::class.java).apply { isAccessible = true }
            .invoke(map, BooleanSupplier { false })
        for (worker in 0 until 4) for (i in 0 until 4096) {
            check(dirty.contains(ChunkPos.pack(100000 + worker, i))) { "dirty chunk lost: $worker/$i" }
        }
        println("DIRTY_CHUNK_CONCURRENCY_PASSED")
    }
}
