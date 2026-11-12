package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.concurrent.Interactions
import com.synsenetwork.vanadium.tick.EntityTickAccess
import com.synsenetwork.vanadium.tick.PersistentTickTasks
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.levelgen.Heightmap
import java.util.Collections

/**
 * Persistent entity tick tasks against real ticks: entities spawned during the wave start next tick and
 * tick once per tick, removed ones stop, serial-rule changes (config edits, reload, mounting) move an entity
 * between the wave and the serial path, the disabled path and re-enabling work, and an entity whose chunk
 * stops ticking leaves the wave.
 */
internal object EntityCollectionChecks {
    /** Records the server tick and whether it ran inside the entity wave, for every tick it gets. */
    private class Probe(level: ServerLevel, val onTick: (Probe) -> Unit = {}) : Pig(EntityTypes.PIG, level) {
        val ticks: MutableList<Pair<Int, Boolean>> = Collections.synchronizedList(ArrayList())

        override fun tick() {
            ticks.add(level().server!!.tickCount to Interactions.active)
            onTick(this)
            super.tick()
        }

        /** How this probe ticked during [tick]: one entry per tick it got, true for the wave. */
        fun at(tick: Int): List<Boolean> = synchronized(ticks) { ticks.filter { it.first == tick }.map { it.second } }
    }

    private const val CHUNK_X = 48
    private const val CHUNK_Z = -48

    fun schedule(level: ServerLevel, checks: TickedChecks) {
        val server = level.server
        val tasks: PersistentTickTasks<Entity> = (level as EntityTickAccess).`vanadium$entityTicks`().tasks
        val center = BlockPos(CHUNK_X * 16 + 8, 0, CHUNK_Z * 16 + 8)
        val config = { Vanadium.config }
        var spawner: Probe? = null
        var child: Probe? = null
        var childSpawnedAt = -1
        var childSpawnedInWave = false
        lateinit var plain: Probe
        lateinit var stand: ArmorStand
        var tick = 0

        fun spawn(entity: Entity, dx: Int) {
            val x = center.x + dx
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, center.z)
            entity.setPos(x + 0.5, y.toDouble(), center.z + 0.5)
            check(level.addFreshEntity(entity)) { "could not add $entity" }
        }

        fun ticked(probe: Probe, inWave: Boolean, what: String) {
            val seen = probe.at(server.tickCount)
            check(seen == listOf(inWave)) { "$what: expected one ${if (inWave) "wave" else "serial"} tick, got $seen" }
        }

        checks.then("force the fixture chunk") {
            check(level.setChunkForced(CHUNK_X, CHUNK_Z, true)) { "could not force the fixture chunk" }
            level.getChunk(CHUNK_X, CHUNK_Z)
        }
        checks.await("fixture chunk to tick entities", 100) { level.isPositionEntityTicking(center) }
        checks.then("spawn probes") {
            spawner = Probe(level) { self ->
                // Spawn from inside the wave, on whichever thread runs this tick.
                if (child == null && self.ticks.size >= 2) {
                    val spawned = Probe(level)
                    childSpawnedAt = server.tickCount
                    childSpawnedInWave = Interactions.active
                    spawned.setPos(self.x + 2, self.y, self.z)
                    check(level.addFreshEntity(spawned)) { "could not spawn from the wave" }
                    child = spawned
                }
            }.also { spawn(it, 0) }
            plain = Probe(level).also { spawn(it, 3) }
            stand = ArmorStand(EntityTypes.ARMOR_STAND, level).also { spawn(it, 5) }
            tick = server.tickCount
        }
        checks.until("an entity added between ticks ticks once next tick, in the wave") {
            check(server.tickCount == tick + 1)
            ticked(spawner!!, true, "spawner")
            ticked(plain, true, "plain")
            check(tasks.isSerial(plain) == false && tasks.isSerial(stand) == false) { "probes are not wave tasks" }
            true
        }
        checks.await("the spawner to spawn from the wave", 10) { child != null }
        checks.then("an entity spawned during the wave did not tick that tick") {
            check(childSpawnedInWave) { "the spawn did not happen inside the wave" }
            check(childSpawnedAt == server.tickCount - 1) { "spawn tick bookkeeping is off" }
            val spawned = child!!
            check(spawned.ticks.all { it.first > childSpawnedAt }) { "the child ticked during its spawn tick" }
            ticked(spawned, true, "child, first tick after spawning")
        }
        checks.then("the child ticks exactly once per tick") {
            val spawned = child!!
            ticked(spawned, true, "child, second tick")
            ticked(spawner!!, true, "spawner")
            spawned.discard()
        }
        checks.then("a removed entity stops ticking and loses its task") {
            val removed = child!!
            check(removed.at(server.tickCount).isEmpty()) { "removed child still ticked" }
            check(tasks.isSerial(removed) == null) { "removed child kept its task" }
            config().serialEntityTypes = mutableListOf("minecraft:pig") // Applies from the next tick's start.
        }
        checks.then("a serial type rule moves pigs to the serial path") {
            ticked(plain, false, "plain under a pig rule")
            ticked(spawner!!, false, "spawner under a pig rule")
            check(tasks.isSerial(plain) == true)
            check(server.commands.dispatcher.execute("vanadium reload", server.createCommandSourceStack()) == 1) { "reload failed" }
            check(config().serialEntityTypes.isEmpty()) { "the live config file has serial entity rules" }
        }
        checks.then("reload restores the wave") {
            ticked(plain, true, "plain after reload")
            config().serialEntityTypes = mutableListOf("minecraft:armor_stand")
        }
        checks.then("an armor stand rule leaves pigs in the wave") {
            ticked(plain, true, "plain under an armor stand rule")
            check(tasks.isSerial(stand) == true) { "the armor stand is not serial" }
            check(stand.startRiding(plain, true, false)) { "could not mount the armor stand" }
        }
        checks.then("a serial passenger moves its vehicle to the serial path") {
            check(stand.vehicle === plain) { "the armor stand dismounted by itself" }
            ticked(plain, false, "plain carrying a serial passenger")
            stand.stopRiding()
        }
        checks.then("dismounting returns the vehicle to the wave") {
            ticked(plain, true, "plain after the dismount")
            config().serialEntityTypes = mutableListOf()
            config().parallelEntities = false
        }
        checks.then("the disabled path ticks every entity once, outside the wave") {
            ticked(plain, false, "plain with parallel entities off")
            check(tasks.memberCount == 0) { "the disabled path kept tasks" }
            config().parallelEntities = true
        }
        checks.then("re-enabling rescans the tick list") {
            ticked(plain, true, "plain after re-enabling")
            check(tasks.isSerial(spawner!!) == false) { "spawner lost after the rescan" }
            check(level.setChunkForced(CHUNK_X, CHUNK_Z, false)) { "could not unforce the fixture chunk" }
        }
        checks.await("entities in a chunk that stops ticking to leave the wave", 100) {
            if (level.isPositionEntityTicking(center)) return@await false
            // The tick list drops them when their section stops ticking; the next dispatch drops their tasks.
            tasks.isSerial(spawner!!) == null && tasks.isSerial(plain) == null
        }
        checks.then("entities outside ticking chunks do not tick") {
            val first = spawner!!
            check(first.at(server.tickCount).isEmpty() && plain.at(server.tickCount).isEmpty()) { "entity in a stopped chunk ticked" }
            for (entity in listOf(first, plain, stand)) entity.discard()
        }
    }
}
