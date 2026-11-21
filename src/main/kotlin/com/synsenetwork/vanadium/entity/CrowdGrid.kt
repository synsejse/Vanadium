package com.synsenetwork.vanadium.entity

import com.synsenetwork.vanadium.tick.TickThread
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.function.LongSupplier
import kotlin.math.floor
import net.minecraft.core.SectionPos
import net.minecraft.util.AbortableIterationConsumer
import net.minecraft.util.Continuation
import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB

/**
 * A crowded entity section's members binned into cells (2×4×2 blocks: 8×4×8 per section) by the cells
 * their bounding box overlaps, so a small box query reads only the members of the cells it overlaps instead
 * of every entity's box. It only prefilters: each candidate still gets vanilla's test on its live box (and
 * type, and the caller's predicate), so a query returns what vanilla's scan of the section could have.
 *
 * Invariant (no false negatives): whenever no move is deleting old cells, each member is listed in every cell
 * its entity's current box overlaps, clamped to the section. Box coordinates map to cells monotonically, so a
 * box that intersects the query box overlaps a cell the query visits.
 *
 * Per-cell lists are immutable arrays sorted by [Member.order], updated lock-free by CAS on [cells]:
 * - adds and removes run under this monitor together with the section's own list ([addLocked], [removeLocked]);
 * - a box write whose cells change runs lock-free: [beginMove] inserts into the new cells before the box write,
 *   and [endMove] opens [seq], deletes from any vacated cells, and closes [seq]. Inserting into new cells never
 *   causes a false negative, so [seq] is only open across the tiny cell-deletion window (or skipped altogether
 *   when the new range is a superset of the old).
 *
 * Readers have three paths:
 * - Single-cell queries are wait-free: a box contained in one cell cannot suffer the multi-cell jumper race, so
 *   one volatile cell load plus a post-check on [dead] and [unprotectedWrites] suffices with no [seq] check.
 * - Two-cell queries validate both cell arrays under [seq] and merge the two already-sorted arrays inline with
 *   no scratch buffer or sorting.
 * - Larger queries (up to [MAX_QUERY_CELLS]) collect cells into a per-thread [Scratch] buffer (on
 *   [TickThread.crowdScratch]), validate [seq], merge-sort by [Member.order] and deduplicate.
 *
 * The only box writers are the entity's owner and writers inside its `Interactions` scope (`EntityMixin`), so
 * one member's moves never overlap. Writes that ran unprotected after an ownership timeout or cycle break are
 * counted by [unprotectedWrites]: a grid built before the count changed is bypassed and rebuilt.
 */
class CrowdGrid<T : EntityAccess>(sectionKey: Long, @JvmField val epoch: Long) {
    class Member<T : EntityAccess> internal constructor(
        @JvmField val entity: T,
        /** Position in the section's list order. */
        @JvmField val order: Long,
        @JvmField val grid: CrowdGrid<T>,
    ) {
        /** The cells this member is listed in while no move of it is open; written by the member's owner. */
        @Volatile
        @JvmField
        var range: Int = 0

        /** The target range of this member's open move, or [NO_RANGE]. */
        @Volatile
        @JvmField
        internal var target: Int = NO_RANGE

        /** Set under this grid's monitor on removal or drop. */
        @Volatile
        @JvmField
        internal var removed = false
    }

    private val originX = SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey)).toDouble()
    private val originY = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey)).toDouble()
    private val originZ = SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey)).toDouble()

    /** Open cell-deleting writes in the low [OPEN_BITS] bits, completed writes above. */
    @Volatile
    @JvmField
    var seq = 0L

    /** Immutable arrays sorted by [Member.order], updated lock-free per cell via CAS. */
    private val cells = AtomicReferenceArray<Array<Member<T>?>>(CELLS).apply {
        val empty = empty()
        for (i in 0 until CELLS) set(i, empty)
    }

    /** Under this monitor. */
    private val members = Reference2ObjectOpenHashMap<T, Member<T>>()
    private var nextOrder = 0L

    /** Set once, under this monitor; a dead grid keeps a write open in [seq] forever. */
    @Volatile
    var dead = false
        private set

    val size: Int get() = synchronized(this) { members.size }

    /** The packed cells [box] overlaps, clamped to this section. */
    fun rangeOf(box: AABB): Int = pack(
        horizontal(box.minX - originX), horizontal(box.maxX - originX),
        vertical(box.minY - originY), vertical(box.maxY - originY),
        horizontal(box.minZ - originZ), horizontal(box.maxZ - originZ),
    )

    /** Registers [entity], just added to the section's list; the caller holds this monitor. */
    fun addLocked(entity: T) {
        if (dead) return
        val access = entity as? CrowdAccess
        if (access == null || members.containsKey(entity)) {
            // Not trackable, or listed twice (vanilla's list allows it): only vanilla's scan is right.
            dropLocked()
            return
        }
        open()
        try {
            val member = Member(entity, nextOrder++, this)
            val range = rangeOf(entity.boundingBox)
            member.range = range
            forEachCell(range) { insert(it, member) }
            members.put(entity, member)
            access.`vanadium$setCrowd`(member)
        } finally {
            close()
        }
    }

    /** Unregisters [entity], just removed from the section's list; the caller holds this monitor. */
    fun removeLocked(entity: T) {
        if (dead) return
        val member = members.remove(entity) ?: return
        open()
        try {
            member.removed = true
            val target = member.target
            forEachCell(if (target == NO_RANGE) member.range else hull(member.range, target)) { delete(it, member) }
            val access = entity as CrowdAccess
            if (access.`vanadium$crowd`() === member) access.`vanadium$setCrowd`(null)
        } finally {
            close()
        }
    }

    /**
     * Before writing a box whose cells are [range] (not [member]'s current ones): lists the member in the new
     * cells without locking the section or opening [seq]. Returns false when there is nothing to maintain
     * (removed member, dead grid); otherwise the caller writes the box and then calls [endMove].
     */
    fun beginMove(member: Member<T>, range: Int): Boolean {
        if (dead || member.removed) return false
        val current = member.range
        member.target = range
        forEachCell(range) { if (!contains(current, it)) insert(it, member) }
        if (dead || member.removed) {
            member.target = NO_RANGE
            return false
        }
        return true
    }

    /** After the box write that [beginMove] prepared: leaves any vacated cells under a brief [seq] window. */
    fun endMove(member: Member<T>) {
        val target = member.target
        if (target == NO_RANGE) return
        if (dead || member.removed) {
            member.target = NO_RANGE
            return
        }
        val current = member.range
        if (containsRange(target, current)) {
            member.range = target
            member.target = NO_RANGE
            return
        }
        open()
        try {
            forEachCell(current) { if (!contains(target, it)) delete(it, member) }
            member.range = target
            member.target = NO_RANGE
        } finally {
            close()
        }
    }

    /** Stops indexing for good: readers fall back from now on, and members stop maintaining it. */
    fun dropLocked() {
        if (dead) return
        dead = true
        SEQ.getAndAdd(this, 1L)
        for (member in members.values) {
            member.removed = true
            val access = member.entity as CrowdAccess
            if (access.`vanadium$crowd`() === member) access.`vanadium$setCrowd`(null)
        }
        members.clear()
        val empty = empty()
        for (i in 0 until CELLS) cells.set(i, empty)
    }

    /**
     * Vanilla's `EntitySection.getEntities(AABB, consumer)` over the cells [box] overlaps; null when a write
     * overlapped the read or the box covers too much of the section, and the caller must scan the section.
     *
     * Kept small and split across 1-cell, 2-cell and multi-cell helpers so C2 can inline the dispatch and hot paths.
     */
    fun query(box: AABB, output: AbortableIterationConsumer<T>): Continuation? {
        val range = rangeOf(box)
        val x0 = minX(range); val x1 = maxX(range)
        val z0 = minZ(range); val z1 = maxZ(range)
        val y0 = minY(range); val y1 = maxY(range)
        val count = (x1 - x0 + 1) * (z1 - z0 + 1) * (y1 - y0 + 1)
        if (count == 1) {
            val list = snapshotOne(cell(x0, y0, z0)) ?: return null
            return scanCell(list, 0, box, output)
        }
        if (count == 2) return queryTwo(cell(x0, y0, z0), cell(x1, y1, z1), box, output)
        if (count > MAX_QUERY_CELLS) return null
        return queryMany(x0, x1, y0, y1, z0, z1, box, output)
    }

    /** Vanilla's typed `EntitySection.getEntities`, as [query]; the caller has checked the class list is not empty. */
    fun <U : T> query(type: EntityTypeTest<T, U>, box: AABB, output: AbortableIterationConsumer<in U>): Continuation? {
        val range = rangeOf(box)
        val x0 = minX(range); val x1 = maxX(range)
        val z0 = minZ(range); val z1 = maxZ(range)
        val y0 = minY(range); val y1 = maxY(range)
        val count = (x1 - x0 + 1) * (z1 - z0 + 1) * (y1 - y0 + 1)
        if (count == 1) {
            val list = snapshotOne(cell(x0, y0, z0)) ?: return null
            return scanCellTyped(list, 0, type, type.baseClass, box, output)
        }
        if (count == 2) return queryTwoTyped(cell(x0, y0, z0), cell(x1, y1, z1), type, box, output)
        if (count > MAX_QUERY_CELLS) return null
        return queryManyTyped(x0, x1, y0, y1, z0, z1, type, box, output)
    }

    /** Wait-free single-cell snapshot: [dead] is checked after the volatile cell read so a drop never returns an emptied array. */
    private fun snapshotOne(at: Int): Array<Member<T>?>? {
        val list = cells.get(at)
        return if (dead || unprotectedWrites.asLong != epoch) null else list
    }

    private fun scanCell(
        list: Array<Member<T>?>,
        from: Int,
        box: AABB,
        output: AbortableIterationConsumer<T>,
    ): Continuation {
        for (i in from until list.size) {
            val entity = list[i]!!.entity
            if (entity.boundingBox.intersects(box) && output.accept(entity).shouldAbort()) return Continuation.ABORT
        }
        return Continuation.CONTINUE
    }

    private fun <U : T> scanCellTyped(
        list: Array<Member<T>?>,
        from: Int,
        type: EntityTypeTest<T, U>,
        base: Class<out T>,
        box: AABB,
        output: AbortableIterationConsumer<in U>,
    ): Continuation {
        for (i in from until list.size) {
            val entity = list[i]!!.entity
            if (!base.isInstance(entity)) continue
            val cast = type.tryCast(entity)
            if (cast != null && entity.boundingBox.intersects(box) && output.accept(cast).shouldAbort()) {
                return Continuation.ABORT
            }
        }
        return Continuation.CONTINUE
    }

    /** Two-cell query: validates both sorted cell arrays under [seq] and merges inline without a scratch buffer. */
    private fun queryTwo(c0: Int, c1: Int, box: AABB, output: AbortableIterationConsumer<T>): Continuation? {
        if (dead) return null
        var a: Array<Member<T>?>? = null
        var b: Array<Member<T>?>? = null
        for (attempt in 0..RETRIES) {
            val start = seq
            if (start and OPEN_MASK != 0L) {
                Thread.onSpinWait()
                continue
            }
            val ra = cells.get(c0)
            val rb = cells.get(c1)
            if (seq == start) {
                a = ra
                b = rb
                break
            }
        }
        if (a == null || unprotectedWrites.asLong != epoch) return null
        if (a.isEmpty()) return scanCell(b!!, 0, box, output)
        if (b!!.isEmpty()) return scanCell(a, 0, box, output)
        return mergeTwo(a, b, box, output)
    }

    private fun mergeTwo(
        a: Array<Member<T>?>,
        b: Array<Member<T>?>,
        box: AABB,
        output: AbortableIterationConsumer<T>,
    ): Continuation {
        val lenA = a.size
        val lenB = b.size
        var i = 0
        var j = 0
        while (i < lenA && j < lenB) {
            val ma = a[i]!!
            val mb = b[j]!!
            val member: Member<T>
            if (ma.order < mb.order) {
                member = ma; i++
            } else if (mb.order < ma.order) {
                member = mb; j++
            } else {
                member = ma; i++; j++
            }
            val entity = member.entity
            if (entity.boundingBox.intersects(box) && output.accept(entity).shouldAbort()) return Continuation.ABORT
        }
        if (i < lenA) return scanCell(a, i, box, output)
        if (j < lenB) return scanCell(b, j, box, output)
        return Continuation.CONTINUE
    }

    private fun <U : T> queryTwoTyped(
        c0: Int,
        c1: Int,
        type: EntityTypeTest<T, U>,
        box: AABB,
        output: AbortableIterationConsumer<in U>,
    ): Continuation? {
        if (dead) return null
        var a: Array<Member<T>?>? = null
        var b: Array<Member<T>?>? = null
        for (attempt in 0..RETRIES) {
            val start = seq
            if (start and OPEN_MASK != 0L) {
                Thread.onSpinWait()
                continue
            }
            val ra = cells.get(c0)
            val rb = cells.get(c1)
            if (seq == start) {
                a = ra
                b = rb
                break
            }
        }
        if (a == null || unprotectedWrites.asLong != epoch) return null
        val base = type.baseClass
        if (a.isEmpty()) return scanCellTyped(b!!, 0, type, base, box, output)
        if (b!!.isEmpty()) return scanCellTyped(a, 0, type, base, box, output)
        return mergeTwoTyped(a, b, type, base, box, output)
    }

    private fun <U : T> mergeTwoTyped(
        a: Array<Member<T>?>,
        b: Array<Member<T>?>,
        type: EntityTypeTest<T, U>,
        base: Class<out T>,
        box: AABB,
        output: AbortableIterationConsumer<in U>,
    ): Continuation {
        val lenA = a.size
        val lenB = b.size
        var i = 0
        var j = 0
        while (i < lenA && j < lenB) {
            val ma = a[i]!!
            val mb = b[j]!!
            val member: Member<T>
            if (ma.order < mb.order) {
                member = ma; i++
            } else if (mb.order < ma.order) {
                member = mb; j++
            } else {
                member = ma; i++; j++
            }
            val entity = member.entity
            if (base.isInstance(entity)) {
                val cast = type.tryCast(entity)
                if (cast != null && entity.boundingBox.intersects(box) && output.accept(cast).shouldAbort()) {
                    return Continuation.ABORT
                }
            }
        }
        if (i < lenA) return scanCellTyped(a, i, type, base, box, output)
        if (j < lenB) return scanCellTyped(b, j, type, base, box, output)
        return Continuation.CONTINUE
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryMany(
        x0: Int, x1: Int, y0: Int, y1: Int, z0: Int, z1: Int,
        box: AABB, output: AbortableIterationConsumer<T>,
    ): Continuation? {
        if (dead) return null
        val scratch = Scratch.take()
        try {
            if (!collectMany(x0, x1, y0, y1, z0, z1, scratch)) return null
            val found = scratch.items
            val n = scratch.size
            for (i in 0 until n) {
                val entity = (found[i] as Member<T>).entity
                if (entity.boundingBox.intersects(box) && output.accept(entity).shouldAbort()) return Continuation.ABORT
            }
            return Continuation.CONTINUE
        } finally {
            scratch.release()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <U : T> queryManyTyped(
        x0: Int, x1: Int, y0: Int, y1: Int, z0: Int, z1: Int,
        type: EntityTypeTest<T, U>, box: AABB, output: AbortableIterationConsumer<in U>,
    ): Continuation? {
        if (dead) return null
        val scratch = Scratch.take()
        try {
            if (!collectMany(x0, x1, y0, y1, z0, z1, scratch)) return null
            val base = type.baseClass
            val found = scratch.items
            val n = scratch.size
            for (i in 0 until n) {
                val entity = (found[i] as Member<T>).entity
                if (!base.isInstance(entity)) continue
                val cast = type.tryCast(entity)
                if (cast != null && entity.boundingBox.intersects(box) && output.accept(cast).shouldAbort()) {
                    return Continuation.ABORT
                }
            }
            return Continuation.CONTINUE
        } finally {
            scratch.release()
        }
    }

    private fun collectMany(
        x0: Int, x1: Int, y0: Int, y1: Int, z0: Int, z1: Int,
        scratch: Scratch,
    ): Boolean {
        val cells = cells
        var valid = false
        for (attempt in 0..RETRIES) {
            val start = seq
            if (start and OPEN_MASK != 0L) {
                Thread.onSpinWait()
                continue
            }
            for (x in x0..x1) {
                for (z in z0..z1) {
                    for (y in y0..y1) scratch.addAll(cells.get(cell(x, y, z)))
                }
            }
            if (seq == start) {
                valid = true
                break
            }
            scratch.clear()
        }
        if (!valid || unprotectedWrites.asLong != epoch) return false
        scratch.sortAndDedupe()
        return true
    }

    private fun open() {
        SEQ.getAndAdd(this, 1L)
    }

    private fun close() {
        SEQ.getAndAdd(this, VERSION - 1L)
    }

    private fun insert(cell: Int, member: Member<T>) {
        val cells = cells
        val order = member.order
        while (true) {
            val list = cells.get(cell)
            val n = list.size
            var at = n
            while (at > 0) {
                val existing = list[at - 1]!!
                if (existing === member) return
                if (existing.order < order) break
                at--
            }
            val next = arrayOfNulls<Member<T>>(n + 1)
            list.copyInto(next, 0, 0, at)
            next[at] = member
            list.copyInto(next, at + 1, at)
            if (cells.compareAndSet(cell, list, next)) return
        }
    }

    private fun delete(cell: Int, member: Member<T>) {
        val cells = cells
        while (true) {
            val list = cells.get(cell)
            val n = list.size
            var at = -1
            for (i in 0 until n) {
                if (list[i] === member) {
                    at = i
                    break
                }
            }
            if (at < 0) return
            val next = if (n == 1) {
                empty()
            } else {
                val copy = arrayOfNulls<Member<T>>(n - 1)
                list.copyInto(copy, 0, 0, at)
                list.copyInto(copy, at, at + 1)
                copy
            }
            if (cells.compareAndSet(cell, list, next)) return
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun empty(): Array<Member<T>?> = EMPTY as Array<Member<T>?>

    /** Per-thread candidate buffer (stored on [TickThread.crowdScratch]); a nested query gets its own. */
    private class Scratch {
        @JvmField var items = arrayOfNulls<Member<*>>(64)
        private var aux = arrayOfNulls<Member<*>>(64)
        @JvmField var size = 0
        private var busy = false

        fun addAll(list: Array<out Member<*>?>) {
            val len = list.size
            if (len == 0) return
            val needed = size + len
            if (needed > items.size) items = items.copyOf(maxOf(items.size * 2, needed))
            System.arraycopy(list, 0, items, size, len)
            size = needed
        }

        /** By list order; one member may sit in several visited cells. */
        fun sortAndDedupe() {
            val items = items
            val n = size
            if (n > 1) {
                if (n <= INSERTION_LIMIT) {
                    insertionSort(items, 0, n)
                } else {
                    if (aux.size < n) aux = arrayOfNulls(maxOf(aux.size * 2, n))
                    mergeSort(items, aux, 0, n)
                    aux.fill(null, 0, n)
                }
            }
            var kept = 0
            for (i in 0 until n) {
                if (kept == 0 || items[kept - 1] !== items[i]) items[kept++] = items[i]
            }
            for (i in kept until n) items[i] = null
            size = kept
        }

        private fun insertionSort(arr: Array<Member<*>?>, lo: Int, hi: Int) {
            for (i in lo + 1 until hi) {
                val item = arr[i]!!
                val order = item.order
                var j = i - 1
                while (j >= lo && arr[j]!!.order > order) {
                    arr[j + 1] = arr[j]
                    j--
                }
                arr[j + 1] = item
            }
        }

        private fun mergeSort(arr: Array<Member<*>?>, aux: Array<Member<*>?>, lo: Int, hi: Int) {
            val len = hi - lo
            if (len <= INSERTION_LIMIT) {
                insertionSort(arr, lo, hi)
                return
            }
            val mid = (lo + hi) ushr 1
            mergeSort(arr, aux, lo, mid)
            mergeSort(arr, aux, mid, hi)
            if (arr[mid - 1]!!.order <= arr[mid]!!.order) return
            System.arraycopy(arr, lo, aux, lo, len)
            var i = lo
            var j = mid
            var k = lo
            while (i < mid && j < hi) {
                val left = aux[i]!!
                val right = aux[j]!!
                if (left.order <= right.order) {
                    arr[k++] = left
                    i++
                } else {
                    arr[k++] = right
                    j++
                }
            }
            while (i < mid) arr[k++] = aux[i++]
        }

        fun clear() {
            items.fill(null, 0, size)
            size = 0
        }

        fun release() {
            clear()
            busy = false
        }

        companion object {
            private const val INSERTION_LIMIT = 24
            private val LOCAL = ThreadLocal.withInitial(::Scratch)

            fun take(): Scratch {
                val thread = Thread.currentThread()
                val scratch = if (thread is TickThread) {
                    var existing = thread.crowdScratch as Scratch?
                    if (existing == null) {
                        existing = Scratch()
                        thread.crowdScratch = existing
                    }
                    existing
                } else {
                    LOCAL.get()
                }
                if (scratch.busy) return Scratch().also { it.busy = true }
                scratch.busy = true
                return scratch
            }
        }
    }

    companion object {
        /** Count of entity writes that ran without their owner's lock (timeouts and cycle breaks). */
        @JvmField
        @Volatile
        var unprotectedWrites: LongSupplier = LongSupplier { 0L }

        const val NO_RANGE = -1

        /** Extra reads after one that a cell-deleting write overlapped, before falling back to the scan. */
        private const val RETRIES = 3

        /** Queries over more cells than this scan the section instead: the prefilter would not save much. */
        const val MAX_QUERY_CELLS = 64

        private const val CELLS = 8 * 4 * 8
        private const val OPEN_BITS = 24
        private const val OPEN_MASK = (1L shl OPEN_BITS) - 1L
        private const val VERSION = 1L shl OPEN_BITS
        private val EMPTY = arrayOfNulls<Member<*>>(0)
        private val SEQ: VarHandle = MethodHandles.lookup().findVarHandle(CrowdGrid::class.java, "seq", java.lang.Long.TYPE)

        /** 2-block cells; NaN maps to 0 (it intersects nothing anyway). */
        private fun horizontal(offset: Double): Int {
            val cell = floor(offset * 0.5)
            return if (!(cell > 0.0)) 0 else if (cell >= 7.0) 7 else cell.toInt()
        }

        /** 4-block cells. */
        private fun vertical(offset: Double): Int {
            val cell = floor(offset * 0.25)
            return if (!(cell > 0.0)) 0 else if (cell >= 3.0) 3 else cell.toInt()
        }

        /** A range of cells: X and Z bounds in 3 bits each, then Y bounds in 2 bits each. */
        private fun pack(x0: Int, x1: Int, y0: Int, y1: Int, z0: Int, z1: Int): Int =
            x0 or (x1 shl 3) or (z0 shl 6) or (z1 shl 9) or (y0 shl 12) or (y1 shl 14)

        private fun cell(x: Int, y: Int, z: Int): Int = (x * 8 + z) * 4 + y

        private fun contains(range: Int, cell: Int): Boolean {
            val x = cell / 32; val z = (cell / 4) and 7; val y = cell and 3
            return x >= minX(range) && x <= maxX(range) &&
                z >= minZ(range) && z <= maxZ(range) &&
                y >= minY(range) && y <= maxY(range)
        }

        /** True when every cell in [inner] is also in [outer]. */
        private fun containsRange(outer: Int, inner: Int): Boolean =
            minX(outer) <= minX(inner) && maxX(outer) >= maxX(inner) &&
                minY(outer) <= minY(inner) && maxY(outer) >= maxY(inner) &&
                minZ(outer) <= minZ(inner) && maxZ(outer) >= maxZ(inner)

        private fun hull(a: Int, b: Int): Int = pack(
            minOf(minX(a), minX(b)), maxOf(maxX(a), maxX(b)),
            minOf(minY(a), minY(b)), maxOf(maxY(a), maxY(b)),
            minOf(minZ(a), minZ(b)), maxOf(maxZ(a), maxZ(b)),
        )

        private inline fun forEachCell(range: Int, action: (Int) -> Unit) {
            for (x in minX(range)..maxX(range)) {
                for (z in minZ(range)..maxZ(range)) {
                    for (y in minY(range)..maxY(range)) action(cell(x, y, z))
                }
            }
        }
    }
}

// A packed cell range (see CrowdGrid.pack). Inline, so each compiles to its shift and mask rather than a call.
@Suppress("NOTHING_TO_INLINE") private inline fun minX(range: Int): Int = range and 7
@Suppress("NOTHING_TO_INLINE") private inline fun maxX(range: Int): Int = (range ushr 3) and 7
@Suppress("NOTHING_TO_INLINE") private inline fun minZ(range: Int): Int = (range ushr 6) and 7
@Suppress("NOTHING_TO_INLINE") private inline fun maxZ(range: Int): Int = (range ushr 9) and 7
@Suppress("NOTHING_TO_INLINE") private inline fun minY(range: Int): Int = (range ushr 12) and 3
@Suppress("NOTHING_TO_INLINE") private inline fun maxY(range: Int): Int = (range ushr 14) and 3
