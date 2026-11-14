package com.synsenetwork.vanadium.entity

import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntitySection
import java.util.function.Predicate

/**
 * Entities of the accessible, non-empty sections of one [EntitySectionIndex], for whole-level scans
 * (Lithium's chunk-aware spawn counting). Lazy: nothing is read until [iterator] or a [columns] range runs.
 */
class AccessibleEntities<T : EntityAccess> internal constructor(private val index: EntitySectionIndex<T>) : Iterable<T> {
    /** The index's column slots as they are now, for splitting one scan into ranges without reading any column. */
    fun columns(): ColumnScan<T> = ColumnScan(index)

    override fun iterator(): Iterator<T> = columns().let { ColumnScan.Range(it, 0, it.size, null) }
}

/**
 * The column slots of an [EntitySectionIndex] at construction. A [range] of slots can be iterated on any thread
 * while no section is created or removed (a counting wave runs nothing else); concatenating consecutive ranges
 * visits the same entities in the same order as one full range. Each range records how many entities each of
 * its columns held, so the next scan's [split] is balanced without walking the level first.
 */
class ColumnScan<T : EntityAccess> internal constructor(index: EntitySectionIndex<T>) {
    private val slots = index.columnSlots()

    /** Column slots in this scan. */
    val size = minOf(index.columnSlotCount(), slots.size)

    /** Entities per slot as of the previous scan (0 when unknown). Shared with that scan's ranges. */
    private val weights = index.slotWeights.let { weights ->
        if (weights.size >= size) weights else weights.copyOf(slots.size).also { index.slotWeights = it }
    }

    /** Entities `from` until `to` of the slot order that pass [filter]. Each call to `iterator()` is one pass. */
    fun range(from: Int, to: Int, filter: Predicate<in T>?): Iterable<T> = Iterable { Range(this, from, to, filter) }

    /** Entities of the previous scan, or of every slot this scan has not seen yet (each counted as 0). */
    fun weight(): Long {
        var total = 0L
        for (i in 0 until size) total += weights[i]
        return total
    }

    /**
     * Range bounds `[0 = b0, b1, …, bn = size]` cutting the slots into at most [parts] ranges of about equal cost, where a
     * column costs [columnCost] plus its previous entity count. A column is never split, so one crowded column may
     * still make its range the longest.
     */
    fun split(parts: Int, columnCost: Int): IntArray {
        if (parts <= 1 || size <= 1) return intArrayOf(0, size)
        val total = weight() + size.toLong() * columnCost
        val bounds = IntArray(minOf(parts, size) + 1)
        var part = 1
        var cost = 0L
        for (i in 0 until size) {
            cost += weights[i] + columnCost
            // Close a range once the running cost reaches the next equal share, keeping one slot per later range.
            if (part < bounds.size - 1 && cost * (bounds.size - 1) >= total * part && size - (i + 1) >= bounds.size - 1 - part) {
                bounds[part++] = i + 1
            }
        }
        bounds[part] = size
        return if (part == bounds.size - 1) bounds else bounds.copyOf(part + 1)
    }

    /** Iterates column by column, then section by section; records each finished column's entity count. */
    @Suppress("UNCHECKED_CAST")
    internal class Range<T : EntityAccess>(
        scan: ColumnScan<T>,
        private var slot: Int,
        private val to: Int,
        private val filter: Predicate<in T>?,
    ) : Iterator<T> {
        private val slots = scan.slots
        private val weights = scan.weights
        private var sections: Array<EntitySection<T>>? = null
        private var section = 0
        private var entities: Iterator<T>? = null
        /** Entities seen so far in the current column. */
        private var seen = 0
        private var next: T? = null

        override fun hasNext(): Boolean {
            if (next == null) next = advance()
            return next != null
        }

        override fun next(): T {
            val entity = next ?: advance() ?: throw NoSuchElementException()
            next = null
            return entity
        }

        private fun advance(): T? {
            while (true) {
                val current = entities
                if (current != null && current.hasNext()) {
                    val entity = current.next()
                    seen++
                    if (filter == null || filter.test(entity)) return entity
                    continue
                }
                entities = null
                val columnSections = sections
                if (columnSections != null) {
                    if (section < columnSections.size) {
                        val next = columnSections[section++]
                        if (!next.isEmpty && next.status.isAccessible) entities = next.storage.iterator()
                        continue
                    }
                    // Column finished: its slot is the one before [slot].
                    weights[slot - 1] = seen
                    sections = null
                }
                if (slot >= to) return null
                // Null only for a scan that raced a removal (writers not quiescent): nothing to visit there.
                sections = slots[slot++]?.sections ?: NO_SECTIONS as Array<EntitySection<T>>
                section = 0
                seen = 0
            }
        }

        private companion object {
            val NO_SECTIONS = arrayOfNulls<EntitySection<*>>(0)
        }
    }
}
