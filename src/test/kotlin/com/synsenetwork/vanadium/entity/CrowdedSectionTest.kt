package com.synsenetwork.vanadium.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.AbortableIterationConsumer
import net.minecraft.util.Continuation
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntityInLevelCallback
import net.minecraft.world.level.entity.EntitySection
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.level.entity.Visibility
import net.minecraft.world.phys.AABB
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier
import java.util.stream.Stream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CrowdedSectionTest {
    /** An entity whose box writes follow `EntityMixin.vanadium$moveInCells`. */
    open class Fake(box: AABB) : EntityAccess, CrowdAccess {
        @JvmField var box: AABB = box

        @Volatile private var crowd: CrowdedSection.Member<*>? = null

        @Volatile @JvmField var gone = false

        /** Bumped after every removal and before every add, by the entity's writer. */
        @JvmField val changes = AtomicInteger()

        override fun `vanadium$crowd`(): CrowdedSection.Member<*>? = crowd
        override fun `vanadium$setCrowd`(member: CrowdedSection.Member<*>?) {
            crowd = member
        }

        @Suppress("UNCHECKED_CAST")
        fun move(next: AABB) {
            val member = crowd as CrowdedSection.Member<EntityAccess>?
            if (member == null) {
                box = next
                return
            }
            val grid = member.grid
            val range = grid.rangeOf(next)
            if (range == member.range || !grid.beginMove(member, range)) {
                box = next
                return
            }
            try {
                box = next
            } finally {
                grid.endMove(member)
            }
        }

        override fun getBoundingBox(): AABB = box
        override fun getId(): Int = 0
        override fun blockPosition(): BlockPos = BlockPos.containing(box.center)
        override fun setLevelCallback(callback: EntityInLevelCallback) = Unit
        override fun getSelfAndPassengers(): Stream<out EntityAccess> = Stream.of(this)
        override fun getPassengersAndSelf(): Stream<out EntityAccess> = Stream.of(this)
        override fun setRemoved(removalReason: Entity.RemovalReason) = Unit
        override fun shouldBeSaved(): Boolean = false
        override fun isAlwaysTicking(): Boolean = false
        override fun getUUID(): UUID = UUID(0, 0)
        override fun isRemoved(): Boolean = gone
    }

    class Other(box: AABB) : Fake(box)

    /** A section list plus its grid, updated as `EntitySectionMixin` does (copy-on-write list, as Vanadium's). */
    private class Section(key: Long) {
        val list = CopyOnWriteArrayList<EntityAccess>()
        val grid = CrowdedSection<EntityAccess>(key, 0L)

        fun add(entity: EntityAccess) = synchronized(grid) {
            list.add(entity)
            grid.addLocked(entity)
        }

        fun remove(entity: EntityAccess) = synchronized(grid) {
            list.remove(entity)
            grid.removeLocked(entity)
        }

        /** What the mixin does: the grid, or vanilla's scan when the read overlapped a write. */
        fun query(box: AABB, out: AbortableIterationConsumer<EntityAccess>): Boolean =
            grid.query(box, out)?.let { true } ?: run { scan(box, out); false }

        fun scan(box: AABB, out: AbortableIterationConsumer<EntityAccess>): Continuation {
            for (entity in list) if (entity.boundingBox.intersects(box) && out.accept(entity).shouldAbort()) return Continuation.ABORT
            return Continuation.CONTINUE
        }
    }

    private val key = SectionPos.asLong(-1, 3, 2) // Blocks x -16..-1, y 48..63, z 32..47: negative on X.

    @AfterTest
    fun resetEpoch() {
        CrowdedSection.unprotectedWrites = LongSupplier { 0L }
    }

    private fun randomBox(random: Random, spread: Double = 4.0): AABB {
        val x = random.nextDouble(-16.0 - spread, spread)
        val y = random.nextDouble(48.0 - spread, 64.0 + spread)
        val z = random.nextDouble(32.0 - spread, 48.0 + spread)
        val w = random.nextDouble(0.0, 1.6)
        return AABB(x - w / 2, y, z - w / 2, x + w / 2, y + random.nextDouble(0.0, 2.8), z + w / 2)
    }

    private fun collect(query: (AbortableIterationConsumer<EntityAccess>) -> Unit): List<EntityAccess> {
        val found = ArrayList<EntityAccess>()
        query { found.add(it); Continuation.CONTINUE }
        return found
    }

    @Test
    fun queriesMatchTheFullScanInListOrder() {
        val random = Random(11)
        val section = Section(key)
        val entities = List(120) { Fake(randomBox(random)) }
        entities.forEach(section::add)
        repeat(3000) { step ->
            val entity = entities[random.nextInt(entities.size)]
            when (random.nextInt(10)) {
                0 -> {
                    section.remove(entity)
                    section.add(entity) // Back at the end of the list, as a section change and return would put it.
                }
                else -> entity.move(if (random.nextInt(4) == 0) randomBox(random) else entity.box.move(
                    random.nextDouble(-0.6, 0.6), random.nextDouble(-0.3, 0.3), random.nextDouble(-0.6, 0.6)))
            }
            val query = when (step % 4) {
                0 -> randomBox(random, 6.0)
                1 -> entity.box // A push: the mover's own box.
                2 -> randomBox(random).inflate(random.nextDouble(0.0, 3.0))
                else -> AABB(-40.0, 0.0, 0.0, 40.0, 100.0, 80.0) // Covers everything: falls back.
            }
            val expected = collect { section.scan(query, it) }
            val viaGrid = ArrayList<EntityAccess>()
            val result = section.grid.query(query) { viaGrid.add(it); Continuation.CONTINUE }
            if (step % 4 != 3) assertNotNull(result, "single-threaded query fell back")
            if (result != null) assertEquals(expected, viaGrid, "query $query at step $step")
        }
    }

    @Test
    fun cellsFollowBoxesOutsideTheSection() {
        val section = Section(key)
        // Positions inside the section, boxes reaching up to two blocks past each face.
        val west = Fake(AABB(-17.9, 50.0, 40.0, -15.5, 51.0, 41.0))
        val east = Fake(AABB(-1.5, 50.0, 40.0, 1.9, 51.0, 41.0))
        val top = Fake(AABB(-8.0, 62.0, 40.0, -7.0, 65.5, 41.0))
        val bottom = Fake(AABB(-8.0, 46.0, 33.0, -7.0, 48.5, 34.0))
        listOf(west, east, top, bottom).forEach(section::add)
        fun found(box: AABB) = collect { section.grid.query(box, it)!! }
        assertEquals(listOf(west), found(AABB(-18.0, 50.0, 40.0, -17.0, 51.0, 41.0)))
        assertEquals(listOf(east), found(AABB(1.0, 50.5, 40.5, 3.0, 50.6, 40.6)))
        assertEquals(listOf(top), found(AABB(-8.0, 65.0, 40.0, -7.5, 70.0, 41.0)))
        assertEquals(listOf(bottom), found(AABB(-8.0, 40.0, 33.0, -7.5, 47.0, 33.5)))
        assertEquals(emptyList(), found(AABB(-18.0, 50.0, 42.0, -17.0, 51.0, 43.0)))
        val nan = AABB(Double.NaN, 50.0, 40.0, Double.NaN, 51.0, 41.0)
        assertEquals(emptyList(), found(nan))
    }

    @Test
    fun typedQueriesAndAbortMatchVanilla() {
        val random = Random(5)
        val section = Section(key)
        val entities = List(80) { if (it % 3 == 0) Other(randomBox(random)) else Fake(randomBox(random)) }
        entities.forEach(section::add)
        val type = EntityTypeTest.forClass<EntityAccess, Other>(Other::class.java)
        repeat(500) {
            val query = randomBox(random).inflate(random.nextDouble(0.0, 4.0))
            val expected: List<EntityAccess> = entities.filter { it is Other && it.boundingBox.intersects(query) }
            val found = ArrayList<EntityAccess>()
            val result = section.grid.query(type, query) { found.add(it); Continuation.CONTINUE }
            if (result != null) assertEquals<List<EntityAccess>>(expected, found)
            val all = collect { section.scan(query, it) }
            if (all.size >= 2) {
                val prefix = ArrayList<EntityAccess>()
                val aborted = section.grid.query(query) { prefix.add(it); if (prefix.size == 2) Continuation.ABORT else Continuation.CONTINUE }
                if (aborted != null) {
                    assertEquals(Continuation.ABORT, aborted)
                    assertEquals(all.subList(0, 2), prefix)
                }
            }
        }
    }

    @Test
    fun nestedQueriesKeepTheirOwnCandidates() {
        val section = Section(key)
        val entities = List(40) { Fake(AABB(-15.5 + it % 8 * 1.9, 50.0, 33.0 + it / 8 * 2.5, -15.0 + it % 8 * 1.9, 52.0, 34.2 + it / 8 * 2.5)) }
        entities.forEach(section::add)
        val wide = AABB(-16.0, 50.0, 32.0, -6.0, 52.0, 40.0)
        val expected = collect { section.scan(wide, it) }
        val outer = ArrayList<EntityAccess>()
        section.grid.query(wide) { entity ->
            outer.add(entity)
            assertEquals(collect { section.scan(entity.boundingBox, it) }, collect { section.grid.query(entity.boundingBox, it)!! })
            Continuation.CONTINUE
        }
        assertEquals(expected, outer)
    }

    @Test
    fun openWritesAndUnprotectedWritesFallBack() {
        val section = Section(key)
        val entity = Fake(AABB(-15.5, 50.0, 33.0, -15.0, 51.0, 33.5))
        section.add(entity)
        val before = AABB(-16.0, 49.0, 32.0, -14.0, 52.0, 34.0)
        val after = AABB(-4.0, 49.0, 44.0, -2.0, 52.0, 46.0)
        assertEquals(listOf<EntityAccess>(entity), collect { section.grid.query(before, it)!! })
        assertEquals(emptyList(), collect { section.grid.query(after, it)!! })
        @Suppress("UNCHECKED_CAST")
        val member = entity.`vanadium$crowd`() as CrowdedSection.Member<EntityAccess>
        val target = AABB(-3.5, 50.0, 45.0, -3.0, 51.0, 45.5)
        assertTrue(section.grid.beginMove(member, section.grid.rangeOf(target)))
        assertNull(section.grid.query(before) { Continuation.CONTINUE }, "a read during an open move validated")
        assertNull(section.grid.query(after) { Continuation.CONTINUE }, "a read during an open move validated")
        entity.box = target
        section.grid.endMove(member)
        assertEquals(listOf<EntityAccess>(entity), collect { section.grid.query(after, it)!! })
        assertEquals(emptyList(), collect { section.grid.query(before, it)!! })
        CrowdedSection.unprotectedWrites = LongSupplier { 1L }
        assertNull(section.grid.query(after) { Continuation.CONTINUE }, "a grid older than an unprotected write validated")
    }

    @Test
    fun duplicateOrUntrackableMembersDropTheGrid() {
        val section = Section(key)
        val entity = Fake(AABB(-15.5, 50.0, 33.0, -15.0, 51.0, 33.5))
        section.add(entity)
        section.add(entity)
        assertTrue(section.grid.dead)
        assertNull(entity.`vanadium$crowd`())
        assertNull(section.grid.query(entity.box) { Continuation.CONTINUE })
    }

    @Test
    fun crowdedSectionsBuildAndDropAtTheThreshold() {
        val threshold = CrowdedSections.THRESHOLD
        val crowds = CrowdedSections<EntityAccess>()
        val section = EntitySection(EntityAccess::class.java, Visibility.TICKING)
        val slot = crowds.slot(section, key)
        val live = java.util.function.LongFunction<EntitySection<EntityAccess>?> { if (it == key) section else null }
        val entities = List(threshold) { Fake(AABB(-15.5 + it % 8, 50.0, 33.0 + it / 8, -15.0 + it % 8, 51.0, 33.5 + it / 8)) }
        fun add(entity: Fake) {
            val grid = slot.grid
            if (grid == null) {
                section.add(entity)
                slot.offer()
            } else synchronized(grid) {
                section.add(entity)
                grid.addLocked(entity)
            }
        }
        fun remove(entity: Fake) {
            val grid = slot.grid ?: return run { section.remove(entity) }
            synchronized(grid) {
                section.remove(entity)
                grid.removeLocked(entity)
                slot.removedLocked(grid)
            }
        }
        entities.forEach(::add)
        crowds.refresh(true, live)
        assertNull(slot.grid, "an unarmed storage queued a section")
        remove(entities.last())
        add(entities.last()) // Armed now: reaching the threshold queues it.
        crowds.refresh(true, live)
        val grid = assertNotNull(slot.grid)
        assertTrue(entities.all { (it.`vanadium$crowd`() as CrowdedSection.Member<*>?)?.grid === grid })
        assertEquals(1, crowds.indexedCount())

        // An unprotected write: the grid stops validating and is rebuilt at the next refresh.
        CrowdedSection.unprotectedWrites = LongSupplier { 3L }
        assertNull(grid.query(entities[0].box) { Continuation.CONTINUE })
        crowds.refresh(true, live)
        val rebuilt = assertNotNull(slot.grid)
        assertTrue(grid.dead && rebuilt !== grid && rebuilt.epoch == 3L)
        assertNotNull(rebuilt.query(entities[0].box) { Continuation.CONTINUE })

        // Shrinking below half drops it at once; growing back queues it again.
        val removed = entities.take(threshold - threshold / 2 + 1)
        removed.forEach(::remove)
        assertNull(slot.grid)
        assertTrue(rebuilt.dead)
        assertTrue(entities.all { it.`vanadium$crowd`() == null })
        removed.forEach(::add)
        crowds.refresh(true, live)
        assertNotNull(slot.grid)

        // Disabled: dropped; a section that left the storage is dropped too.
        crowds.refresh(false, live)
        assertNull(slot.grid)
        assertEquals(0, crowds.indexedCount())
        remove(entities[0]); add(entities[0])
        crowds.refresh(true, live) // Not armed while disabled, so nothing was queued.
        assertNull(slot.grid)
        remove(entities[0]); add(entities[0])
        crowds.refresh(true) { null }
        assertNull(slot.grid)
    }

    /**
     * Writers move, re-section and remove entities while readers query, with no pauses inside a round. Every
     * entity whose box intersects the query box throughout comes back exactly once; none comes back twice;
     * one removed before the query started never comes back. Quiet phases between rounds make sure the grid
     * itself (not only the fallback) answered queries.
     */
    @Test
    fun concurrentReadersSeeEveryEntityThatStaysInTheBox() {
        val random = Random(99)
        val a = Section(key)
        val b = Section(SectionPos.asLong(0, 3, 2)) // East of a: blocks x 0..15.
        val query = AABB(-6.0, 50.0, 36.0, -2.0, 52.0, 40.0)
        // Anchors sit in the box; jitterers slide across cell boundaries, always intersecting it.
        val anchors = List(10) { Fake(AABB(-5.8 + it * 0.3, 50.5, 37.0, -5.5 + it * 0.3, 51.5, 37.6)) }
        val jitterers = List(16) { i ->
            val z = 36.2 + i * 0.22
            Fake(AABB(-6.6, 50.0, z, -5.9, 51.9, z + 0.6))
        }
        // Jumpers hop between the first and the last cells the query visits, staying in the box: a reader that
        // did not validate could read the first cell before a hop and the last one after it.
        val near = { i: Int -> AABB(-5.9, 50.2, 36.1 + i * 0.1, -5.5, 51.0, 36.4 + i * 0.1) }
        val far = { i: Int -> AABB(-2.5, 51.1, 39.6 - i * 0.1, -2.1, 51.9, 39.9 - i * 0.1) }
        val jumpers = List(12) { Fake(far(it)) }
        // Travellers cross between a and b and wander through the box; churners leave and come back.
        val travellers = List(24) { Fake(randomBox(random)) }
        val churners = List(20) { Fake(randomBox(random)) }
        val inA = Collections.newSetFromMap(IdentityHashMap<Fake, Boolean>())
        (anchors + jitterers + jumpers + travellers + churners).forEach { a.add(it); inA.add(it) }
        val mustReturn = anchors + jitterers + jumpers

        val writers = 3
        val readers = 3
        val rounds = 40
        val barrier = CyclicBarrier(writers + readers)
        val failure = AtomicReference<Throwable>()
        val viaGrid = AtomicInteger()
        val pool = Executors.newFixedThreadPool(writers + readers)
        fun guarded(body: () -> Unit) = Runnable {
            try {
                body()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                barrier.reset()
            }
        }
        val owned = (0 until writers).map { w ->
            (jitterers + jumpers).filterIndexed { i, _ -> i % writers == w } to
                (travellers + churners).filterIndexed { i, _ -> i % writers == w }
        }
        repeat(writers) { w ->
            pool.execute(guarded {
                val local = Random(w)
                val (slide, others) = owned[w]
                repeat(rounds) {
                    barrier.await(30, TimeUnit.SECONDS)
                    repeat(400) {
                        for (entity in slide) {
                            val jumper = jumpers.indexOf(entity)
                            if (jumper >= 0) {
                                entity.move(if (entity.box.minX < -4.0) far(jumper) else near(jumper))
                                continue
                            }
                            val dx = local.nextDouble(-0.5, 0.5)
                            val x0 = (entity.box.minX + dx).coerceIn(-6.6, -2.1) // Crosses the cells at x = -6, -4 and -2.
                            entity.move(AABB(x0, entity.box.minY, entity.box.minZ, x0 + 0.7, entity.box.maxY, entity.box.maxZ))
                        }
                        val entity = others[local.nextInt(others.size)]
                        if (entity in churners) {
                            if (entity.gone) {
                                entity.changes.incrementAndGet()
                                entity.gone = false
                                a.add(entity)
                            } else {
                                a.remove(entity)
                                entity.gone = true
                                entity.changes.incrementAndGet()
                            }
                        } else {
                            // Leave the section as the move callback does (old section first), then write the box.
                            val (from, to) = if (synchronized(inA) { entity in inA }) a to b else b to a
                            val dx = if (to === b) 16.0 else -16.0
                            from.remove(entity)
                            to.add(entity)
                            synchronized(inA) { if (to === a) inA.add(entity) else inA.remove(entity) }
                            entity.move(entity.box.move(dx, 0.0, 0.0))
                            entity.move(randomBox(local).move(if (to === b) 16.0 else 0.0, 0.0, 0.0))
                        }
                    }
                    barrier.await(30, TimeUnit.SECONDS) // Quiet phase: readers only.
                    barrier.await(30, TimeUnit.SECONDS)
                }
            })
        }
        repeat(readers) {
            pool.execute(guarded {
                fun check(section: Section, required: List<Fake>) {
                    // Removed before the query started, and not touched until it ended: must not come back.
                    val gone = churners.filter { it.gone }.map { it to it.changes.get() }
                    val found = ArrayList<EntityAccess>()
                    if (section.query(query) { found.add(it); Continuation.CONTINUE }) viaGrid.incrementAndGet()
                    val seen = Collections.newSetFromMap(IdentityHashMap<EntityAccess, Boolean>())
                    for (entity in found) check(seen.add(entity)) { "returned twice: ${entity.boundingBox}" }
                    for (entity in required) check(entity in seen) { "missed ${entity.boundingBox}" }
                    for ((entity, changes) in gone) {
                        check(entity !in seen || entity.changes.get() != changes) { "returned an entity removed before the query" }
                    }
                }
                repeat(rounds) {
                    barrier.await(30, TimeUnit.SECONDS)
                    repeat(2000) {
                        check(a, mustReturn)
                        check(b, emptyList())
                    }
                    barrier.await(30, TimeUnit.SECONDS)
                    repeat(20) { check(a, mustReturn) }
                    barrier.await(30, TimeUnit.SECONDS)
                }
            })
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "workers did not finish")
        failure.get()?.let { throw AssertionError("concurrent query failed", it) }
        assertTrue(viaGrid.get() >= rounds * readers * 20, "the grid answered only ${viaGrid.get()} queries")
        // Afterwards the grids agree with their lists exactly.
        for (section in listOf(a, b)) {
            repeat(300) {
                val box = randomBox(random, 6.0).move(if (section === b) 16.0 else 0.0, 0.0, 0.0).inflate(random.nextDouble(0.0, 2.0))
                val expected = collect { section.scan(box, it) }
                assertEquals(expected, collect { assertNotNull(section.grid.query(box, it)) })
            }
            for (entity in section.list) assertSame(section.grid, ((entity as Fake).`vanadium$crowd`())!!.grid)
        }
        for (entity in churners) if (entity.gone) assertNull(entity.`vanadium$crowd`())
        assertFalse(a.grid.dead || b.grid.dead)
    }
}
