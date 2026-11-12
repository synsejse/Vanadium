package com.synsenetwork.vanadium.entity

import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntitySection
import java.util.function.Predicate

/**
 * Entities of the accessible, non-empty sections of one [EntitySectionIndex], for whole-level scans
 * (Lithium's chunk-aware spawn counting). Lazy: nothing is read until [iterator] or [snapshot].
 */
class AccessibleEntities<T : EntityAccess> internal constructor(private val index: EntitySectionIndex<T>) : Iterable<T> {
    /** The sections as they are now, in this iterable's order, for splitting one scan into ranges. */
    fun snapshot(): SectionEntities<T> = SectionEntities(index.accessibleSections())

    override fun iterator(): Iterator<T> = SectionEntities.Range(index.accessibleSections(), null, 0, Int.MAX_VALUE, null)
}

/**
 * A fixed list of sections, with their entity counts as of construction. A [range] of the concatenated
 * entities can be iterated on any thread while the sections do not change (a counting wave runs nothing
 * else); concatenating consecutive ranges visits the same entities in the same order as one full range.
 */
class SectionEntities<T : EntityAccess>(private val sections: List<EntitySection<T>>) {
    /** `starts[i]` is the position of section `i`'s first entity; `starts[sections.size]` is [size]. */
    private val starts = IntArray(sections.size + 1).also { starts ->
        for (i in sections.indices) starts[i + 1] = starts[i] + sections[i].size()
    }

    /** Entities in all sections when this snapshot was taken, before any filter. */
    val size: Int get() = starts[sections.size]

    /** Entities `from` until `to` of the concatenation that pass [filter]. Each call to `iterator()` is one pass. */
    fun range(from: Int, to: Int, filter: Predicate<in T>?): Iterable<T> = Iterable { Range(sections, starts, from, to, filter) }

    /** Iterates section by section; with [starts] null, every entity of every section (no recorded bounds). */
    internal class Range<T : EntityAccess>(
        private val sections: List<EntitySection<T>>,
        private val starts: IntArray?,
        private val from: Int,
        private val to: Int,
        private val filter: Predicate<in T>?,
    ) : Iterator<T> {
        private var section = if (starts == null) 0 else firstSection(starts, from)
        private var entities: Iterator<T>? = null
        /** Entities still to visit in the current section. */
        private var remaining = 0
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
                if (current != null && remaining > 0 && current.hasNext()) {
                    val entity = current.next()
                    remaining--
                    if (filter == null || filter.test(entity)) return entity
                    continue
                }
                if (section >= sections.size) return null
                val iterator = sections[section].storage.iterator()
                if (starts == null) {
                    remaining = Int.MAX_VALUE
                } else {
                    val start = starts[section]
                    if (start >= to) {
                        section = sections.size
                        return null
                    }
                    var skip = from - start
                    while (skip > 0 && iterator.hasNext()) {
                        iterator.next()
                        skip--
                    }
                    remaining = minOf(to, starts[section + 1]) - maxOf(from, start)
                }
                entities = iterator
                section++
            }
        }

        private companion object {
            /** The last section starting at or before [from] (the first of equal starts holding an entity). */
            fun firstSection(starts: IntArray, from: Int): Int {
                var low = 0
                var high = starts.size - 2
                while (low < high) {
                    val mid = (low + high + 1) ushr 1
                    if (starts[mid] <= from) low = mid else high = mid - 1
                }
                return maxOf(low, 0)
            }
        }
    }
}
