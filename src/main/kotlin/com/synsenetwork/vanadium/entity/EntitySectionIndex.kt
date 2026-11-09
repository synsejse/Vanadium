package com.synsenetwork.vanadium.entity

import org.jctools.maps.NonBlockingHashMapLong
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import net.minecraft.core.SectionPos
import net.minecraft.util.AbortableIterationConsumer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntitySection
import java.util.function.LongFunction

/**
 * Entity sections of one level, replacing `EntitySectionStorage`'s hash map and AVL tree. Readers
 * (every AI entity query, on every worker) take no lock and allocate nothing; writers serialize.
 *
 * Box queries visit each chunk column in the box once instead of walking the whole sorted strip of
 * sections at each section X. Visit order matches vanilla's sorted section keys: X ascending, then Z and
 * Y each in the unsigned order of their packed bits (non-negative ascending, then negative ascending).
 */
class EntitySectionIndex<T : EntityAccess> {
    private val sections = NonBlockingHashMapLong<EntitySection<T>>()

    /** Chunk key to the Y coordinates of its sections, sorted by packed Y bits; replaced on change. */
    private val columns = NonBlockingHashMapLong<IntArray>()

    operator fun get(key: Long): EntitySection<T>? = sections[key]

    /** The section at [key], creating it with [create] (which must not touch this index) if absent. */
    fun getOrCreate(key: Long, create: LongFunction<EntitySection<T>>): EntitySection<T> =
        sections[key] ?: synchronized(this) {
            sections[key] ?: create.apply(key).also {
                sections.put(key, it)
                addY(chunkKey(key), SectionPos.y(key))
            }
        }

    fun remove(key: Long) {
        synchronized(this) {
            if (sections.remove(key) != null) removeY(chunkKey(key), SectionPos.y(key))
        }
    }

    fun size(): Int = sections.size

    /** Accessible, non-empty sections overlapping the section-coordinate box, in vanilla order. */
    fun forEachAccessibleNonEmpty(
        xMin: Int, yMin: Int, zMin: Int, xMax: Int, yMax: Int, zMax: Int,
        output: AbortableIterationConsumer<EntitySection<T>>,
    ) {
        for (x in xMin..xMax) {
            // Z in unsigned packed order: the non-negative part of the range first.
            if (zMin < 0 && zMax >= 0) {
                for (z in 0..zMax) if (visitColumn(x, z, yMin, yMax, output)) return
                for (z in zMin..-1) if (visitColumn(x, z, yMin, yMax, output)) return
            } else {
                for (z in zMin..zMax) if (visitColumn(x, z, yMin, yMax, output)) return
            }
        }
    }

    /** Returns true when [output] asked to abort. */
    private fun visitColumn(x: Int, z: Int, yMin: Int, yMax: Int, output: AbortableIterationConsumer<EntitySection<T>>): Boolean {
        val ys = columns[ChunkPos.pack(x, z)] ?: return false
        for (y in ys) {
            if (y < yMin || y > yMax) continue
            val section = sections[SectionPos.asLong(x, y, z)] ?: continue
            if (!section.isEmpty && section.status.isAccessible && output.accept(section).shouldAbort()) return true
        }
        return false
    }

    /** Section keys in one chunk column, in vanilla order. */
    fun sectionKeysInChunk(chunkKey: Long): LongArray {
        val ys = columns[chunkKey] ?: return EMPTY
        val x = ChunkPos.getX(chunkKey)
        val z = ChunkPos.getZ(chunkKey)
        return LongArray(ys.size) { SectionPos.asLong(x, ys[it], z) }
    }

    fun chunksWithSections(): LongSet {
        val chunks = LongOpenHashSet()
        val keys = columns.IteratorLong()
        while (keys.hasNext()) chunks.add(keys.nextLong())
        return chunks
    }

    private fun addY(chunk: Long, y: Int) {
        val ys = columns[chunk] ?: IntArray(0)
        var at = 0
        while (at < ys.size && unsigned(ys[at]) < unsigned(y)) at++
        if (at < ys.size && ys[at] == y) return
        val next = IntArray(ys.size + 1)
        ys.copyInto(next, 0, 0, at)
        next[at] = y
        ys.copyInto(next, at + 1, at)
        columns.put(chunk, next)
    }

    private fun removeY(chunk: Long, y: Int) {
        val ys = columns[chunk] ?: return
        val at = ys.indexOf(y)
        if (at < 0) return
        if (ys.size == 1) {
            columns.remove(chunk)
            return
        }
        val next = IntArray(ys.size - 1)
        ys.copyInto(next, 0, 0, at)
        ys.copyInto(next, at, at + 1)
        columns.put(chunk, next)
    }

    private companion object {
        val EMPTY = LongArray(0)

        fun chunkKey(sectionKey: Long): Long = ChunkPos.pack(SectionPos.x(sectionKey), SectionPos.z(sectionKey))

        /** Y's position in vanilla's packed section key order. */
        fun unsigned(y: Int): Int = y and 0xFFFFF
    }
}
