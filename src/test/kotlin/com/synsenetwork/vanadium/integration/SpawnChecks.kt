package com.synsenetwork.vanadium.integration

import com.mojang.authlib.GameProfile
import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.SpawnPreparation
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LocalMobCapCalculator
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.PotentialCalculator
import net.minecraft.world.level.entity.EntityLookup
import java.util.UUID

internal object SpawnChecks {
    fun run(level: ServerLevel) {
        val map = level.chunkSource.chunkMap
        val chunk = level.getChunkAt(BlockPos(0, 100, 0))
        val entities = List<Entity>(2048) { i ->
            val mob = if (i % 2 == 0) Pig(EntityTypes.PIG, level) else Zombie(EntityTypes.ZOMBIE, level)
            mob.setPos((i % 16).toDouble(), 100.0, (i / 16 % 16).toDouble())
            if (i % 3 == 0) mob.setPersistenceRequired()
            mob
        }
        val chunks = NaturalSpawner.ChunkGetter { _, consumer -> consumer.accept(chunk) }
        fun caps() = LocalMobCapCalculator(map)
        check(level.allEntities is Collection<*>, "world entity view does not expose its size")
        for (size in intArrayOf(0, 256, 1023, 1024, 1025, 2048)) {
            val input = entities.subList(0, size)
            val expected = NaturalSpawner.createState(289, input, chunks, caps())
            val counted = SpawnPreparation.count(289, input, chunks, caps(), map)
            check(expected.mobCategoryCounts == counted.mobCategoryCounts, "sized count differs at $size")
            var iterated = false
            val singlePass = Iterable {
                check(!iterated, "unsized input iterated twice")
                iterated = true
                input.iterator()
            }
            val unsized = SpawnPreparation.count(289, singlePass, chunks, caps(), map)
            check(expected.mobCategoryCounts == unsized.mobCategoryCounts, "unsized count differs at $size")
        }
        val lookup = EntityLookup<Entity>()
        val view = lookup.allEntities as Collection<*>
        check(view.isEmpty(), "new entity view is not empty")
        lookup.add(entities.first())
        check(view.size == 1 && entities.first() in view, "entity view lost live membership")
        expectUnsupported("entity view permits clearing") { (view as MutableCollection<*>).clear() }
        val iterator = view.iterator() as MutableIterator<*>
        iterator.next()
        expectUnsupported("entity view permits iterator removal") { iterator.remove() }
        lookup.remove(entities.first())
        check(view.isEmpty(), "entity view retained a removed entity")

        val reference = NaturalSpawner.createState(289, entities, chunks, caps())
        val parallel = SpawnPreparation.count(289, entities, chunks, caps(), map)
        check(reference.mobCategoryCounts == parallel.mobCategoryCounts, "parallel spawn counts differ")
        check(reference.spawnableChunkCount == parallel.spawnableChunkCount, "spawnable chunk count changed")
        check(reference.spawnPotential.getPotentialEnergyChange(BlockPos(0, 100, 0), 1.0) ==
                parallel.spawnPotential.getPotentialEnergyChange(BlockPos(0, 100, 0), 1.0), "spawn density changed")
        val previous = Vanadium.config.parallelSpawning
        try {
            Vanadium.config.parallelSpawning = false
            val serial = SpawnPreparation.count(289, entities, chunks, caps(), map)
            check(reference.mobCategoryCounts == serial.mobCategoryCounts, "spawn fallback changed counts")
        } finally {
            Vanadium.config.parallelSpawning = previous
        }

        val player = ServerPlayer(level.server, level.server.overworld(), GameProfile(UUID.randomUUID(), "SpawnCaps"),
            ClientInformation.createDefault())
        val combined = NaturalSpawner.createState(289, listOf(), chunks, caps())
        val density = PotentialCalculator()
        for (i in 0 until 4) {
            val partCaps = caps()
            partCaps.playersNearChunk.put(ChunkPos.pack(0, 0), listOf(player))
            repeat(20) { partCaps.addMob(ChunkPos(0, 0), MobCategory.MONSTER) }
            val part = NaturalSpawner.createState(289, listOf(), chunks, partCaps)
            part.mobCategoryCounts.put(MobCategory.MONSTER, 20)
            val pos = BlockPos(i + 1, 100, 0)
            part.spawnPotential.addCharge(pos, i + 0.25)
            density.addCharge(pos, i + 0.25)
            SpawnPreparation.merge(combined, part)
        }
        check(combined.mobCategoryCounts.getInt(MobCategory.MONSTER) == 80, "reduction lost category counts")
        check(!combined.localMobCapCalculator.canSpawn(MobCategory.MONSTER, ChunkPos(0, 0)), "reduction lost local player caps")
        check(combined.spawnPotential.getPotentialEnergyChange(BlockPos.ZERO, 1.0) ==
                density.getPotentialEnergyChange(BlockPos.ZERO, 1.0), "reduction changed charge values/order")
        println("SPAWN_PREPARATION_CHECKS_PASSED entities=2048 counts=${parallel.mobCategoryCounts}")
    }

    private fun expectUnsupported(message: String, action: () -> Unit) {
        try {
            action()
        } catch (_: UnsupportedOperationException) {
            return // The read-only contract holds.
        }
        throw AssertionError(message)
    }
}
