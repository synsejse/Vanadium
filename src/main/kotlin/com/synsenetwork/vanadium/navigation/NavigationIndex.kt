package com.synsenetwork.vanadium.navigation

import com.synsenetwork.vanadium.tracking.AreaMap
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.floor
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.pathfinder.Path

/**
 * Conservative candidate index for vanilla's navigation invalidation (`ServerLevel.navigatingMobs`).
 * Dirty navigations remain candidates until a safe tick-boundary [refresh].
 *
 * Invalidation is the hot path (every moving mob, every tick, on any worker) and takes no lock: a mob's
 * first invalidation after a refresh flips its own dirty flag, pushes it onto a thread-striped lock-free
 * queue, and sets that stripe's bit in [dirtyMask]; later invalidations only read that flag.
 *
 * Candidate queries (`sendBlockUpdated` from parallel piston/redstone/block-entity waves) take only the
 * shared read lock of [lock] and scan only stripes marked in [dirtyMask], so concurrent block updates never
 * serialize on each other. Membership and area updates take the write lock.
 */
class NavigationIndex : AbstractMutableSet<Mob>() {
    private val lock = ReentrantReadWriteLock()
    private val members = ReferenceOpenHashSet<Mob>()
    private val unbounded = ReferenceOpenHashSet<Mob>()
    private val areas = AreaMap<Mob>()

    /** Dirty mobs, striped by invalidating thread. May hold departed members; readers filter them. */
    private val dirty = Array(STRIPES) { ConcurrentLinkedQueue<Any>() }
    @Volatile
    internal var dirtyMask = 0L
    private val dirtyCount = LongAdder()

    /** Any invalidation since the last refresh; written only when it changes. */
    @Volatile
    private var touched = false
    private val queriesSinceRefresh = AtomicInteger()

    private fun markDirty(mob: Mob) {
        if (!touched) touched = true
        val flag = mob.state.`vanadium$navigationDirty`()
        if (!flag.get() && flag.compareAndSet(false, true)) {
            dirtyCount.increment()
            val stripeIndex = Thread.currentThread().threadId().toInt() and (STRIPES - 1)
            dirty[stripeIndex].add(mob)
            val bit = 1L shl stripeIndex
            if ((dirtyMask and bit) == 0L) DIRTY_MASK.getAndBitwiseOr(this, bit)
        }
    }

    override fun add(element: Mob): Boolean {
        val added = lock.write {
            if (!members.add(element)) return@write false
            element.state.`vanadium$setNavigationMember`(true)
            true
        }
        if (!added) return false
        markDirty(element)
        return true
    }

    override fun remove(element: Mob): Boolean = lock.write {
        if (!members.remove(element)) return false
        element.state.`vanadium$setNavigationMember`(false)
        unbounded.remove(element)
        areas.remove(element)
        true
    }

    override val size: Int
        get() = lock.read { members.size }

    override fun contains(element: Mob): Boolean = lock.read { element in members }

    /** A snapshot; removal through the iterator removes from the index. */
    override fun iterator(): MutableIterator<Mob> {
        val snapshot = lock.read { members.toList() }.iterator()
        return object : MutableIterator<Mob> {
            private var current: Mob? = null
            override fun hasNext(): Boolean = snapshot.hasNext()
            override fun next(): Mob {
                val mob = snapshot.next()
                current = mob
                return mob
            }
            override fun remove() {
                this@NavigationIndex.remove(current ?: throw IllegalStateException())
                current = null
            }
        }
    }

    fun candidates(pos: BlockPos): List<Mob> {
        if (queriesSinceRefresh.get() < MIN_QUERIES_FOR_REFRESH) queriesSinceRefresh.incrementAndGet()
        val read = lock.readLock()
        read.lock()
        try {
            val nearby = areas.objectsAt(ChunkPos.pack(pos))
            val mask = dirtyMask
            val hasUnbounded = unbounded.isNotEmpty()
            if (mask == 0L && !hasUnbounded) {
                return if (nearby.isEmpty()) emptyList() else ArrayList(nearby)
            }
            val dirtyMobs = if (mask != 0L) dirtyCount.sum() else 0L
            // A full snapshot is cheaper than merging candidate sets that already cover the population.
            if (nearby.size + dirtyMobs + unbounded.size >= members.size) {
                return if (members.isEmpty()) emptyList() else ArrayList(members)
            }
            if (nearby.isEmpty() && !hasUnbounded) {
                // Each dirty mob is queued at most once per refresh cycle (guarded by its dirty flag),
                // so no deduplication set is needed when nearby and unbounded are both empty.
                val result = ArrayList<Mob>(dirtyMobs.toInt().coerceIn(4, members.size.coerceAtLeast(4)))
                collectDirty(mask, result)
                return result
            }
            val candidates = ReferenceOpenHashSet<Mob>(nearby.size + unbounded.size + dirtyMobs.toInt().coerceAtLeast(4))
            candidates.addAll(nearby)
            if (hasUnbounded) candidates.addAll(unbounded)
            if (mask != 0L) collectDirty(mask, candidates)
            return ArrayList(candidates)
        } finally {
            read.unlock()
        }
    }

    private fun collectDirty(mask: Long, out: MutableCollection<Mob>) {
        var remaining = mask
        while (remaining != 0L) {
            val index = java.lang.Long.numberOfTrailingZeros(remaining)
            remaining = remaining and (remaining - 1L)
            for (entry in dirty[index]) {
                if (entry is Mob && entry.state.`vanadium$navigationMember`()) out.add(entry)
            }
        }
    }

    /**
     * Called between world ticks. Never acquire a navigation monitor while holding this index's lock.
     * Only entries queued before the refresh starts are processed; later invalidations stay dirty.
     */
    fun refresh() {
        val recentQueries = queriesSinceRefresh.getAndSet(0)
        val changedAgain = touched
        if (changedAgain) touched = false
        val mask = dirtyMask
        val dirtyMobs = dirtyCount.sum()
        if (dirtyMobs <= 0 && mask == 0L) return
        // Rebuilding most paths costs more than one full scan. Keep their conservative fallback until
        // edits become frequent or the pending paths stop changing.
        if (changedAgain && recentQueries < MIN_QUERIES_FOR_REFRESH && dirtyMobs > size / 2) return

        // Every marker goes in before any stripe drains, so only entries queued before the refresh are
        // processed. An entry added to a skipped empty stripe after this check waits for the next refresh.
        val marker = Any()
        var markedMask = 0L
        var remaining = mask
        while (remaining != 0L) {
            val index = java.lang.Long.numberOfTrailingZeros(remaining)
            remaining = remaining and (remaining - 1L)
            val stripe = dirty[index]
            if (stripe.isEmpty()) {
                clearStripeBitIfEmpty(index, stripe)
                continue
            }
            stripe.add(marker)
            markedMask = markedMask or (1L shl index)
        }
        var toDrain = markedMask
        while (toDrain != 0L) {
            val index = java.lang.Long.numberOfTrailingZeros(toDrain)
            toDrain = toDrain and (toDrain - 1L)
            val stripe = dirty[index]
            while (true) {
                val entry = stripe.poll() ?: break
                if (entry === marker) break
                if (entry is Mob) rebuild(entry) // Anything else is a stale marker from an interrupted refresh.
            }
            if (stripe.isEmpty()) clearStripeBitIfEmpty(index, stripe)
        }
    }

    /** Dekker-paired with [markDirty]: clears bit [index], then restores it if a concurrent add landed first. */
    private fun clearStripeBitIfEmpty(index: Int, stripe: ConcurrentLinkedQueue<Any>) {
        val bit = 1L shl index
        DIRTY_MASK.getAndBitwiseAnd(this, bit.inv())
        if (!stripe.isEmpty()) DIRTY_MASK.getAndBitwiseOr(this, bit)
    }

    private fun rebuild(mob: Mob) {
        val state = mob.state
        // Clear first: an invalidation racing with the snapshot below re-queues the mob.
        state.`vanadium$navigationDirty`().set(false)
        dirtyCount.decrement()
        if (!state.`vanadium$navigationMember`()) return
        val navigation = mob.navigation
        val bounds = synchronized(navigation) {
            if (VANILLA_PREDICATE.get(navigation.javaClass)) bounds(mob, navigation.path) else Unbounded
        }
        lock.write {
            if (mob !in members) return
            unbounded.remove(mob)
            when (bounds) {
                null -> areas.remove(mob)
                Unbounded -> {
                    areas.remove(mob)
                    unbounded.add(mob)
                }
                is Area -> if (mob in areas) {
                    areas.update(mob, bounds.x, bounds.z, bounds.radius)
                } else {
                    areas.add(mob, bounds.x, bounds.z, bounds.radius)
                }
            }
        }
    }

    /** Where a pending path can be invalidated; null when there is no pending path. */
    private sealed interface Bounds

    /** A square of chunks around the path's midpoint, indexed in [areas]. */
    private class Area(val x: Int, val z: Int, val radius: Int) : Bounds

    /** Checked on every invalidation: a custom predicate or a path too long to index. */
    private object Unbounded : Bounds

    companion object {
        private const val MIN_QUERIES_FOR_REFRESH = 2
        private const val MAX_INDEXED_PATH_NODES = 256
        private const val STRIPES = 64
        private val DIRTY_MASK: VarHandle = MethodHandles.lookup()
            .findVarHandle(NavigationIndex::class.java, "dirtyMask", java.lang.Long.TYPE)

        /** Custom `shouldRecomputePath` overrides cannot be bounded and stay in the full fallback. */
        private val VANILLA_PREDICATE = object : ClassValue<Boolean>() {
            override fun computeValue(type: Class<*>): Boolean =
                type.getMethod("shouldRecomputePath", BlockPos::class.java).declaringClass == PathNavigation::class.java
        }

        @JvmStatic
        fun invalidate(entity: Entity) {
            if (entity is Mob && entity.state.`vanadium$navigationMember`()) {
                val level = entity.level() as? ServerLevel ?: return
                (level as NavigationAccess).`vanadium$navigations`().markDirty(entity)
            }
        }

        private val Mob.state: NavigationState get() = this as NavigationState

        private fun bounds(mob: Mob, path: Path?): Bounds? {
            if (path == null || path.isDone || path.nodeCount == 0) return null
            val end = path.endNode ?: return null
            val x = (end.x + mob.x) / 2.0
            val z = (end.z + mob.z) / 2.0
            val remaining = path.nodeCount - path.nextNodeIndex
            // Limit index memory for very long modded paths; the exact vanilla predicate still runs.
            if (remaining > MAX_INDEXED_PATH_NODES || !x.isFinite() || !z.isFinite()) return Unbounded
            return Area(floor(x / 16.0).toInt(), floor(z / 16.0).toInt(), (remaining + 31) / 16)
        }
    }
}
