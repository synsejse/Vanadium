package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.longs.AbstractLongSortedSet
import it.unimi.dsi.fastutil.longs.LongBidirectionalIterator
import it.unimi.dsi.fastutil.longs.LongComparator
import it.unimi.dsi.fastutil.longs.LongSortedSet
import java.util.NavigableSet
import java.util.concurrent.ConcurrentSkipListSet

/**
 * Thread-safe [LongSortedSet] over a [ConcurrentSkipListSet], replacing vanilla's entity section index.
 * Range sets are live, bounded views; iterators are weakly consistent and support removal. Section
 * queries only iterate forward and tolerate concurrently removed sections.
 */
class ConcurrentLongSortedSet private constructor(private val back: NavigableSet<Long>) : AbstractLongSortedSet() {
    constructor() : this(ConcurrentSkipListSet())

    override val size: Int get() = back.size

    override fun isEmpty(): Boolean = back.isEmpty()

    override fun add(k: Long): Boolean = back.add(k)

    override fun remove(k: Long): Boolean = back.remove(k)

    override fun contains(k: Long): Boolean = back.contains(k)

    override fun clear() = back.clear()

    override fun firstLong(): Long = back.first()

    override fun lastLong(): Long = back.last()

    /** Natural ascending order, as the backing skip list uses. */
    override fun comparator(): LongComparator? = null

    override fun subSet(fromElement: Long, toElement: Long): LongSortedSet =
        ConcurrentLongSortedSet(back.subSet(fromElement, true, toElement, false))

    override fun headSet(toElement: Long): LongSortedSet = ConcurrentLongSortedSet(back.headSet(toElement, false))

    override fun tailSet(fromElement: Long): LongSortedSet = ConcurrentLongSortedSet(back.tailSet(fromElement, true))

    /** Traverses directly without copying the range. */
    override fun iterator(): LongBidirectionalIterator = SetIterator(back.iterator(), null)

    /** Positions the cursor after [fromElement], matching fastutil's iterator contract. */
    override fun iterator(fromElement: Long): LongBidirectionalIterator =
        SetIterator(forwardFrom(back.higher(fromElement)), back.floor(fromElement))

    private fun forwardFrom(first: Long?): MutableIterator<Long> =
        if (first == null) mutableListOf<Long>().iterator() else back.tailSet(first, true).iterator()

    /** Forward traversal uses the skip-list iterator; reverse traversal repositions it by key. */
    private inner class SetIterator(private var forward: MutableIterator<Long>, private var previous: Long?) :
        LongBidirectionalIterator {
        private var lastReturned: Long? = null
        private var movedForward = false

        override fun hasNext(): Boolean = forward.hasNext()

        override fun nextLong(): Long {
            // Keep the skip list's own box: unboxing into a primitive local would re-box it twice
            // below, and this iterator runs for every entity section query.
            val next: Long? = forward.next()
            lastReturned = next
            previous = next
            movedForward = true
            return next!!
        }

        override fun hasPrevious(): Boolean = previous != null

        override fun previousLong(): Long {
            val current: Long? = previous ?: throw NoSuchElementException()
            lastReturned = current
            previous = back.lower(current)
            forward = forwardFrom(current)
            movedForward = false
            return current!!
        }

        override fun remove() {
            val current: Long? = lastReturned ?: throw IllegalStateException()
            if (movedForward) {
                forward.remove()
                previous = back.lower(current)
            } else {
                back.remove(current)
                forward = forwardFrom(back.higher(current))
            }
            lastReturned = null
        }
    }
}
