package com.synsenetwork.vanadium.entity

import org.jctools.maps.NonBlockingHashMapLong
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import net.minecraft.core.SectionPos
import net.minecraft.util.AbortableIterationConsumer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntitySection
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import java.util.function.LongFunction

/**
 * Entity sections of one level, replacing `EntitySectionStorage`'s hash map and AVL tree. Readers
 * (every AI entity query, on every worker) take no lock and allocate nothing; writers serialize.
 *
 * The only map is chunk key → [Column], an immutable snapshot of that column's sections sorted by
 * packed Y bits. A change publishes a new column, so a reader sees each column either wholly before or
 * wholly after any write: never a Y without its section, nor a section paired with a removed or
 * replaced key. A box query costs one map read per chunk column in the box, and a key lookup one map
 * read plus a scan of that column's few sections.
 *
 * Visit order matches vanilla's sorted section keys: X ascending, then Z and Y each in the unsigned order
 * of their packed bits (non-negative ascending, then negative ascending).
 *
 * Every column also has a slot in a dense array, so whole-level scans ([accessibleEntities]) can be split
 * into slot ranges and run on several threads without first walking the level to build a list.
 */
class EntitySectionIndex<T : EntityAccess> {
    /** One chunk column's sections; never mutated after publication. */
    internal class Column<T : EntityAccess>(
        /** Section Ys, sorted by [unsigned]. */
        @JvmField val ys: IntArray,
        @JvmField val sections: Array<EntitySection<T>>,
        /** Position in [slots]; the one mutable field, read and written only by writers under the index lock. */
        @JvmField var slot: Int,
    ) {
        fun indexOf(y: Int): Int {
            val ys = ys
            for (i in ys.indices) if (ys[i] == y) return i
            return -1
        }
    }

    private val columns = NonBlockingHashMapLong<Column<T>>()

    /** Written under the lock, read without it. */
    @Volatile
    private var count = 0

    /**
     * Every column, at its [Column.slot], in `[0, slotCount)`; written under the lock. A column replaced by a
     * write takes over its predecessor's slot; a removed one is replaced by the last slot's column. Scans that
     * read it without the lock need writers to be quiescent (see [accessibleEntities]).
     */
    @Volatile
    private var slots = arrayOfNulls<Column<T>>(16)

    @Volatile
    private var slotCount = 0

    /**
     * Entities seen per slot by the latest whole-level scan, to split the next one evenly (a hint: stale or
     * missing entries only unbalance the split). Written by scanning threads, each to its own slots.
     */
    @Volatile
    internal var slotWeights = IntArray(0)

    operator fun get(key: Long): EntitySection<T>? {
        val column = columns[chunkKey(key)] ?: return null
        val at = column.indexOf(SectionPos.y(key))
        return if (at < 0) null else column.sections[at]
    }

    /**
     * The section at [key], creating it with [create] if absent. [create] runs under this index's lock
     * and must not call back into it; the new section is published only after [create] returns.
     */
    fun getOrCreate(key: Long, create: LongFunction<EntitySection<T>>): EntitySection<T> =
        get(key) ?: synchronized(this) {
            val chunk = chunkKey(key)
            val y = SectionPos.y(key)
            val column = columns[chunk]
            val at = column?.indexOf(y) ?: -1
            if (at >= 0) return column!!.sections[at]
            val section = create.apply(key)
            publish(chunk, inserted(column, y, section))
            count++
            section
        }

    fun remove(key: Long) {
        synchronized(this) {
            val chunk = chunkKey(key)
            val column = columns[chunk] ?: return
            val at = column.indexOf(SectionPos.y(key))
            if (at < 0) return
            if (column.ys.size == 1) {
                columns.remove(chunk)
                releaseSlot(column.slot)
            } else {
                publish(chunk, removed(column, at))
            }
            count--
        }
    }

    fun size(): Int = count

    /** Accessible, non-empty sections overlapping the section-coordinate box, in vanilla order. */
    fun forEachAccessibleNonEmpty(
        xMin: Int, yMin: Int, zMin: Int, xMax: Int, yMax: Int, zMax: Int,
        output: AbortableIterationConsumer<EntitySection<T>>,
    ) = walk(xMin, yMin, zMin, xMax, yMax, zMax, output, null, null, null)

    /** `EntitySectionStorage.forEachAccessibleNonEmptySection`: the sections near [bb], in vanilla order. */
    fun forEachAccessibleNonEmpty(bb: AABB, output: AbortableIterationConsumer<EntitySection<T>>) =
        walk(minX(bb), minY(bb), minZ(bb), maxX(bb), maxY(bb), maxZ(bb), output, null, null, null)

    /**
     * `EntitySectionStorage.getEntities`: each section's `getEntities` for [bb] (and [type], when not null) over
     * the sections [forEachAccessibleNonEmpty] visits for [bb], in that order, until [output] aborts. Vanilla
     * builds a capturing section lambda per query; the query is passed down instead, allocating nothing.
     */
    fun getEntities(bb: AABB, type: EntityTypeTest<T, *>?, output: AbortableIterationConsumer<*>) =
        walk(minX(bb), minY(bb), minZ(bb), maxX(bb), maxY(bb), maxZ(bb), null, type, bb, output)

    /** Visits [sections] if not null, otherwise queries each section with [type], [bb] and [entities]. */
    private fun walk(
        xMin: Int, yMin: Int, zMin: Int, xMax: Int, yMax: Int, zMax: Int,
        sections: AbortableIterationConsumer<EntitySection<T>>?,
        type: EntityTypeTest<T, *>?, bb: AABB?, entities: AbortableIterationConsumer<*>?,
    ) {
        for (x in xMin..xMax) {
            // Z in unsigned packed order: the non-negative part of the range first.
            if (zMin < 0 && zMax >= 0) {
                for (z in 0..zMax) if (visitColumn(x, z, yMin, yMax, sections, type, bb, entities)) return
                for (z in zMin..-1) if (visitColumn(x, z, yMin, yMax, sections, type, bb, entities)) return
            } else {
                for (z in zMin..zMax) if (visitColumn(x, z, yMin, yMax, sections, type, bb, entities)) return
            }
        }
    }

    /** Returns true when the visitor or entity consumer asked to abort. */
    @Suppress("UNCHECKED_CAST")
    private fun visitColumn(
        x: Int, z: Int, yMin: Int, yMax: Int,
        sections: AbortableIterationConsumer<EntitySection<T>>?,
        type: EntityTypeTest<T, *>?, bb: AABB?, entities: AbortableIterationConsumer<*>?,
    ): Boolean {
        val column = columns[ChunkPos.pack(x, z)] ?: return false
        val ys = column.ys
        val columnSections = column.sections
        for (i in ys.indices) {
            val y = ys[i]
            if (y < yMin || y > yMax) continue
            val section = columnSections[i]
            if (section.isEmpty || !section.status.isAccessible) continue
            val next = when {
                sections != null -> sections.accept(section)
                type == null -> section.getEntities(bb!!, entities as AbortableIterationConsumer<T>)
                else -> section.getEntities(type as EntityTypeTest<T, T>, bb!!, entities as AbortableIterationConsumer<T>)
            }
            if (next.shouldAbort()) return true
        }
        return false
    }

    /** Section keys in one chunk column, in vanilla order. */
    fun sectionKeysInChunk(chunkKey: Long): LongArray {
        val column = columns[chunkKey] ?: return EMPTY
        val x = ChunkPos.getX(chunkKey)
        val z = ChunkPos.getZ(chunkKey)
        val ys = column.ys
        return LongArray(ys.size) { SectionPos.asLong(x, ys[it], z) }
    }

    /** Sections in one chunk column, in vanilla order, as they were when this was called. Do not modify the array. */
    fun sectionsInChunk(chunkKey: Long): Array<EntitySection<T>> = columns[chunkKey]?.sections ?: emptySections()

    /**
     * Entities of every accessible, non-empty section, column by column in slot order; for whole-level scans such
     * as spawn counting. Splittable into slot ranges while no section is created or removed.
     */
    fun accessibleEntities(): ColumnScan<T> = ColumnScan(this)

    /** The column slots as they are now: a stable view while no section is created or removed. */
    internal fun columnSlots(): Array<Column<T>?> = slots

    internal fun columnSlotCount(): Int = slotCount

    /** Accessible, non-empty sections as they are now, in [accessibleEntities]'s order. */
    fun accessibleSections(): ArrayList<EntitySection<T>> {
        val result = ArrayList<EntitySection<T>>(count)
        val slots = slots
        for (i in 0 until slotCount) {
            for (section in slots[i]!!.sections) {
                if (!section.isEmpty && section.status.isAccessible) result.add(section)
            }
        }
        return result
    }

    fun chunksWithSections(): LongSet {
        val chunks = LongOpenHashSet()
        val keys = columns.IteratorLong()
        while (keys.hasNext()) chunks.add(keys.nextLong())
        return chunks
    }

    /** Publishes [column] for [chunk], in its predecessor's slot or, for a new column (slot -1), in a new last slot. */
    private fun publish(chunk: Long, column: Column<T>) {
        if (column.slot < 0) {
            var slots = slots
            val at = slotCount
            if (at == slots.size) {
                slots = slots.copyOf(at * 2)
                this.slots = slots
            }
            column.slot = at
            slots[at] = column
            slotCount = at + 1
        } else {
            slots[column.slot] = column
        }
        columns.put(chunk, column)
    }

    /** Moves the last slot's column into the emptied [slot]. */
    private fun releaseSlot(slot: Int) {
        val slots = slots
        val last = slotCount - 1
        val moved = slots[last]!!
        moved.slot = slot
        slots[slot] = moved
        slots[last] = null
        slotCount = last
    }

    private fun inserted(column: Column<T>?, y: Int, section: EntitySection<T>): Column<T> {
        if (column == null) return Column(intArrayOf(y), sectionArray(1).also { it[0] = section }, -1)
        val ys = column.ys
        var at = 0
        while (at < ys.size && unsigned(ys[at]) < unsigned(y)) at++
        val nextYs = IntArray(ys.size + 1)
        ys.copyInto(nextYs, 0, 0, at)
        nextYs[at] = y
        ys.copyInto(nextYs, at + 1, at)
        val nextSections = sectionArray(ys.size + 1)
        column.sections.copyInto(nextSections, 0, 0, at)
        nextSections[at] = section
        column.sections.copyInto(nextSections, at + 1, at)
        return Column(nextYs, nextSections, column.slot)
    }

    private fun removed(column: Column<T>, at: Int): Column<T> {
        val size = column.ys.size - 1
        val nextYs = IntArray(size)
        column.ys.copyInto(nextYs, 0, 0, at)
        column.ys.copyInto(nextYs, at, at + 1)
        val nextSections = sectionArray(size)
        column.sections.copyInto(nextSections, 0, 0, at)
        column.sections.copyInto(nextSections, at, at + 1)
        return Column(nextYs, nextSections, column.slot)
    }

    @Suppress("UNCHECKED_CAST")
    private fun sectionArray(size: Int): Array<EntitySection<T>> = arrayOfNulls<EntitySection<*>>(size) as Array<EntitySection<T>>

    @Suppress("UNCHECKED_CAST")
    private fun emptySections(): Array<EntitySection<T>> = NO_SECTIONS as Array<EntitySection<T>>

    private companion object {
        val EMPTY = LongArray(0)
        val NO_SECTIONS = arrayOfNulls<EntitySection<*>>(0)

        fun chunkKey(sectionKey: Long): Long = ChunkPos.pack(SectionPos.x(sectionKey), SectionPos.z(sectionKey))

        /** Y's position in vanilla's packed section key order. */
        fun unsigned(y: Int): Int = y and 0xFFFFF

        // Vanilla's section box around a query box: two blocks wider, four blocks lower.
        fun minX(bb: AABB): Int = SectionPos.posToSectionCoord(bb.minX - 2.0)
        fun minY(bb: AABB): Int = SectionPos.posToSectionCoord(bb.minY - 4.0)
        fun minZ(bb: AABB): Int = SectionPos.posToSectionCoord(bb.minZ - 2.0)
        fun maxX(bb: AABB): Int = SectionPos.posToSectionCoord(bb.maxX + 2.0)
        fun maxY(bb: AABB): Int = SectionPos.posToSectionCoord(bb.maxY + 0.0)
        fun maxZ(bb: AABB): Int = SectionPos.posToSectionCoord(bb.maxZ + 2.0)
    }
}
