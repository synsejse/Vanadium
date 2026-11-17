package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.live.TestPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/** Real player membership, connection ticks, chunk delivery, tracking and block edits across real ticks. */
internal object PlayerChecks {
    /** The pig, the edited block and the second player are in (16, 8); the first player walks into (17, 8). */
    private val CHUNKS = listOf(16 to 8, 17 to 8)

    /**
     * The floor loads the fixture chunks before the players join, so vanilla tracking sends them at once
     * (C2ME's no-tick view distance sends FULL chunks). C2ME's ServerAccessibleChunkSending status, reached
     * a few ticks later on the way to BLOCK_TICKING, sends each chunk again through
     * `ChunkMap.onChunkReadyToSend`. Until the re-send goes out, the chunk is pending for the player, so
     * `isChunkTracked` is false: vanilla withholds its block updates and unpairs its entities from that
     * player, and a block break or discard in that window is legitimately never sent as a packet.
     * A ticking chunk has passed that status; it is settled once neither player has it pending.
     */
    private fun settled(level: ServerLevel, test: TestPlayer, x: Int, z: Int): Boolean {
        val chunks = level.chunkSource
        return chunks.getVisibleChunkIfPresent(ChunkPos.pack(x, z))?.tickingChunk != null &&
            chunks.chunkMap.isChunkTracked(test.player, x, z) &&
            test.packets.any { it is ClientboundLevelChunkWithLightPacket && it.x == x && it.z == z }
    }

    /** Whether the latest pairing packet this client received for [id] spawned it (re-sends unpair and re-pair). */
    private fun pairedWith(test: TestPlayer, id: Int): Boolean = test.packets.lastOrNull {
        (it is ClientboundAddEntityPacket && it.id == id) || (it is ClientboundRemoveEntitiesPacket && id in it.entityIds)
    } is ClientboundAddEntityPacket

    fun schedule(level: ServerLevel, checks: TickedChecks) {
        val server = level.server
        lateinit var first: TestPlayer
        lateinit var second: TestPlayer
        lateinit var pig: Pig
        lateinit var edit: BlockPos
        var startX = 0.0
        var moved = 0
        var playerTick = 0

        checks.then("two players join through PlayerList") {
            val x = 16 * 16 + 15
            val z = 8 * 16 + 8
            val y = 100
            // The live world is empty; give the players and pig a small, stable floor across the boundary.
            for (dx in -5..5) for (dz in -3..4) {
                level.setBlockAndUpdate(BlockPos(x + dx, y - 1, z + dz), Blocks.STONE.defaultBlockState())
            }
            startX = x + 0.5
            first = TestPlayer(level, "LivePlayerA", Vec3(startX, y.toDouble(), z + 0.5))
            second = TestPlayer(level, "LivePlayerB", Vec3(x - 2.5, y.toDouble(), z + 0.5))
            for (test in listOf(first, second)) {
                test.player.setGameMode(GameType.CREATIVE)
                check(server.playerList.getPlayer(test.player.uuid) === test.player)
                check(level.players().contains(test.player))
            }
            pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND)!!.apply {
                setPos(x - 1.5, y.toDouble(), z + 2.5)
                setNoAi(true)
                setPersistenceRequired()
            }
            check(level.addFreshEntity(pig))
            edit = BlockPos(x, y, z + 1)
            playerTick = first.player.tickCount
        }
        checks.awaitAsync("both clients settle their fixture chunks, each other and the tracked pig", 150) {
            listOf(first to second, second to first).all { (test, other) ->
                test.player.connection.hasClientLoaded() &&
                    CHUNKS.all { (x, z) -> settled(level, test, x, z) } &&
                    pairedWith(test, other.player.id) && pairedWith(test, pig.id)
            }
        }
        checks.then("start observing client movement") { second.packets.clear() }
        checks.until("client movement crosses a chunk boundary") {
            check(first.player.tickCount > playerTick) { "registered player did not receive real ticks" }
            if (moved == 4) {
                check(first.player.x > startX + 1.5) { "client movement was not applied" }
                check(first.player.chunkPosition().x() == 17) { "player did not cross the chunk boundary" }
                true
            } else {
                moved++
                first.send(ServerboundMovePlayerPacket.Pos(startX + moved * 0.5, first.player.y, first.player.z, true, false))
                false
            }
        }
        checks.await("the other player receives movement", 10) {
            second.packets.any { packet ->
                when (packet) {
                    is ClientboundMoveEntityPacket -> packet.hasPosition() && packet.getEntity(level) === first.player
                    is ClientboundEntityPositionSyncPacket -> packet.id() == first.player.id
                    else -> false
                }
            }
        }
        checks.then("prepare a block edit visible to the other player") {
            level.setBlockAndUpdate(edit, Blocks.DIRT.defaultBlockState())
        }
        checks.then("player breaks the block through the packet handler") {
            first.packets.clear()
            second.packets.clear()
            first.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, edit, Direction.UP, 1))
        }
        checks.await("the block break reaches the world and the other player", 20) {
            level.getBlockState(edit).isAir && second.packets.any { packet ->
                when (packet) {
                    is ClientboundBlockUpdatePacket -> packet.pos == edit && packet.blockState.isAir
                    is ClientboundSectionBlocksUpdatePacket -> {
                        var found = false
                        packet.runUpdates { pos, state -> if (pos == edit && state.isAir) found = true }
                        found
                    }
                    else -> false
                }
            }
        }
        checks.then("a tracked entity is removed") {
            second.packets.clear()
            pig.discard()
        }
        checks.await("the other player receives the entity removal", 20) {
            second.packets.any { it is ClientboundRemoveEntitiesPacket && pig.id in it.entityIds }
        }
        checks.then("disconnect the first player") {
            second.packets.clear()
            first.close()
        }
        checks.then("disconnect removes player membership and informs the remaining player") {
            check(server.playerList.getPlayer(first.player.uuid) == null)
            check(first.player !in level.players())
            check(level.getEntity(first.player.id) == null)
            check(second.packets.any { it is ClientboundPlayerInfoRemovePacket && first.player.uuid in it.profileIds() })
            second.close()
        }
        checks.then("all mock players and their connections are removed") {
            check(server.playerList.players.isEmpty())
            check(level.players().isEmpty())
            check(server.connection.connections.isEmpty())
            println("PLAYER_CHECKS_PASSED players=2 join=1 movement=1 chunks=1 tracking=1 blockEdit=1 disconnect=1")
        }
    }
}
