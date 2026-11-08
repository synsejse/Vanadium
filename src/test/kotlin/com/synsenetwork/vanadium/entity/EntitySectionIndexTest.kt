package com.synsenetwork.vanadium.entity

import it.unimi.dsi.fastutil.longs.LongAVLTreeSet
import net.minecraft.core.SectionPos
import net.minecraft.util.AbortableIterationConsumer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntitySection
import net.minecraft.world.level.entity.Visibility
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class EntitySectionIndexTest {
    private val entity = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EntityAccess::class.java)) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            else -> null
        }
    } as EntityAccess

    @Test
    fun boxQueriesAndColumnsMatchVanillaOrder() {
        val random = Random(42)
        val index = EntitySectionIndex<EntityAccess>()
        val sorted = LongAVLTreeSet() // Vanilla's sectionIds.
        val keyOf = IdentityHashMap<EntitySection<EntityAccess>, Long>()
        repeat(600) {
            val key = SectionPos.asLong(random.nextInt(-6, 7), random.nextInt(-4, 20), random.nextInt(-6, 7))
            val section = index.getOrCreate(key) { EntitySection(EntityAccess::class.java, Visibility.TICKING) }
            if (sorted.add(key)) {
                section.add(entity)
                keyOf[section] = key
            }
        }
        // Empty and inaccessible sections exist but are skipped by queries.
        val empty = SectionPos.asLong(0, 0, 0).also { sorted.remove(it); index.remove(it) }
        index.getOrCreate(empty) { EntitySection(EntityAccess::class.java, Visibility.TICKING) }
        sorted.add(empty)

        repeat(400) {
            val x0 = random.nextInt(-8, 8); val y0 = random.nextInt(-6, 22); val z0 = random.nextInt(-8, 8)
            val box = intArrayOf(x0, y0, z0, x0 + random.nextInt(0, 6), y0 + random.nextInt(0, 8), z0 + random.nextInt(0, 6))
            val expected = vanillaQuery(sorted, box).filter { it != empty }
            val actual = ArrayList<Long>()
            index.forEachAccessibleNonEmpty(box[0], box[1], box[2], box[3], box[4], box[5]) {
                actual.add(keyOf.getValue(it))
                AbortableIterationConsumer.Continuation.CONTINUE
            }
            assertEquals(expected, actual, "box ${box.toList()}")
        }

        for (x in -6..6) for (z in -6..6) {
            val chunk = ChunkPos.pack(x, z)
            val vanilla = sorted.subSet(SectionPos.asLong(x, 0, z), SectionPos.asLong(x, -1, z) + 1).toLongArray().toList()
            assertEquals(vanilla, index.sectionKeysInChunk(chunk).toList(), "column $x,$z")
        }
        assertEquals(sorted.size, index.size())
        val chunks = sorted.map { ChunkPos.pack(SectionPos.x(it), SectionPos.z(it)) }.toSet()
        assertEquals(chunks, index.chunksWithSections().toSet())

        for (key in sorted.toLongArray().filterIndexed { i, _ -> i % 3 == 0 }) {
            index.remove(key)
            sorted.remove(key)
        }
        assertEquals(sorted.size, index.size())
        assertEquals(sorted.map { ChunkPos.pack(SectionPos.x(it), SectionPos.z(it)) }.toSet(), index.chunksWithSections().toSet())
    }

    @Test
    fun abortStopsTheQuery() {
        val index = EntitySectionIndex<EntityAccess>()
        for (x in 0 until 4) index.getOrCreate(SectionPos.asLong(x, 0, 0)) {
            EntitySection<EntityAccess>(EntityAccess::class.java, Visibility.TICKING).apply { add(entity) }
        }
        var visited = 0
        index.forEachAccessibleNonEmpty(0, 0, 0, 3, 0, 0) {
            visited++
            if (visited == 2) AbortableIterationConsumer.Continuation.ABORT else AbortableIterationConsumer.Continuation.CONTINUE
        }
        assertEquals(2, visited)
    }

    /** Vanilla `forEachAccessibleNonEmptySection` key order, before the per-section checks. */
    private fun vanillaQuery(sorted: LongAVLTreeSet, box: IntArray): List<Long> {
        val keys = ArrayList<Long>()
        for (x in box[0]..box[3]) {
            val it = sorted.subSet(SectionPos.asLong(x, 0, 0), SectionPos.asLong(x, -1, -1) + 1).iterator()
            while (it.hasNext()) {
                val key = it.nextLong()
                val y = SectionPos.y(key)
                val z = SectionPos.z(key)
                if (y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5]) keys.add(key)
            }
        }
        return keys
    }
}
