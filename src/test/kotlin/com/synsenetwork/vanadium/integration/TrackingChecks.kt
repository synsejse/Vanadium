package com.synsenetwork.vanadium.integration

import com.mojang.authlib.GameProfile
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.Stage
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.WorkerPool
import com.synsenetwork.vanadium.tracking.NearbyTrackers
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.animal.pig.Pig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object TrackingChecks {
    fun run(level: ServerLevel) {
        val previous = Vanadium.scheduler
        WorkerPool(4).use { pool ->
            try {
                Vanadium.scheduler = TickScheduler(pool, 1)
                val index = NearbyTrackers()
                fun player(name: String) = ServerPlayer(level.server, level, GameProfile(UUID.randomUUID(), name),
                    ClientInformation.createDefault()).apply { setPos(0.0, 100.0, 0.0) }
                val players = MutableList(8) { player("Tracking$it") }
                val playerTrackers = players.map { tracker(level, it, ConcurrentHashMap.newKeySet()) }
                playerTrackers.forEach { index.add(it, players) }
                val watchers = ArrayList<MutableSet<ServerPlayer>>()
                val mobTrackers = (0 until 160).map { i ->
                    val mob = Pig(EntityTypes.PIG, level).apply { setPos((i % 32).toDouble(), 100.0, (i / 32).toDouble()) }
                    val seen = ConcurrentHashMap.newKeySet<ServerPlayer>().also(watchers::add)
                    tracker(level, mob, seen).also { index.add(it, players) }
                }
                repeat(202) { tick(index, players, level) }
                check(watchers.all { it.size == 8 }, "staging migration lost watchers")
                players.first().setPos(10000.0, 100.0, 0.0)
                tick(index, players, level)
                check(watchers.all { it.size == 7 && players.first() !in it }, "leave-range diff failed")
                players.first().setPos(0.0, 100.0, 0.0)
                tick(index, players, level)
                check(watchers.all { it.size == 8 }, "return-range diff failed")

                // Grow player rows, reorder slots by removing a tracker, then reuse the buffers.
                val newcomer = player("NewTracker")
                players.add(newcomer)
                index.add(tracker(level, newcomer, ConcurrentHashMap.newKeySet()), players)
                tick(index, players, level)
                check(watchers.all { it.size == 9 }, "joining player lost tracking actions")
                val leaving = players.removeFirst()
                index.remove(playerTrackers.first())
                index.remove(mobTrackers.first())
                val removed = watchers.removeFirst()
                removed.clear()
                repeat(3) { tick(index, players, level) }
                check(removed.isEmpty(), "removed tracker received stale actions")
                check(watchers.all { it.size == 8 && leaving !in it && newcomer in it }, "player removal or slot reuse corrupted actions")
                println("TRACKING_PREPARATION_CHECKS_PASSED players=8 mobs=160")
            } finally {
                Vanadium.scheduler = previous
            }
        }
    }

    private fun tick(index: NearbyTrackers, players: List<ServerPlayer>, level: ServerLevel) {
        Vanadium.scheduler.begin(Stage.TRACKING)
        index.tick(players, level.chunkSource.chunkMap.distanceManager)
        Vanadium.scheduler.run(Stage.TRACKING)
    }

    /** A tracker that records watchers and fails if two threads ever write it at once. */
    private fun tracker(level: ServerLevel, entity: Entity, watchers: MutableSet<ServerPlayer>): ChunkMap.TrackedEntity =
        with(level.chunkSource.chunkMap) {
            object : ChunkMap.TrackedEntity(entity, 64, 3, true) {
                private val updating = AtomicBoolean()

                override fun updatePlayer(player: ServerPlayer) = exclusive("concurrent writes to one tracker") {
                    if (entity.distanceToSqr(player) <= 64 * 64) watchers.add(player) else watchers.remove(player)
                }

                override fun removePlayer(player: ServerPlayer) = exclusive("concurrent remove/update on one tracker") {
                    watchers.remove(player)
                }

                override fun sendToTrackingPlayersAndSelf(packet: Packet<in ClientGamePacketListener>) = Unit

                private inline fun exclusive(message: String, action: () -> Unit) {
                    check(updating.compareAndSet(false, true), message)
                    try {
                        action()
                    } finally {
                        updating.set(false)
                    }
                }
            }
        }
}
