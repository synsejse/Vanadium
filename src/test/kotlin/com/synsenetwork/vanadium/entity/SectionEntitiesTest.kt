package com.synsenetwork.vanadium.entity

import net.minecraft.core.SectionPos
import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntitySection
import net.minecraft.world.level.entity.Visibility
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.function.Predicate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SectionEntitiesTest {
    private val ids = IdentityHashMap<EntityAccess, Int>()

    private fun entity(): EntityAccess {
        val proxy = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EntityAccess::class.java)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args[0]
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    else -> null
                }
            }
        } as EntityAccess
        ids[proxy] = ids.size
        return proxy
    }

    /** An index with accessible sections of very different sizes, plus empty and hidden ones that scans skip. */
    private fun index(random: Random): EntitySectionIndex<EntityAccess> {
        val index = EntitySectionIndex<EntityAccess>()
        repeat(300) {
            val key = SectionPos.asLong(random.nextInt(-12, 12), random.nextInt(-4, 20), random.nextInt(-12, 12))
            val visibility = if (random.nextInt(8) == 0) Visibility.HIDDEN else if (random.nextBoolean()) Visibility.TRACKED else Visibility.TICKING
            val section = index.getOrCreate(key) { EntitySection(EntityAccess::class.java, visibility) }
            val count = when (random.nextInt(10)) {
                0 -> 0
                1 -> random.nextInt(200, 600) // A dense section larger than a job.
                else -> random.nextInt(1, 12)
            }
            repeat(count) { section.add(entity()) }
        }
        return index
    }

    /** What a whole-level scan visits: each accessible, non-empty section's entity stream, in the index's order. */
    private fun reference(index: EntitySectionIndex<EntityAccess>): List<Int> =
        index.accessibleSections().flatMap { section -> section.entities.toList() }.map { ids.getValue(it) }

    private fun Iterable<EntityAccess>.ids() = map { ids.getValue(it) }

    @Test
    fun fullIterationMatchesSections() {
        val index = index(Random(7))
        val expected = reference(index)
        assertEquals(expected, index.accessibleEntities().ids())
        assertEquals(expected, index.accessibleEntities().columns().let { it.range(0, it.size, null) }.ids())
        val iterator = index.accessibleEntities().iterator()
        repeat(expected.size) { iterator.next() } // next() without hasNext() still advances.
        assertFalse(iterator.hasNext())
        assertFailsWith<NoSuchElementException> { iterator.next() }
        // Hidden and empty sections are not scanned; the count matches only accessible entities.
        val accessible = index.accessibleSections()
        assertEquals(accessible.sumOf { it.size() }, expected.size)
        assertEquals(accessible.size, accessible.count { !it.isEmpty && it.status.isAccessible })
        // Every column is in exactly one slot.
        val columns = index.chunksWithSections()
        assertEquals(columns.size, index.accessibleEntities().columns().size)
    }

    @Test
    fun consecutiveRangesConcatenateToTheFullOrder() {
        val random = Random(11)
        val index = index(random)
        val columns = index.accessibleEntities().columns()
        val expected = reference(index)
        val even = Predicate<EntityAccess> { ids.getValue(it) % 2 == 0 }
        val expectedEven = expected.filter { it % 2 == 0 }
        for (parts in listOf(1, 2, 3, 7, 16, 25, 97, columns.size + 3)) {
            val bounds = columns.split(parts, 2)
            val ranges = bounds.toList().zipWithNext()
            assertEquals(expected, ranges.flatMap { (from, to) -> columns.range(from, to, null).ids() }, "parts=$parts")
            assertEquals(expectedEven, ranges.flatMap { (from, to) -> columns.range(from, to, even).ids() }, "filtered parts=$parts")
        }
        repeat(200) {
            val cuts = (List(random.nextInt(0, 12)) { random.nextInt(0, columns.size + 1) } + 0 + columns.size).sorted()
            val pieces = cuts.zipWithNext().flatMap { (from, to) -> columns.range(from, to, null).ids() }
            assertEquals(expected, pieces, "cuts $cuts")
        }
        assertEquals(emptyList(), columns.range(5, 5, null).ids())
        assertEquals(emptyList(), columns.range(columns.size, columns.size, null).ids())
        assertEquals(emptyList(), EntitySectionIndex<EntityAccess>().accessibleEntities().ids())
        assertEquals(0, EntitySectionIndex<EntityAccess>().accessibleEntities().columns().size)
    }

    @Test
    fun scansRecordColumnWeightsForBalancedSplits() {
        val index = index(Random(5))
        val expected = reference(index)
        val first = index.accessibleEntities().columns()
        assertEquals(0L, first.weight(), "no scan has run yet")
        // A split before any scan still covers every column.
        assertEquals(first.size, first.split(8, 2).last())
        first.range(0, first.size / 2, null).ids()
        first.range(first.size / 2, first.size, null).ids()
        val columns = index.accessibleEntities().columns()
        assertEquals(expected.size.toLong(), columns.weight(), "two half scans saw every entity once")
        val weights = (0 until columns.size).map { columns.range(it, it + 1, null).count() }
        for (parts in listOf(2, 3, 8, 37)) {
            val bounds = columns.split(parts, 2)
            assertEquals(0, bounds.first())
            assertEquals(columns.size, bounds.last())
            check(bounds.size - 1 in 1..parts) { "parts=$parts: ${bounds.size - 1} ranges" }
            for (i in 0 until bounds.size - 1) check(bounds[i] < bounds[i + 1]) { "parts=$parts: empty range at $i" }
            // Balanced up to one column: no range exceeds an equal share by more than the heaviest column.
            val cost = { from: Int, to: Int -> (from until to).sumOf { weights[it] + 2L } }
            val total = cost(0, columns.size)
            val heaviest = weights.max() + 2L
            for (i in 0 until bounds.size - 1) {
                val share = cost(bounds[i], bounds[i + 1])
                check(share <= total / (bounds.size - 1) + heaviest) { "parts=$parts: range $i costs $share of $total" }
            }
        }
    }

    @Test
    fun slotsFollowCreationAndRemoval() {
        val random = Random(3)
        val index = EntitySectionIndex<EntityAccess>()
        val live = HashSet<Long>()
        repeat(5000) {
            val key = SectionPos.asLong(random.nextInt(-6, 6), random.nextInt(-2, 3), random.nextInt(-6, 6))
            if (key in live && random.nextInt(3) == 0) {
                index.remove(key)
                live.remove(key)
            } else {
                index.getOrCreate(key) { EntitySection(EntityAccess::class.java, Visibility.TRACKED).also { it.add(entity()) } }
                live.add(key)
            }
            if (it % 250 == 0) {
                val chunks = live.map { k -> net.minecraft.world.level.ChunkPos.pack(SectionPos.x(k), SectionPos.z(k)) }.toSet()
                val columns = index.accessibleEntities().columns()
                assertEquals(chunks.size, columns.size, "one slot per column")
                // Every live section's entity, each once.
                val scanned = columns.range(0, columns.size, null).ids()
                assertEquals(scanned.size, scanned.toSet().size)
                assertEquals(live.map { k -> ids.getValue(index[k]!!.entities.findFirst().get()) }.toSet(), scanned.toSet())
            }
        }
    }
}
