package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.longs.LongCollection
import it.unimi.dsi.fastutil.longs.LongComparator
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet
import it.unimi.dsi.fastutil.longs.LongListIterator
import it.unimi.dsi.fastutil.longs.LongSortedSet
import it.unimi.dsi.fastutil.longs.LongSpliterator
import it.unimi.dsi.fastutil.longs.LongSpliterators
import java.util.Spliterator
import java.util.concurrent.ConcurrentSkipListSet
import java.util.function.LongConsumer

/**
 * Thread-safe stand-in for vanilla fields typed [LongLinkedOpenHashSet], backed by a
 * [ConcurrentSkipListSet]. The superclass storage is never used, so every method that would read it is
 * overridden (including `forEach` and `spliterator`). Iteration is ascending, not insertion order;
 * `removeFirstLong` removes the smallest key. Range views and positional iterators are unsupported.
 */
class ConcurrentLongLinkedOpenHashSet : LongLinkedOpenHashSet() {
    private val backing = ConcurrentSkipListSet<Long>()

    override fun add(k: Long): Boolean = backing.add(k)

    override fun addAll(c: LongCollection): Boolean = backing.addAll(c)

    override fun addAll(c: Collection<Long>): Boolean = backing.addAll(c)

    override fun addAndMoveToFirst(k: Long): Boolean = backing.add(k)

    override fun addAndMoveToLast(k: Long): Boolean = backing.add(k)

    override fun remove(k: Long): Boolean = backing.remove(k)

    override fun contains(k: Long): Boolean = backing.contains(k)

    override fun removeFirstLong(): Long = backing.pollFirst() ?: throw NoSuchElementException()

    override fun removeLastLong(): Long = backing.pollLast() ?: throw NoSuchElementException()

    override fun firstLong(): Long = backing.first()

    override fun lastLong(): Long = backing.last()

    override fun clear() = backing.clear()

    override val size: Int get() = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun iterator(): LongListIterator = object : LongListIterator {
        private val source = backing.iterator()
        override fun hasNext(): Boolean = source.hasNext()
        override fun nextLong(): Long = source.next()
        override fun remove() = source.remove()
        override fun hasPrevious(): Boolean = false
        override fun previousLong(): Long = throw UnsupportedOperationException()
        override fun nextIndex(): Int = throw UnsupportedOperationException()
        override fun previousIndex(): Int = throw UnsupportedOperationException()
    }

    override fun spliterator(): LongSpliterator = LongSpliterators.asSpliterator(iterator(), backing.size.toLong(),
        Spliterator.DISTINCT or Spliterator.ORDERED or Spliterator.NONNULL)

    override fun forEach(action: LongConsumer) = backing.forEach { action.accept(it) }

    override fun toLongArray(): LongArray = backing.toLongArray()

    override fun toArray(a: LongArray): LongArray {
        val values = toLongArray()
        if (a.size < values.size) return values
        values.copyInto(a)
        return a
    }

    override fun hashCode(): Int = backing.hashCode()

    /** Natural ascending order, as the backing skip list uses. */
    override fun comparator(): LongComparator? = null

    override fun clone(): LongLinkedOpenHashSet = ConcurrentLongLinkedOpenHashSet().also { it.backing.addAll(backing) }

    override fun ensureCapacity(capacity: Int) = Unit

    override fun trim(): Boolean = true

    override fun trim(n: Int): Boolean = true

    override fun iterator(from: Long): LongListIterator = throw UnsupportedOperationException("positional iteration")

    override fun headSet(to: Long): LongSortedSet = throw UnsupportedOperationException("range views")

    override fun tailSet(from: Long): LongSortedSet = throw UnsupportedOperationException("range views")

    override fun subSet(from: Long, to: Long): LongSortedSet = throw UnsupportedOperationException("range views")
}
