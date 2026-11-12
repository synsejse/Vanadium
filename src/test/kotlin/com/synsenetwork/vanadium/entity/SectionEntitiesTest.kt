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

    /** What the previous whole-level iteration (columns, then each accessible section's entity stream) visited. */
    private fun reference(index: EntitySectionIndex<EntityAccess>): List<Int> =
        index.accessibleSections().flatMap { section -> section.entities.toList() }.map { ids.getValue(it) }

    private fun Iterable<EntityAccess>.ids() = map { ids.getValue(it) }

    @Test
    fun fullIterationMatchesSections() {
        val index = index(Random(7))
        val expected = reference(index)
        assertEquals(expected, index.accessibleEntities().ids())
        assertEquals(expected, index.accessibleEntities().snapshot().let { it.range(0, it.size, null) }.ids())
        val iterator = index.accessibleEntities().iterator()
        repeat(expected.size) { iterator.next() } // next() without hasNext() still advances.
        assertFalse(iterator.hasNext())
        assertFailsWith<NoSuchElementException> { iterator.next() }
        // Hidden and empty sections are not scanned; the count matches only accessible entities.
        val accessible = index.accessibleSections()
        assertEquals(accessible.sumOf { it.size() }, expected.size)
        assertEquals(accessible.size, accessible.count { !it.isEmpty && it.status.isAccessible })
    }

    @Test
    fun consecutiveRangesConcatenateToTheFullOrder() {
        val random = Random(11)
        val index = index(random)
        val sections = index.accessibleEntities().snapshot()
        val expected = reference(index)
        val even = Predicate<EntityAccess> { ids.getValue(it) % 2 == 0 }
        val expectedEven = expected.filter { it % 2 == 0 }
        for (jobs in listOf(1, 2, 3, 7, 16, 25, 97, sections.size + 3)) {
            // SpawnPreparation's even split, with the final range possibly short or empty.
            val batch = (sections.size + jobs - 1) / jobs
            val ranges = (0 until jobs).map { minOf(sections.size, it * batch) to minOf(sections.size, (it + 1) * batch) }
            assertEquals(expected, ranges.flatMap { (from, to) -> sections.range(from, to, null).ids() }, "jobs=$jobs")
            assertEquals(expectedEven, ranges.flatMap { (from, to) -> sections.range(from, to, even).ids() }, "filtered jobs=$jobs")
        }
        repeat(200) {
            val cuts = (List(random.nextInt(0, 12)) { random.nextInt(0, sections.size + 1) } + 0 + sections.size).sorted()
            val pieces = cuts.zipWithNext().flatMap { (from, to) -> sections.range(from, to, null).ids() }
            assertEquals(expected, pieces, "cuts $cuts")
        }
        assertEquals(emptyList(), sections.range(5, 5, null).ids())
        assertEquals(emptyList(), sections.range(sections.size, sections.size, null).ids())
        assertEquals(emptyList(), SectionEntities<EntityAccess>(emptyList()).range(0, 0, null).ids())
    }
}
