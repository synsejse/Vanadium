package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.tick.SpawnPreparation
import net.caffeinemc.mods.lithium.common.entity.PositionedEntityTrackingSection
import net.caffeinemc.mods.lithium.common.world.ChunkAwareEntityIterable
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LocalMobCapCalculator
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.entity.EntitySectionStorage
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.level.entity.PersistentEntitySectionManager
import net.minecraft.world.phys.AABB
import java.util.concurrent.TimeUnit

/**
 * The live section storage is served by Vanadium's index: queries across chunk and section boundaries on
 * both sides of zero, Lithium's per-section position, and Lithium's chunk-aware spawn counting, which
 * would otherwise iterate vanilla's (empty) section map.
 */
internal object EntitySectionChecks {
    private val CHUNKS = listOf(-1 to -1, -1 to 0, 0 to -1, 0 to 0)

    fun run(level: ServerLevel) {
        val zombies = ArrayList<Zombie>()
        try {
            for ((x, z) in CHUNKS) {
                level.setChunkForced(x, z, true)
                level.getChunk(x, z)
            }
            // Straddle X/Z zero and a section boundary in Y (112).
            for (x in doubleArrayOf(-7.5, -0.5, 0.5, 9.5)) for (z in doubleArrayOf(-9.5, -0.5, 0.5, 6.5)) for (y in doubleArrayOf(110.0, 114.0)) {
                val zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND)!!
                zombie.setPos(x, y, z)
                zombie.setNoGravity(true)
                zombie.setNoAi(true)
                check(level.addFreshEntity(zombie), "zombie was not added")
                zombies.add(zombie)
            }
            check(zombies.none { it.isPersistenceRequired }, "fixture zombies must count toward spawning")
            // Full chunks make their sections accessible (tracked or ticking) once the status change lands.
            val storage = storage(level)
            fun ready() = zombies.all { storage.getSection(SectionPos.asLong(it.blockPosition()))?.status?.isAccessible == true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            level.chunkSource.mainThreadProcessor.managedBlock { System.nanoTime() >= deadline || ready() }
            check(ready(), "fixture sections did not become accessible")
            checkStorage(level, zombies)
            checkQueries(level, zombies)
            checkSpawnCounts(level, zombies)
        } finally {
            zombies.forEach { if (!it.isRemoved) it.discard() }
            for ((x, z) in CHUNKS) level.setChunkForced(x, z, false)
        }
        println("ENTITY_SECTION_CHECKS_PASSED")
    }

    @Suppress("UNCHECKED_CAST")
    private fun storage(level: ServerLevel): EntitySectionStorage<Entity> {
        val manager = ServerLevel::class.java.getDeclaredField("entityManager").apply { isAccessible = true }.get(level)
        return PersistentEntitySectionManager::class.java.getDeclaredField("sectionStorage").apply { isAccessible = true }
            .get(manager) as EntitySectionStorage<Entity>
    }

    private fun checkStorage(level: ServerLevel, zombies: List<Zombie>) {
        val storage = storage(level)
        val sections = EntitySectionStorage::class.java.getDeclaredField("sections").apply { isAccessible = true }.get(storage) as Map<*, *>
        check(sections.isEmpty(), "vanilla section map is in use")
        val ids = EntitySectionStorage::class.java.getDeclaredField("sectionIds").apply { isAccessible = true }.get(storage) as Collection<*>
        check(ids.isEmpty(), "vanilla sorted section keys are in use")
        for (zombie in zombies) {
            val key = SectionPos.asLong(zombie.blockPosition())
            val section = storage.getSection(key)
            check(section != null && section.entities.anyMatch { it === zombie }, "zombie missing from section ${SectionPos.of(key)}")
            check(section!!.status.isAccessible, "fixture section ${SectionPos.of(key)} is not accessible")
            check(storage.getOrCreateSection(key) === section, "getOrCreateSection replaced an existing section")
            if (section is PositionedEntityTrackingSection) {
                check(section.`lithium$getPos`() == key, "Lithium section position ${section.`lithium$getPos`()} != $key")
            }
            val chunk = ChunkPos.pack(SectionPos.x(key), SectionPos.z(key))
            check(storage.getExistingSectionsInChunk(chunk).anyMatch { it === section }, "chunk stream lost section ${SectionPos.of(key)}")
            check(storage.getExistingSectionPositionsInChunk(chunk).anyMatch { it == key }, "chunk keys lost ${SectionPos.of(key)}")
            check(chunk in storage.allChunksWithExistingSections, "chunk set lost ${ChunkPos.unpack(chunk)}")
        }
    }

    private fun checkQueries(level: ServerLevel, zombies: List<Zombie>) {
        for (box in listOf(AABB(-12.0, 100.0, -12.0, 12.0, 130.0, 12.0), AABB(-1.0, 113.0, -1.0, 1.0, 116.0, 1.0),
                AABB(-8.0, 109.0, -10.0, -7.0, 111.0, -9.0))) {
            val expected = level.allEntities.filter { it.boundingBox.intersects(box) }.toSet()
            val actual = level.getEntities(null as Entity?, box) { true }
            check(actual.size == actual.toSet().size, "box query $box visited an entity twice")
            check(actual.toSet() == expected, "box query $box: ${actual.size} entities, brute force ${expected.size}")
            val typed = level.getEntities(EntityTypeTest.forClass(Zombie::class.java), box) { true }.toSet()
            check(typed == zombies.filter { it.boundingBox.intersects(box) }.toSet(), "typed query $box differs")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkSpawnCounts(level: ServerLevel, zombies: List<Zombie>) {
        val storage = storage(level)
        if (storage is ChunkAwareEntityIterable<*>) {
            val iterated = (storage as ChunkAwareEntityIterable<Entity>).`lithium$IterateEntitiesInTrackedSections`().toList()
            check(iterated.size == iterated.toSet().size, "chunk-aware iteration repeated an entity")
            check(iterated.toSet() == level.allEntities.toSet(), "chunk-aware iteration (${iterated.size}) differs from visible entities")
        }
        val chunks = NaturalSpawner.ChunkGetter { key, consumer -> consumer.accept(level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) }
        val map = level.chunkSource.chunkMap
        // Through Lithium's redirect (when present) and Vanadium's count hook, against vanilla's visible entities.
        val world = NaturalSpawner.createState(289, level, chunks, LocalMobCapCalculator(map))
        val reference = SpawnPreparation.createState(289, level, level.allEntities.toList(), chunks, LocalMobCapCalculator(map))
        check(world.mobCategoryCounts == reference.mobCategoryCounts, "spawn counts ${world.mobCategoryCounts} != ${reference.mobCategoryCounts}")
        check(world.mobCategoryCounts.getInt(MobCategory.MONSTER) >= zombies.size, "spawn counting missed fixture zombies: ${world.mobCategoryCounts}")
    }
}
