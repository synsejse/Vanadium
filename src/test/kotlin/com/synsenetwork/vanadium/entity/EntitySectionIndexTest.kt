package com.synsenetwork.vanadium.entity

import it.unimi.dsi.fastutil.longs.LongAVLTreeSet
import net.minecraft.core.SectionPos
import net.minecraft.util.Continuation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntitySection
import net.minecraft.world.level.entity.Visibility
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
                Continuation.CONTINUE
            }
            assertEquals(expected, actual, "box ${box.toList()}")
        }

        assertLookupsMatch(index, sorted)
        val chunks = sorted.map { ChunkPos.pack(SectionPos.x(it), SectionPos.z(it)) }.toSet()
        assertEquals(chunks, index.chunksWithSections().toSet())

        val removed = sorted.toLongArray().filterIndexed { i, _ -> i % 3 == 0 }
        for (key in removed) {
            index.remove(key)
            sorted.remove(key)
        }
        index.remove(SectionPos.asLong(40, 0, 40)) // Absent column.
        index.remove(SectionPos.asLong(0, 100, 0)) // Absent Y in a present column.
        assertLookupsMatch(index, sorted)
        for (key in removed) assertNull(index[key])
        assertEquals(sorted.map { ChunkPos.pack(SectionPos.x(it), SectionPos.z(it)) }.toSet(), index.chunksWithSections().toSet())

        // Recreating a removed key builds a new section; the old one is gone from every view.
        val key = removed.first()
        val old = keyOf.entries.first { it.value == key }.key
        val recreated = index.getOrCreate(key) { EntitySection(EntityAccess::class.java, Visibility.TICKING) }
        assertNotSame(old, recreated)
        assertSame(recreated, index[key])
        val chunk = ChunkPos.pack(SectionPos.x(key), SectionPos.z(key))
        assertTrue(index.sectionsInChunk(chunk).none { it === old })
        assertTrue(index.sectionsInChunk(chunk).any { it === recreated })
    }

    /** Key lookups, per-chunk keys and per-chunk sections all agree with vanilla's sorted key set. */
    private fun assertLookupsMatch(index: EntitySectionIndex<EntityAccess>, sorted: LongAVLTreeSet) {
        for (x in -6..6) for (z in -6..6) {
            val chunk = ChunkPos.pack(x, z)
            val vanilla = sorted.subSet(SectionPos.asLong(x, 0, z), SectionPos.asLong(x, -1, z) + 1).toLongArray().toList()
            assertEquals(vanilla, index.sectionKeysInChunk(chunk).toList(), "column $x,$z")
            val sections = index.sectionsInChunk(chunk)
            assertEquals(vanilla.size, sections.size, "column $x,$z sections")
            for (i in vanilla.indices) assertSame(index[vanilla[i]], sections[i], "column $x,$z section $i")
            for (key in vanilla) assertTrue(index[key] != null, "missing section $key")
        }
        assertEquals(sorted.size, index.size())
    }

    @Test
    fun creationPublishesOnceAfterCreateReturns() {
        val index = EntitySectionIndex<EntityAccess>()
        val key = SectionPos.asLong(-3, -2, 5)
        val inCreate = CountDownLatch(1)
        val release = CountDownLatch(1)
        val creates = AtomicInteger()
        val pool = Executors.newFixedThreadPool(5)
        try {
            val creators = List(4) {
                pool.submit<EntitySection<EntityAccess>> {
                    index.getOrCreate(key) {
                        creates.incrementAndGet()
                        inCreate.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "create gate timed out" }
                        EntitySection(EntityAccess::class.java, Visibility.TICKING)
                    }
                }
            }
            assertTrue(inCreate.await(10, TimeUnit.SECONDS))
            // While create runs, readers on other threads do not see the section yet.
            pool.submit {
                assertNull(index[key])
                assertEquals(0, index.sectionsInChunk(ChunkPos.pack(-3, 5)).size)
                assertEquals(0, index.size())
            }.get(10, TimeUnit.SECONDS)
            release.countDown()
            val sections = creators.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, creates.get())
            assertTrue(sections.all { it === sections[0] })
            assertSame(sections[0], index[key])
        } finally {
            pool.shutdownNow()
        }
    }

    /** A section tagged with the Y and generation it was created for. */
    private class Tagged(val y: Int, val generation: Int) : EntitySection<EntityAccess>(EntityAccess::class.java, Visibility.TICKING)

    @Test
    fun readersNeverSeeATornColumn() {
        val index = EntitySectionIndex<EntityAccess>()
        // Columns on both sides of zero; each writer owns some columns and cycles them through
        // {} → {lo} → {lo, hi} → {hi} → {} with fresh sections every round.
        // Columns on both sides of zero and far from it.
        val columns = listOf(-2 to -1, -1 to 0, 0 to -1, 0 to 0, 1 to 1, -1 to -1, 15 to 16, -16 to -17, 16 to -16)
        val lo = -3
        val hi = 4
        val writers = 2
        val readers = 3
        val rounds = 20_000
        val start = CountDownLatch(1)
        val writing = AtomicBoolean(true)
        val observations = AtomicInteger()
        val pool = Executors.newFixedThreadPool(writers + readers)
        try {
            val writerJobs = List(writers) { w ->
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS)) { "writer gate timed out" }
                    val mine = columns.filterIndexed { i, _ -> i % writers == w }
                    for (round in 0 until rounds) {
                        for ((x, z) in mine) create(index, x, lo, z, round)
                        for ((x, z) in mine) create(index, x, hi, z, round)
                        for ((x, z) in mine) index.remove(SectionPos.asLong(x, lo, z))
                        for ((x, z) in mine) index.remove(SectionPos.asLong(x, hi, z))
                    }
                }
            }
            val readerJobs = List(readers) {
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS)) { "reader gate timed out" }
                    val seen = ArrayList<Tagged>()
                    var reads = 0
                    while (writing.get() || reads == 0) {
                        reads++
                        for ((x, z) in columns) {
                            seen.clear()
                            index.forEachAccessibleNonEmpty(x, lo - 1, z, x, hi + 1, z) {
                                seen.add(it as Tagged)
                                Continuation.CONTINUE
                            }
                            checkColumn(seen, lo, hi, "query $x,$z")
                            seen.clear()
                            for (section in index.sectionsInChunk(ChunkPos.pack(x, z))) seen.add(section as Tagged)
                            checkColumn(seen, lo, hi, "chunk $x,$z")
                            val keys = index.sectionKeysInChunk(ChunkPos.pack(x, z))
                            for (key in keys) check(SectionPos.x(key) == x && SectionPos.z(key) == z) { "foreign key in $x,$z" }
                            check(keys.size <= 2 && (keys.size < 2 || SectionPos.y(keys[0]) == hi && SectionPos.y(keys[1]) == lo)) {
                                "column $x,$z keys ${keys.map(SectionPos::y)}"
                            }
                            val single = index[SectionPos.asLong(x, lo, z)]
                            check(single == null || (single as Tagged).y == lo) { "lookup of $x,$lo,$z returned Y ${(single as Tagged).y}" }
                        }
                    }
                    observations.addAndGet(reads)
                }
            }
            start.countDown()
            writerJobs.forEach { it.get(60, TimeUnit.SECONDS) }
            writing.set(false)
            readerJobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            writing.set(false)
            pool.shutdownNow()
        }
        assertTrue(observations.get() >= readers)
        assertEquals(0, index.size())
        assertTrue(index.chunksWithSections().isEmpty())
    }

    private fun create(index: EntitySectionIndex<EntityAccess>, x: Int, y: Int, z: Int, round: Int) {
        val section = index.getOrCreate(SectionPos.asLong(x, y, z)) { Tagged(y, round).apply { add(entity) } }
        check(section is Tagged && section.y == y && section.generation == round) { "getOrCreate returned a stale section" }
    }

    /**
     * One column snapshot is a state some writer published: {}, {lo}, {lo, hi} of one round, or {hi}; in
     * vanilla order, non-negative [hi] comes before negative [lo].
     */
    private fun checkColumn(seen: List<Tagged>, lo: Int, hi: Int, where: String) {
        val ys = seen.map { it.y }
        check(ys.isEmpty() || ys == listOf(lo) || ys == listOf(hi) || ys == listOf(hi, lo)) { "$where saw Ys $ys" }
        check(seen.size < 2 || seen[0].generation == seen[1].generation) {
            "$where mixed generations ${seen.map { it.generation }}"
        }
    }

    @Test
    fun wideQueriesMatchVanillaOrderAsAreasEmptyAndRefill() {
        val random = Random(9)
        val index = EntitySectionIndex<EntityAccess>()
        val sorted = LongAVLTreeSet()
        val keyOf = IdentityHashMap<EntitySection<EntityAccess>, Long>()
        fun add(key: Long) {
            val section = index.getOrCreate(key) { EntitySection<EntityAccess>(EntityAccess::class.java, Visibility.TICKING).apply { add(entity) } }
            if (sorted.add(key)) keyOf[section] = key
        }
        // Sparse keys over a wide area, plus dense strips of columns around X = ±16.
        repeat(1_500) { add(SectionPos.asLong(random.nextInt(-40, 41), random.nextInt(-4, 8), random.nextInt(-40, 41))) }
        for (x in intArrayOf(-17, -16, -15, 15, 16, 17)) for (z in -40..40) add(SectionPos.asLong(x, 2, z))
        fun checkQueries() {
            repeat(300) {
                val x0 = random.nextInt(-44, 40); val y0 = random.nextInt(-6, 8); val z0 = random.nextInt(-44, 40)
                val box = intArrayOf(x0, y0, z0, x0 + random.nextInt(0, 40), y0 + random.nextInt(0, 6), z0 + random.nextInt(0, 40))
                val actual = ArrayList<Long>()
                index.forEachAccessibleNonEmpty(box[0], box[1], box[2], box[3], box[4], box[5]) {
                    actual.add(keyOf.getValue(it))
                    Continuation.CONTINUE
                }
                assertEquals(vanillaQuery(sorted, box), actual, "box ${box.toList()}")
            }
            for (x in -41..41) for (z in -41..41) {
                val vanilla = sorted.subSet(SectionPos.asLong(x, 0, z), SectionPos.asLong(x, -1, z) + 1).toLongArray().toList()
                assertEquals(vanilla, index.sectionKeysInChunk(ChunkPos.pack(x, z)).toList(), "column $x,$z")
            }
            assertEquals(sorted.size, index.size())
            assertEquals(sorted.map { ChunkPos.pack(SectionPos.x(it), SectionPos.z(it)) }.toSet(), index.chunksWithSections().toSet())
        }
        checkQueries()
        // Empty two 16×16-chunk areas entirely, then refill part of one.
        for (key in sorted.toLongArray()) {
            val x = SectionPos.x(key); val z = SectionPos.z(key)
            if ((x shr 4 == -1 && z shr 4 == -1) || (x shr 4 == 1 && z shr 4 == 0)) {
                index.remove(key)
                sorted.remove(key)
            }
        }
        assertTrue(sorted.none { SectionPos.x(it) shr 4 == -1 && SectionPos.z(it) shr 4 == -1 })
        checkQueries()
        for (x in -16..-1 step 3) for (z in -16..-1 step 5) add(SectionPos.asLong(x, -1, z))
        checkQueries()
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
            if (visited == 2) Continuation.ABORT else Continuation.CONTINUE
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
