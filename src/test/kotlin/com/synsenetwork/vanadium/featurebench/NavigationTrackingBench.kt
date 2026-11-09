package com.synsenetwork.vanadium.featurebench

import com.mojang.authlib.GameProfile
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.Stage
import com.synsenetwork.vanadium.tracking.NavigationAccess
import com.synsenetwork.vanadium.tracking.NavigationIndex
import com.synsenetwork.vanadium.tracking.NearbyTrackers
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import java.util.UUID

internal object NavigationTrackingBench {
    fun navigation(level: ServerLevel, measurements: Measurements) {
        val index = (level as NavigationAccess).`vanadium$navigations`()
        val pathField = PathNavigation::class.java.getDeclaredField("path").apply { isAccessible = true }
        for (dense in listOf(false, true)) {
            val mobs = ArrayList<Mob>()
            try {
                for (i in 0 until 1024) {
                    val mob = Pig(EntityTypes.PIG, level)
                    val x = if (dense) 0 else (i % 32 - 16) * 128
                    val z = if (dense) 0 else (i / 32 - 16) * 128
                    mob.setPos(x.toDouble(), 100.0, z.toDouble())
                    pathField.set(mob.navigation, Path(List(32) { j -> Node(x + j, 100, z) }, BlockPos(x + 31, 100, z), true))
                    mobs.add(mob)
                    index.add(mob)
                }
                index.refresh()
                for (edits in intArrayOf(1, 64)) for (moving in listOf(false, true)) {
                    if (dense && (moving || edits == 1)) continue
                    var enabled = true
                    val name = "navigation-${if (dense) "dense" else "spread"}-edits-$edits-${if (moving) "dirty" else "static"}"
                    measurements.compare(name, { enabled = it }) {
                        // Same invalidation hooks remain installed in both modes, as with enabled=false.
                        if (moving) mobs.forEach(NavigationIndex::invalidate)
                        if (enabled) index.refresh()
                        var matches = 0
                        for (i in 0 until edits) {
                            val pos = mobs[i * 13 % mobs.size].blockPosition()
                            val candidates: Iterable<Mob> = if (enabled) index.candidates(pos) else index
                            for (mob in candidates) if (mob.navigation.shouldRecomputePath(pos)) matches++
                        }
                        FeatureBench.sink = matches
                    }
                }
            } finally {
                mobs.forEach(index::remove)
            }
        }
    }

    fun tracking(level: ServerLevel, measurements: Measurements, count: Int) {
        val index = NearbyTrackers()
        val players = List(8) { i ->
            ServerPlayer(level.server, level, GameProfile(UUID(0, i + 1L), "Bench$i"), ClientInformation.createDefault())
                .apply { setPos(0.0, 100.0, 0.0) }
        }
        for (player in players) index.add(tracker(level, player), players)
        val mobs = List<Entity>(count) { i ->
            Pig(EntityTypes.PIG, level).apply { setPos((i % 64 - 32).toDouble(), 100.0, (i / 64 % 64 - 32).toDouble()) }
        }
        for (mob in mobs) index.add(tracker(level, mob), players)
        fun tick() {
            Vanadium.scheduler.begin(Stage.TRACKING)
            index.tick(players, level.chunkSource.chunkMap.distanceManager)
            Vanadium.scheduler.run(Stage.TRACKING)
        }
        repeat(202) { tick() }
        var frame = 0L
        measurements.compare("tracking-8-players-$count-mobs", { FeatureBench.parallelTrackingPreparation = it }) {
            val x = if (++frame and 1 == 0L) 0.0 else 0.25
            for (player in players) player.setPos(x, 100.0, 0.0)
            for (mob in mobs) mob.needsSync = true
            tick()
        }
    }

    private fun tracker(level: ServerLevel, entity: Entity): ChunkMap.TrackedEntity = with(level.chunkSource.chunkMap) {
        object : ChunkMap.TrackedEntity(entity, 64, net.minecraft.world.entity.UpdateInterval.periodic(3), true) {
            override fun updatePlayer(player: ServerPlayer) = Unit
            override fun removePlayer(player: ServerPlayer) = Unit
            override fun sendToTrackingPlayersAndSelf(packet: Packet<in ClientGamePacketListener>) = Unit
        }
    }
}
