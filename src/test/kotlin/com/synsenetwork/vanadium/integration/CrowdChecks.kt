package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.entity.CrowdSlotAccess
import com.synsenetwork.vanadium.entity.CrowdedSection
import com.synsenetwork.vanadium.entity.CrowdedSections
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Continuation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.entity.EntitySectionStorage
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.level.entity.PersistentEntitySectionManager
import net.minecraft.world.phys.AABB
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Crowded-section grids against the real server: in a pen of wandering animals (one section, indexed), every
 * box query between waves returns exactly what the same query returns with the index off, in the same order,
 * and the set brute force over all entities gives; and a packed crowd pushes apart as far with the index as
 * without it.
 */
internal object CrowdChecks {
    private const val CHUNK_X = -40
    private const val CHUNK_Z = 40
    private const val FLOOR_Y = 200 // Section y 12 (192..207): the pen and everything in it stay in one section.
    private const val PUSH_TICKS = 40
    private const val EARLY_TICKS = 4

    /** A pig that stands still unless something pushes it. */
    private class StillPig(level: ServerLevel) : Pig(EntityTypes.PIG, level) {
        override fun registerGoals() = Unit
    }

    fun schedule(level: ServerLevel, checks: TickedChecks) {
        val config = { Vanadium.config }
        val pens = listOf(CHUNK_X to CHUNK_Z, CHUNK_X + 2 to CHUNK_Z)
        val crowd = ArrayList<Mob>()
        var rounds = 0
        var waited = 0
        var spreadOn = 0.0
        val earlyOn = DoubleArray(EARLY_TICKS + 1)
        val earlyOff = DoubleArray(EARLY_TICKS + 1)
        var indexedDuringPush = false
        var cramming = 0

        checks.then("force the crowd chunks and build pens") {
            for ((x, z) in pens) {
                check(level.setChunkForced(x, z, true)) { "could not force crowd chunk $x,$z" }
                level.getChunk(x, z)
                pen(level, x, z)
            }
        }
        checks.await("crowd chunks to tick entities", 100) { pens.all { (x, z) -> level.isPositionEntityTicking(center(x, z)) } }
        checks.then("spawn a wandering crowd") {
            val random = Random(3)
            val (x, z) = pens[0]
            repeat(48) { i ->
                val mob = (if (i % 2 == 0) EntityTypes.SHEEP else EntityTypes.PIG).create(level, EntitySpawnReason.COMMAND)!!
                val c = center(x, z)
                mob.setPos(c.x + 0.5 + random.nextDouble(-5.0, 5.0), FLOOR_Y + 1.0, c.z + 0.5 + random.nextDouble(-5.0, 5.0))
                mob.setPersistenceRequired()
                check(level.addFreshEntity(mob)) { "could not add $mob" }
                crowd.add(mob)
            }
        }
        checks.await("the crowded section to get a grid", 5) { grid(level, crowd[0]) != null }
        checks.until("queries in a moving crowd match the full scan and brute force") {
            if (++waited < 3) return@until false // Let them walk between rounds.
            waited = 0
            checkQueries(level, crowd, Random(rounds))
            ++rounds >= 6
        }
        checks.then("spawn a packed crowd with the index on") {
            crowd.forEach { it.discard() }
            crowd.clear()
            // Packed above the threshold, the crowd would also take cramming damage; only pushing is compared.
            cramming = level.gameRules.get(GameRules.MAX_ENTITY_CRAMMING)
            level.gameRules.set(GameRules.MAX_ENTITY_CRAMMING, 0, level.server)
            spawnPacked(level, pens[0], crowd)
            waited = 0
        }
        checks.until("the indexed crowd pushes apart") {
            if (grid(level, crowd[0]) != null) indexedDuringPush = true
            if (++waited <= EARLY_TICKS) earlyOn[waited] = spread(crowd)
            if (waited < PUSH_TICKS) return@until false
            check(indexedDuringPush) { "the packed crowd's section never got a grid" }
            spreadOn = spread(crowd)
            crowd.forEach { it.discard() }
            crowd.clear()
            config().crowdedSectionIndex = false
            true
        }
        checks.then("spawn the same crowd with the index off") {
            spawnPacked(level, pens[1], crowd)
            waited = 0
        }
        checks.until("the unindexed crowd pushes apart as far") {
            check(grid(level, crowd[0]) == null) { "a grid was built with the index off" }
            if (++waited <= EARLY_TICKS) earlyOff[waited] = spread(crowd)
            if (waited < PUSH_TICKS) return@until false
            val spreadOff = spread(crowd)
            config().crowdedSectionIndex = true
            level.gameRules.set(GameRules.MAX_ENTITY_CRAMMING, cramming, level.server)
            check(spreadOff > 0.8 && spreadOn > 0.8) { "the crowds did not push apart: on=$spreadOn off=$spreadOff" }
            // Tick by tick while the crowd bursts apart (the grid exists from the second tick: it is built after the first),
            // before the pen's walls bound the spread, and at the end.
            for (tick in 2..EARLY_TICKS) {
                val on = earlyOn[tick]
                val off = earlyOff[tick]
                check(on > off * 0.75 && on < off * 1.33) { "pushing differs with the index at tick $tick: on=$on off=$off" }
            }
            check(spreadOn > spreadOff * 0.75 && spreadOn < spreadOff * 1.33) { "pushing differs with the index: on=$spreadOn off=$spreadOff" }
            fun row(early: DoubleArray, last: Double) = (early.drop(1) + last).joinToString("/") { "%.2f".format(it) }
            println("CROWD_PUSH spread after ticks 1..$EARLY_TICKS/$PUSH_TICKS: index on=${row(earlyOn, spreadOn)} off=${row(earlyOff, spreadOff)}")
            crowd.forEach { it.discard() }
            crowd.clear()
            for ((x, z) in pens) level.setChunkForced(x, z, false)
            true
        }
    }

    private fun center(chunkX: Int, chunkZ: Int) = BlockPos(chunkX * 16 + 8, FLOOR_Y + 1, chunkZ * 16 + 8)

    /** A 12×12 stone floor walled with barriers, inside one entity section. */
    private fun pen(level: ServerLevel, chunkX: Int, chunkZ: Int) {
        val x0 = chunkX * 16 + 1
        val z0 = chunkZ * 16 + 1
        for (dx in 0..13) for (dz in 0..13) {
            level.setBlockAndUpdate(BlockPos(x0 + dx, FLOOR_Y, z0 + dz), Blocks.STONE.defaultBlockState())
            val wall = dx == 0 || dz == 0 || dx == 13 || dz == 13
            for (dy in 1..2) {
                level.setBlockAndUpdate(BlockPos(x0 + dx, FLOOR_Y + dy, z0 + dz), if (wall) Blocks.BARRIER.defaultBlockState() else Blocks.AIR.defaultBlockState())
            }
        }
    }

    /** Pigs (a few more than an indexed section needs) within 0.3 blocks of the pen's centre, at fixed offsets. */
    private fun spawnPacked(level: ServerLevel, pen: Pair<Int, Int>, into: MutableList<Mob>) {
        val c = center(pen.first, pen.second)
        val count = CrowdedSections.THRESHOLD + 6
        repeat(count) { i ->
            val pig = StillPig(level)
            val angle = i * 2.399963 // Golden angle: distinct directions.
            val r = 0.05 + 0.25 * i / count
            pig.setPos(c.x + 0.5 + r * Math.cos(angle), FLOOR_Y + 1.0, c.z + 0.5 + r * Math.sin(angle))
            pig.setPersistenceRequired()
            check(level.addFreshEntity(pig)) { "could not add $pig" }
            into.add(pig)
        }
    }

    /** Mean horizontal distance from the crowd's centroid. */
    private fun spread(crowd: List<Mob>): Double {
        val cx = crowd.sumOf { it.x } / crowd.size
        val cz = crowd.sumOf { it.z } / crowd.size
        return crowd.sumOf { sqrt((it.x - cx) * (it.x - cx) + (it.z - cz) * (it.z - cz)) } / crowd.size
    }

    @Suppress("UNCHECKED_CAST")
    private fun storage(level: ServerLevel): EntitySectionStorage<Entity> {
        val manager = ServerLevel::class.java.getDeclaredField("entityManager").apply { isAccessible = true }.get(level)
        return PersistentEntitySectionManager::class.java.getDeclaredField("sectionStorage").apply { isAccessible = true }
            .get(manager) as EntitySectionStorage<Entity>
    }

    @Suppress("UNCHECKED_CAST")
    private fun grid(level: ServerLevel, entity: Entity): CrowdedSection<Entity>? {
        val section = storage(level).getSection(SectionPos.asLong(entity.blockPosition())) ?: return null
        return (section as CrowdSlotAccess<Entity>).`vanadium$crowdSlot`()?.grid
    }

    /** Between waves: nothing moves while both paths and brute force answer. */
    private fun checkQueries(level: ServerLevel, crowd: List<Mob>, random: Random) {
        check(crowd.size >= CrowdedSections.THRESHOLD)
        val grid = grid(level, crowd[0]) ?: error("the crowded section lost its grid")
        val boxes = ArrayList<AABB>()
        for (mob in crowd) boxes.add(mob.boundingBox)
        val c = center(CHUNK_X, CHUNK_Z)
        repeat(150) {
            val x = c.x + random.nextDouble(-9.0, 9.0)
            val z = c.z + random.nextDouble(-9.0, 9.0)
            val r = random.nextDouble(0.0, 3.0)
            boxes.add(AABB(x - r, FLOOR_Y + random.nextDouble(0.0, 2.0), z - r, x + r, FLOOR_Y + 1.0 + random.nextDouble(0.0, 3.0), z + r))
        }
        var validated = 0
        for (box in boxes) {
            val on = level.getEntities(null as Entity?, box) { true }
            val typedOn = level.getEntities(EntityTypeTest.forClass(Sheep::class.java), box) { true }
            val classOn = level.getEntitiesOfClass(Animal::class.java, box)
            if (grid.query(box) { Continuation.CONTINUE } != null) validated++
            Vanadium.config.crowdedSectionIndex = false
            try {
                val off = level.getEntities(null as Entity?, box) { true }
                check(on == off) { "box $box: ${on.size} via the grid, ${off.size} via the scan (or another order)" }
                check(typedOn == level.getEntities(EntityTypeTest.forClass(Sheep::class.java), box) { true }) { "typed query $box differs" }
                check(classOn == level.getEntitiesOfClass(Animal::class.java, box)) { "class query $box differs" }
            } finally {
                Vanadium.config.crowdedSectionIndex = true
            }
            val brute = level.allEntities.filter { it.boundingBox.intersects(box) }.toSet()
            check(on.size == on.toSet().size) { "box $box returned an entity twice" }
            check(on.toSet() == brute) { "box $box: ${on.size} entities, brute force ${brute.size}" }
        }
        for (mob in crowd) {
            val pushOn = level.getPushableEntities(mob, mob.boundingBox)
            Vanadium.config.crowdedSectionIndex = false
            try {
                check(pushOn == level.getPushableEntities(mob, mob.boundingBox)) { "pushable entities differ for $mob" }
            } finally {
                Vanadium.config.crowdedSectionIndex = true
            }
        }
        // Push boxes stay small: between waves no write is open, so the grid answers every one of them itself.
        check(validated >= crowd.size) { "the grid answered only $validated of ${boxes.size} queries" }
    }
}
