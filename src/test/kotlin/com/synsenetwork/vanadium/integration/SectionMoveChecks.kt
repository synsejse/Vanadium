package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.concurrent.Interactions
import com.synsenetwork.vanadium.tick.WorkerPool
import net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementListener
import net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker
import net.caffeinemc.mods.lithium.common.tracking.entity.SectionedItemEntityMovementTracker
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.entity.EntitySection
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * Entity section membership while entities move in the flat wave: moves within a section take no lock, crossings
 * do, and Lithium's movement trackers (hoppers) still hear every section's moves.
 */
internal object SectionMoveChecks {
    /** Section (0, 7, 0) and the one above it: blocks y 112–127 and 128–143 of chunk (0, 0). */
    private val lower = AABB(0.0, 112.0, 0.0, 16.0, 128.0, 16.0)
    private val upper = AABB(0.0, 128.0, 0.0, 16.0, 144.0, 16.0)

    fun run(level: ServerLevel) {
        val unprotected = Interactions.timeouts + Interactions.cycles
        WorkerPool(3).use { pool ->
            repeat(4) { checkCrossingsDuringAWave(level, pool) }
            checkMovementTrackersHearConcurrentMoves(level, pool)
        }
        check(Interactions.timeouts + Interactions.cycles == unprotected) { "a section move ran unprotected" }
        println("SECTION_MOVE_CHECKS_PASSED")
    }

    private fun wave(pool: WorkerPool, ticks: List<Pair<Entity, (Entity) -> Unit>>) {
        val tasks = ticks.map { (entity, body) -> Runnable { Interactions.tick(entity, Consumer(body)) } }
        Interactions.runWave { pool.runWave(tasks) }
    }

    private fun zombies(level: ServerLevel, box: AABB) = level.getEntities(EntityTypeTest.forClass(Zombie::class.java), box) { true }

    /**
     * Zombies in one section move inside it, cross into the section above and move inside that, all at once.
     * Each finds itself through an entity query of its new section within its own tick, and afterwards every
     * query sees them all in the new section and none in the old.
     */
    private fun checkCrossingsDuringAWave(level: ServerLevel, pool: WorkerPool) {
        val mobs = List(48) { i ->
            EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND)!!.apply {
                setPos(1.5 + i % 12, 120.0, 1.5 + i / 12)
                setNoGravity(true)
                setNoAi(true)
                check(level.addFreshEntity(this)) { "zombie was not added" }
            }
        }
        try {
            check(zombies(level, lower).containsAll(mobs)) { "zombies were not indexed in their section" }
            val start = CyclicBarrier(4)
            val misses = AtomicInteger()
            wave(pool, mobs.mapIndexed { i, mob ->
                mob to { _: Entity ->
                    if (i < 4) start.await(10, TimeUnit.SECONDS)
                    repeat(8) { mob.setPos(mob.x, 120.0 + it * 0.5, mob.z) } // Same section: the lock-free path.
                    mob.setPos(mob.x, 130.0, mob.z)
                    if (mob !in zombies(level, upper)) misses.incrementAndGet()
                    repeat(8) { mob.setPos(mob.x, 130.0 + it * 0.5, mob.z) }
                }
            })
            check(misses.get() == 0) { "${misses.get()} zombies were not found in their new section during the wave" }
            val above = zombies(level, upper)
            check(above.containsAll(mobs)) { "${mobs.count { it !in above }} zombies missing from their new section" }
            check(zombies(level, lower).none { it in mobs }) { "zombies stayed in their old section" }
        } finally {
            mobs.forEach(Entity::discard)
        }
        check(zombies(level, lower).none { it in mobs } && zombies(level, upper).none { it in mobs }) { "removed zombies stayed indexed" }
    }

    /**
     * Lithium's hopper trackers wait for one move in a watched section. Items in that section move at the same
     * time on several threads; every round the tracker hears the moves and wakes its listener once.
     */
    private fun checkMovementTrackersHearConcurrentMoves(level: ServerLevel, pool: WorkerPool) {
        val area = AABB(4.0, 120.0, 4.0, 12.0, 124.0, 12.0) // Watches section (0, 7, 0) only.
        val tracker = SectionedItemEntityMovementTracker.registerAt(level, area, ItemEntity::class.java)
        // The wraps are optional (Lithium's option may be off); here it is on, so they must have applied.
        val sections = SectionedEntityMovementTracker::class.java.getDeclaredField("sortedSections").apply { isAccessible = true }
            .get(tracker) as List<*>
        val tracked = EntitySection::class.java.declaredFields.single { it.name.endsWith("tracked") && it.type == Boolean::class.javaPrimitiveType }
            .apply { isAccessible = true }
        check(sections.size == 1 && tracked.getBoolean(sections[0])) { "movement-tracking lock was not applied to the watched section" }
        check(SectionedEntityMovementTracker::class.java.getDeclaredMethods().any { it.name.contains("vanadium\$") }) {
            "tracker registration is not wrapped"
        }
        val wakes = AtomicInteger()
        val listener = SectionedEntityMovementListener { wakes.incrementAndGet() }
        val items = List(32) { i ->
            ItemEntity(level, 2.5 + i % 8, 118.0, 2.5 + i / 8, ItemStack(Items.STONE)).apply {
                setNoGravity(true)
                check(level.addFreshEntity(this)) { "item was not added" }
            }
        }
        try {
            repeat(16) { round ->
                // Re-listens to the sections the last round's moves were heard in; a tracker lost from them stays deaf.
                tracker.listenToEntityMovementOnce(listener)
                val before = wakes.get()
                val start = CyclicBarrier(4)
                wave(pool, items.mapIndexed { i, item ->
                    item to { _: Entity ->
                        if (i < 4) start.await(10, TimeUnit.SECONDS)
                        repeat(8) { item.setPos(item.x, 118.0 + (round + it) % 4 * 0.25, item.z) }
                    }
                })
                check(wakes.get() == before + 1) { "round $round woke the tracker's listener ${wakes.get() - before} times" }
            }
        } finally {
            items.forEach(Entity::discard)
            tracker.unRegister(level)
        }
    }
}
