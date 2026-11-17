package com.synsenetwork.vanadium.integration

import com.mojang.authlib.GameProfile
import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.PacketListener
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerGamePacketListener
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.phys.Vec3
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A real player and game listener, joined through PlayerList and ticked by ServerConnectionListener.
 * Only the wire is simulated: packets are recorded and client acknowledgements arrive on the next
 * connection tick. This does not test login authentication, packet codecs or a graphical client.
 */
internal class TestPlayer(level: ServerLevel, name: String, position: Vec3) : AutoCloseable {
    private val server = level.server
    val player = ServerPlayer(server, level, GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault())
    val packets = ConcurrentLinkedQueue<Packet<*>>()
    private val replies = ConcurrentLinkedQueue<Packet<in ServerGamePacketListener>>()
    private var clientPosition = PositionMoveRotation(position, Vec3.ZERO, 0f, 0f)
    private val transport = Transport()

    init {
        check(server.isSameThread) { "test players must join on the server thread" }
        player.setPos(position.x, position.y, position.z)
        server.playerList.placeNewPlayer(transport, player, CommonListenerCookie.createInitial(player.gameProfile, false))
        server.connection.connections.add(transport)
        replies.add(ServerboundPlayerLoadedPacket())
    }

    /** Deliver a client's action through its real server packet handler on the next connection tick. */
    fun send(packet: Packet<in ServerGamePacketListener>) {
        check(transport.isConnected) { "test player is disconnected" }
        if (packet is ServerboundMovePlayerPacket) {
            val pos = clientPosition.position()
            clientPosition = PositionMoveRotation(
                Vec3(packet.getX(pos.x), packet.getY(pos.y), packet.getZ(pos.z)), Vec3.ZERO,
                packet.getYRot(clientPosition.yRot()), packet.getXRot(clientPosition.xRot()))
        }
        replies.add(packet)
    }

    override fun close() {
        check(server.isSameThread)
        transport.disconnect(DisconnectionDetails(Component.literal("test completed")))
        transport.handleDisconnection()
        server.connection.connections.remove(transport)
        transport.channelForCallbacks.finishAndReleaseAll()
    }

    private inner class Transport : Connection(PacketFlow.SERVERBOUND) {
        val channelForCallbacks = EmbeddedChannel()
        private var listener: PacketListener? = null
        private var disconnected: DisconnectionDetails? = null
        private var handled = false

        override fun <T : PacketListener> setupInboundProtocol(protocol: ProtocolInfo<T>, packetListener: T) {
            listener = packetListener
        }

        override fun getPacketListener(): PacketListener? = listener
        override fun getRemoteAddress() = InetSocketAddress("127.0.0.1", 0)
        override fun getLoggableAddress(logIPs: Boolean) = "test-player"
        override fun isMemoryConnection() = false // Exercise remote-client chunk batching and acknowledgements.
        override fun isConnected() = disconnected == null
        override fun isConnecting() = false
        override fun flushChannel() = Unit
        override fun setReadOnly() = Unit
        override fun getDisconnectionDetails() = disconnected

        override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
            record(packet)
            listener?.operationComplete(channelForCallbacks.newSucceededFuture())
        }

        private fun record(packet: Packet<*>) {
            packets.add(packet)
            when (packet) {
                is ClientboundBundlePacket -> packet.subPackets().forEach(::record)
                is ClientboundPlayerPositionPacket -> {
                    val target = PositionMoveRotation.calculateAbsolute(clientPosition, packet.change(), packet.relatives())
                    clientPosition = target
                    val pos = target.position()
                    replies.add(ServerboundAcceptTeleportationPacket(packet.id(), pos.x, pos.y, pos.z, target.yRot(), target.xRot()))
                }
                is ClientboundChunkBatchFinishedPacket -> replies.add(ServerboundChunkBatchReceivedPacket(64.0f))
                is ClientboundKeepAlivePacket -> replies.add(ServerboundKeepAlivePacket(packet.id))
            }
        }

        override fun tick() {
            val game = listener as ServerGamePacketListenerImpl
            // Bound this batch before ticking/handling: replies produced by either wait for the next tick.
            val received = replies.size
            // Establish vanilla's movement baseline before accepting the first teleport acknowledgement.
            game.tick()
            repeat(received) { if (isConnected) replies.poll()?.handle(game) }
            if (isConnected) ServerboundClientTickEndPacket.INSTANCE.handle(game)
        }

        override fun disconnect(details: DisconnectionDetails) {
            if (disconnected == null) disconnected = details
        }

        override fun handleDisconnection() {
            val details = disconnected ?: return
            if (!handled) {
                handled = true
                listener?.onDisconnect(details)
            }
        }
    }
}
