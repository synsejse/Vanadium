package com.synsenetwork.vanadium.entity

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import net.minecraft.core.SectionPos
import net.minecraft.util.AbortableIterationConsumer
import net.minecraft.util.Continuation
import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.function.LongSupplier

/**
 * A crowded entity section's members binned into cells (2×4×2 blocks: 8×4×8 per section) by the cells
 * their bounding box overlaps, so a small box query reads only the members of the cells it overlaps instead
 * of every entity's box. It only prefilters: each candidate still gets vanilla's test on its live box (and
 * type, and the caller's predicate), so a query returns what vanilla's scan of the section could have.
 *
 * Invariant (no false negatives): whenever no write is open, each member is listed in exactly the cells of
 * its entity's current box, clamped to the section. Box coordinates map to cells monotonically, so a box
 * that intersects the query box overlaps a cell the query visits.
 *
 * Every change opens a write in [seq] (low bits count open writes) and closes it with a version step:
 * - adds and removes, under this monitor, together with the section's own list ([addLocked], [removeLocked]);
 * - a box write whose cells change ([beginMove]: list in the new cells; the box field is written; [endMove]:
 *   leave the old ones). The write stays open across the box write, so no reader can validate while the
 *   box and the cells disagree. A box write whose cells stay the same changes nothing here and takes no lock.
 *
 * A reader takes a snapshot of [seq], collects the visited cells' (immutable) arrays, and rechecks [seq]: if
 * any write was open or closed meanwhile it returns null and the caller scans the section as vanilla does.
 * Then it tests candidates in the section list's order (members are numbered in add order, which is the
 * list's order), each once. Members of one section see the same membership at one instant, as vanilla's
 * copy-on-write list iteration does.
 *
 * The only box writers are the entity's owner and writers inside its `Interactions` scope (EntityMixin), so
 * one member's moves never overlap. Writes that ran unprotected after an ownership timeout or cycle break are
 * counted by [unprotectedWrites]: a grid built before the count changed is bypassed and rebuilt.
 */
class CrowdedSection<T : EntityAccess>(sectionKey: Long, @JvmField val epoch: Long) {
    class Member<T : EntityAccess> internal constructor(
        @JvmField val entity: T,
        /** Position in the section's list order. */
        @JvmField val order: Long,
        @JvmField val grid: CrowdedSection<T>,
    ) {
        /** The cells this member is listed in while no move of it is open; written under the grid's monitor. */
        @Volatile
        @JvmField
        var range: Int = 0

        /** The target range of this member's open move, or [NO_RANGE]; under the grid's monitor. */
        @JvmField
        internal var target: Int = NO_RANGE

        /** Under the grid's monitor. */
        @JvmField
        internal var removed = false
    }

    private val originX = SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey)).toDouble()
    private val originY = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey)).toDouble()
    private val originZ = SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey)).toDouble()

    /** Open writes in the low [OPEN_BITS] bits, completed writes above. */
    @Volatile
    @JvmField
    var seq = 0L

    /** Immutable arrays sorted by [Member.order], replaced under this monitor while a write is open. */
    private val cells: Array<Array<Member<T>?>> = Array(CELLS) { empty() }

    /** Under this monitor. */
    private val members = Reference2ObjectOpenHashMap<T, Member<T>>()
    private var nextOrder = 0L

    /** Set once, under this monitor; a dead grid keeps a write open forever. */
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
        val member = Member(entity, nextOrder++, this)
        val range = rangeOf(entity.boundingBox)
        member.range = range
        forEachCell(range) { insert(it, member) }
        members.put(entity, member)
        access.`vanadium$setCrowd`(member)
        close()
    }

    /** Unregisters [entity], just removed from the section's list; the caller holds this monitor. */
    fun removeLocked(entity: T) {
        if (dead) return
        val member = members.remove(entity) ?: return
        open()
        member.removed = true
        val target = member.target
        forEachCell(if (target == NO_RANGE) member.range else hull(member.range, target)) { delete(it, member) }
        val access = entity as CrowdAccess
        if (access.`vanadium$crowd`() === member) access.`vanadium$setCrowd`(null)
        close()
    }

    /**
     * Before writing a box whose cells are [range] (not [member]'s current ones): lists the member in the new
     * cells and opens a write. Returns false when there is nothing to maintain (removed member, dead grid);
     * otherwise the caller writes the box and then calls [endMove].
     */
    fun beginMove(member: Member<T>, range: Int): Boolean = synchronized(this) {
        if (dead || member.removed) return false
        open()
        val current = member.range
        forEachCell(range) { if (!contains(current, it)) insert(it, member) }
        member.target = range
        true
    }

    /** After the box write that [beginMove] opened: leaves the old cells and closes the write. */
    fun endMove(member: Member<T>) {
        synchronized(this) {
            val target = member.target
            if (!dead && !member.removed && target != NO_RANGE) {
                forEachCell(member.range) { if (!contains(target, it)) delete(it, member) }
                member.range = target
            }
            member.target = NO_RANGE
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
        cells.fill(empty())
    }

    /**
     * Vanilla's `EntitySection.getEntities(AABB, consumer)` over the cells [box] overlaps; null when a write
     * overlapped the read or the box covers too much of the section, and the caller must scan the section.
     */
    fun query(box: AABB, output: AbortableIterationConsumer<T>): Continuation? =
        candidates(box) { entity -> entity.boundingBox.intersects(box) && output.accept(entity).shouldAbort() }

    /** Vanilla's typed `EntitySection.getEntities`, as [query]; the caller has checked the class list is not empty. */
    fun <U : T> query(type: EntityTypeTest<T, U>, box: AABB, output: AbortableIterationConsumer<in U>): Continuation? {
        val base = type.baseClass
        return candidates(box) { entity ->
            if (!base.isInstance(entity)) return@candidates false
            val cast = type.tryCast(entity)
            cast != null && entity.boundingBox.intersects(box) && output.accept(cast).shouldAbort()
        }
    }

    /**
     * Calls [visit] for each candidate in list order until it returns true (abort). A read that a write
     * overlapped is retried once (a scan of a crowded section costs far more than a read of its cells).
     */
    private inline fun candidates(box: AABB, visit: (T) -> Boolean): Continuation? {
        val range = rangeOf(box)
        val x0 = range and 7; val x1 = (range ushr 3) and 7
        val z0 = (range ushr 6) and 7; val z1 = (range ushr 9) and 7
        val y0 = (range ushr 12) and 3; val y1 = (range ushr 14) and 3
        val count = (x1 - x0 + 1) * (z1 - z0 + 1) * (y1 - y0 + 1)
        if (count > MAX_QUERY_CELLS) return null
        val cells = cells
        if (count == 1) {
            val at = cell(x0, y0, z0)
            var list: Array<Member<T>?>? = null
            for (attempt in 0..RETRIES) {
                val start = seq
                if (start and OPEN_MASK != 0L) {
                    Thread.onSpinWait()
                    continue
                }
                val read = cells[at]
                VarHandle.acquireFence()
                if (seq == start) {
                    list = read
                    break
                }
            }
            if (list == null || unprotectedWrites.asLong != epoch) return null
            for (member in list) {
                if (member != null && visit(member.entity)) return Continuation.ABORT
            }
            return Continuation.CONTINUE
        }
        val scratch = Scratch.take()
        try {
            var valid = false
            for (attempt in 0..RETRIES) {
                val start = seq
                if (start and OPEN_MASK != 0L) {
                    Thread.onSpinWait()
                    continue
                }
                for (x in x0..x1) for (z in z0..z1) for (y in y0..y1) scratch.addAll(cells[cell(x, y, z)])
                VarHandle.acquireFence()
                if (seq == start) {
                    valid = true
                    break
                }
                scratch.clear()
            }
            if (!valid || unprotectedWrites.asLong != epoch) return null
            scratch.sortAndDedupe()
            val found = scratch.items
            for (i in 0 until scratch.size) {
                @Suppress("UNCHECKED_CAST")
                if (visit((found[i] as Member<T>).entity)) return Continuation.ABORT
            }
            return Continuation.CONTINUE
        } finally {
            scratch.release()
        }
    }

    private fun open() {
        SEQ.getAndAdd(this, 1L)
    }

    private fun close() {
        SEQ.getAndAdd(this, VERSION - 1L)
    }

    private fun insert(cell: Int, member: Member<T>) {
        val list = cells[cell]
        var at = list.size
        while (at > 0 && list[at - 1]!!.order > member.order) at--
        val next = arrayOfNulls<Member<T>>(list.size + 1)
        list.copyInto(next, 0, 0, at)
        next[at] = member
        list.copyInto(next, at + 1, at)
        cells[cell] = next
    }

    private fun delete(cell: Int, member: Member<T>) {
        val list = cells[cell]
        val at = list.indexOfFirst { it === member }
        if (at < 0) return
        if (list.size == 1) {
            cells[cell] = empty()
            return
        }
        val next = arrayOfNulls<Member<T>>(list.size - 1)
        list.copyInto(next, 0, 0, at)
        list.copyInto(next, at, at + 1)
        cells[cell] = next
    }

    @Suppress("UNCHECKED_CAST")
    private fun empty(): Array<Member<T>?> = EMPTY as Array<Member<T>?>

    /** Per-thread candidate buffer; a nested query (from a consumer) gets its own. */
    private class Scratch {
        var items = arrayOfNulls<Member<*>>(64)
        var size = 0
        var busy = false

        fun addAll(list: Array<out Member<*>?>) {
            if (list.isEmpty()) return
            if (size + list.size > items.size) items = items.copyOf(maxOf(items.size * 2, size + list.size))
            for (member in list) if (member != null) items[size++] = member
        }

        /** By list order; one member may sit in several visited cells. */
        fun sortAndDedupe() {
            val items = items
            val n = size
            if (n > 1) {
                if (n <= 32) {
                    for (i in 1 until n) {
                        val item = items[i]!!
                        var j = i - 1
                        while (j >= 0 && items[j]!!.order > item.order) {
                            items[j + 1] = items[j]
                            j--
                        }
                        items[j + 1] = item
                    }
                } else {
                    java.util.Arrays.sort(items, 0, n, BY_ORDER)
                }
            }
            var kept = 0
            for (i in 0 until n) {
                if (kept == 0 || items[kept - 1] !== items[i]) items[kept++] = items[i]
            }
            for (i in kept until n) items[i] = null
            size = kept
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
            private val LOCAL = ThreadLocal.withInitial(::Scratch)
            private val BY_ORDER = Comparator<Member<*>?> { a, b -> a!!.order.compareTo(b!!.order) }

            fun take(): Scratch {
                val scratch = LOCAL.get()
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

        /** Extra reads after one that a write overlapped, before falling back to the scan. */
        private const val RETRIES = 1

        /** Queries over more cells than this scan the section instead: the prefilter would not save much. */
        const val MAX_QUERY_CELLS = 64

        private const val CELLS = 8 * 4 * 8
        private const val OPEN_BITS = 24
        private const val OPEN_MASK = (1L shl OPEN_BITS) - 1L
        private const val VERSION = 1L shl OPEN_BITS
        private val EMPTY = arrayOfNulls<Member<*>>(0)
        private val SEQ: VarHandle = MethodHandles.lookup().findVarHandle(CrowdedSection::class.java, "seq", java.lang.Long.TYPE)

        /** 2-block cells; NaN maps to 0 (it intersects nothing anyway). */
        private fun horizontal(offset: Double): Int {
            val cell = Math.floor(offset * 0.5)
            return if (!(cell > 0.0)) 0 else if (cell >= 7.0) 7 else cell.toInt()
        }

        /** 4-block cells. */
        private fun vertical(offset: Double): Int {
            val cell = Math.floor(offset * 0.25)
            return if (!(cell > 0.0)) 0 else if (cell >= 3.0) 3 else cell.toInt()
        }

        private fun pack(x0: Int, x1: Int, y0: Int, y1: Int, z0: Int, z1: Int): Int =
            x0 or (x1 shl 3) or (z0 shl 6) or (z1 shl 9) or (y0 shl 12) or (y1 shl 14)

        private fun cell(x: Int, y: Int, z: Int): Int = (x * 8 + z) * 4 + y

        private fun contains(range: Int, cell: Int): Boolean {
            val x = cell / 32; val z = (cell / 4) and 7; val y = cell and 3
            return x >= (range and 7) && x <= ((range ushr 3) and 7) &&
                z >= ((range ushr 6) and 7) && z <= ((range ushr 9) and 7) &&
                y >= ((range ushr 12) and 3) && y <= ((range ushr 14) and 3)
        }

        private fun hull(a: Int, b: Int): Int = pack(
            minOf(a and 7, b and 7), maxOf((a ushr 3) and 7, (b ushr 3) and 7),
            minOf((a ushr 12) and 3, (b ushr 12) and 3), maxOf((a ushr 14) and 3, (b ushr 14) and 3),
            minOf((a ushr 6) and 7, (b ushr 6) and 7), maxOf((a ushr 9) and 7, (b ushr 9) and 7),
        )

        private inline fun forEachCell(range: Int, action: (Int) -> Unit) {
            for (x in (range and 7)..((range ushr 3) and 7)) {
                for (z in ((range ushr 6) and 7)..((range ushr 9) and 7)) {
                    for (y in ((range ushr 12) and 3)..((range ushr 14) and 3)) action(cell(x, y, z))
                }
            }
        }
    }
}
