package com.synsenetwork.vanadium.integration

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.state.BlockState

/** The block changes a clientbound packet carries: one for a single-block update, each change of a section update, none otherwise. */
internal fun blockUpdates(packet: Packet<*>): List<Pair<BlockPos, BlockState>> = when (packet) {
    is ClientboundBlockUpdatePacket -> listOf(packet.pos to packet.blockState)
    is ClientboundSectionBlocksUpdatePacket -> ArrayList<Pair<BlockPos, BlockState>>().also { out ->
        // runUpdates reuses one mutable position.
        packet.runUpdates { pos, state -> out.add(pos.immutable() to state) }
    }
    else -> emptyList()
}
