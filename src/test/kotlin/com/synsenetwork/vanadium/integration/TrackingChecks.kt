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
        val mob = Pig(EntityTypes.PIG, level)
        for (interval in listOf(net.minecraft.world.entity.UpdateInterval.periodic(3), net.minecraft.world.entity.UpdateInterval.NEVER)) {
            val tracked = with(level.chunkSource.chunkMap) { TrackedEntity(mob, 64, interval, true) }
            val entry = (tracked as com.synsenetwork.vanadium.mixin.server.world.TrackedEntityAccessor).`vanadium$entry`()
            mob.setPos(120.0, 100.0, -120.0)
            NearbyTrackers.forceResync(entry)
            check(entry.positionBase == mob.trackingPosition(), "resync did not refresh the position baseline")
            check(!mob.syncPosition, "vanilla did not consume the forced resync")
            mob.setPos(0.0, 100.0, 0.0)
        }
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
                checkNoPlayerTicks(level, ::player)
            } finally {
                Vanadium.scheduler = previous
            }
        }
    }

    /**
     * A level without players skips the move scan. Trackers painted far away and moved next to the origin
     * while nobody was there must be found by the first player: the first scan after the join catches up.
     * 400 trackers also take the parallel scan once a player is present.
     */
    private fun checkNoPlayerTicks(level: ServerLevel, player: (String) -> ServerPlayer) {
        val index = NearbyTrackers()
        val mobs = (0 until 400).map { i -> Pig(EntityTypes.PIG, level).apply { setPos(5000.0 + i % 32, 100.0, (i / 32).toDouble()) } }
        val watchers = mobs.map { mob -> ConcurrentHashMap.newKeySet<ServerPlayer>().also { index.add(tracker(level, mob, it), emptyList()) } }
        repeat(202) { tick(index, emptyList(), level) } // Leave staging: painted at x = 5000.
        mobs.forEachIndexed { i, mob -> mob.setPos((i % 32).toDouble(), 100.0, (i / 32).toDouble()) }
        repeat(3) { tick(index, emptyList(), level) }
        val joined = player("QuietJoin")
        val players = listOf(joined)
        index.add(tracker(level, joined, ConcurrentHashMap.newKeySet()), players)
        tick(index, players, level)
        check(watchers.all { joined in it }) { "a player joining a quiet level missed ${watchers.count { joined !in it }} moved trackers" }
        mobs.forEach { it.setPos(it.x + 5000.0, 100.0, it.z) }
        tick(index, players, level)
        check(watchers.all { it.isEmpty() }) { "trackers that left after the join kept their watcher" }
        println("TRACKING_NO_PLAYER_CHECKS_PASSED mobs=400")
    }

    private fun tick(index: NearbyTrackers, players: List<ServerPlayer>, level: ServerLevel) {
        Vanadium.scheduler.begin(Stage.TRACKING)
        index.tick(players, level.chunkSource.chunkMap.distanceManager)
        Vanadium.scheduler.run(Stage.TRACKING)
    }

    /** A tracker that records watchers and fails if two threads ever write it at once. */
    private fun tracker(level: ServerLevel, entity: Entity, watchers: MutableSet<ServerPlayer>): ChunkMap.TrackedEntity =
        with(level.chunkSource.chunkMap) {
            object : ChunkMap.TrackedEntity(entity, 64, net.minecraft.world.entity.UpdateInterval.periodic(3), true) {
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
