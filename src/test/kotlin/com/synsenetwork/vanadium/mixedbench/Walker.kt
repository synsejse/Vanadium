package com.synsenetwork.vanadium.mixedbench

import com.synsenetwork.vanadium.live.TestPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A simulated client walking a fixed route, one move packet per tick. Builders circle their base at
 * walking speed; explorers sprint out and back along a straight line, so chunks keep unloading behind
 * them and reloading from disk. Each route is a stone road one block above the highest terrain, tree or
 * water along it, so the client's level straight-line moves pass vanilla movement validation without
 * simulating jumps or falls, and nothing natural is cut. A walker waits while the chunk ahead is not loaded.
 */
internal class Walker(private val level: ServerLevel, val base: MixedBase, val explorer: Boolean, private val lane: Int) {
    private var distance = 0.0
    private var y = 0.0
    private val speed = if (explorer) 0.2806 else 0.2158
    private val radius = 24.0 + 4.0 * lane
    private val direction = if (base.index % 2 == 0) -1 else 1

    lateinit var client: TestPlayer
        private set

    /** Ticks the walker stood still because the chunk ahead was not loaded yet. */
    var stalls = 0L
        private set

    /** Server position corrections (teleports) the client acknowledged, e.g. after a rejected move. */
    var corrections = 0L
        private set

    private fun position(distance: Double): Pair<Double, Double> {
        val x = base.centre.x + 0.5
        val z = base.centre.z + 0.5
        return if (explorer) {
            // Even bases explore north, odd bases south; lanes keep several explorers of one base apart.
            val along = (distance % (2 * ROUTE)).let { if (it <= ROUTE) it else 2 * ROUTE - it }
            x + 6.0 * lane to z + direction * (START + along)
        } else {
            // The ring clears the 32×32 platform's corners (21.9 blocks from the centre).
            val angle = distance / radius + lane * PI / 3
            x + radius * cos(angle) to z + radius * sin(angle)
        }
    }

    /** Block columns of the route: a band three blocks wide around the walking line. */
    private fun columns(): List<Pair<Int, Int>> {
        val cx = base.centre.x
        val cz = base.centre.z
        val columns = ArrayList<Pair<Int, Int>>()
        if (explorer) {
            val x0 = cx + 6 * lane
            for (along in START.toInt() - 2..(START + ROUTE).toInt() + 2) for (x in x0 - 1..x0 + 1) columns.add(x to cz + direction * along)
        } else {
            val reach = radius.toInt() + 2
            for (dx in -reach..reach) for (dz in -reach..reach) {
                if (hypot(dx.toDouble(), dz.toDouble()) in radius - 1.5..radius + 1.5) columns.add(cx + dx to cz + dz)
            }
        }
        return columns
    }

    /** Chunks the route crosses, so setup can generate them before paving and measuring. */
    fun routeChunks(): Set<Long> = columns().mapTo(HashSet()) { (x, z) -> ChunkPos.pack(x shr 4, z shr 4) }

    /** Lays the road on the first free height above every motion-blocking block (leaves, fluids) along the route. */
    fun pave() {
        val columns = columns()
        val floor = maxOf(level.seaLevel, columns.maxOf { (x, z) -> MixedBase.surface(level, x, z) })
        for ((x, z) in columns) level.setBlock(BlockPos(x, floor, z), Blocks.STONE.defaultBlockState(), QUIET)
        y = floor + 1.0
    }

    fun join(name: String) {
        val (x, z) = position(0.0)
        client = TestPlayer(level, name, Vec3(x, y, z))
    }

    /** One client tick: count corrections from the last tick's packets, drop them, and send the next step. */
    fun step(moving: Boolean) {
        for (packet in client.packets) if (packet is ClientboundPlayerPositionPacket) corrections++
        client.packets.clear()
        if (!moving || !client.player.connection.hasClientLoaded()) return
        val next = distance + speed
        val (x, z) = position(next)
        if (level.chunkSource.getChunkNow(floor(x).toInt() shr 4, floor(z).toInt() shr 4) == null) {
            stalls++
            return
        }
        val (px, pz) = position(distance)
        val yaw = (atan2(z - pz, x - px) * 180.0 / PI).toFloat() - 90f
        distance = next
        client.send(ServerboundMovePlayerPacket.PosRot(x, y, z, yaw, 0f, true, false))
    }

    companion object {
        /** Explorers start this far from the base centre, outside the builders' ring, and go [ROUTE] blocks further. */
        const val START = 30.0
        const val ROUTE = 192.0

        /** Client updates and known shapes only: a road or platform block replacing a plant must not pop its neighbours. */
        const val QUIET = net.minecraft.world.level.block.Block.UPDATE_CLIENTS or net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE
    }
}
