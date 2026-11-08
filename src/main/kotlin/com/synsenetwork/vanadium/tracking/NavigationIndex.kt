package com.synsenetwork.vanadium.tracking

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.pathfinder.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.LongAdder
import kotlin.math.floor

/**
 * Conservative candidate index for vanilla's navigation invalidation (`ServerLevel.navigatingMobs`).
 * Dirty navigations remain candidates until a safe tick-boundary [refresh].
 *
 * Invalidation is the hot path (every moving mob, every tick, on any worker) and takes no lock: a mob's
 * first invalidation after a refresh flips its own dirty flag and pushes it onto a thread-striped
 * lock-free queue; later invalidations only read that flag. The index monitor guards membership, area
 * painting and candidate snapshots, which are comparatively rare.
 */
class NavigationIndex : AbstractMutableSet<Mob>() {
    private val members = ReferenceOpenHashSet<Mob>()
    private val unbounded = ReferenceOpenHashSet<Mob>()
    private val areas = AreaMap<Mob>()

    /** Dirty mobs, striped by invalidating thread. May hold departed members; readers filter them. */
    private val dirty = Array(STRIPES) { ConcurrentLinkedQueue<Any>() }
    private val dirtyCount = LongAdder()

    /** Any invalidation since the last refresh; written only when it changes. */
    @Volatile
    private var touched = false
    private var queriesSinceRefresh = 0

    private fun markDirty(mob: Mob) {
        if (!touched) touched = true
        val flag = mob.state.`vanadium$navigationDirty`()
        if (!flag.get() && flag.compareAndSet(false, true)) {
            dirtyCount.increment()
            dirty[Thread.currentThread().threadId().toInt() and (STRIPES - 1)].add(mob)
        }
    }

    override fun add(element: Mob): Boolean {
        synchronized(this) {
            if (!members.add(element)) return false
            element.state.`vanadium$setNavigationMember`(true)
        }
        markDirty(element)
        return true
    }

    @Synchronized
    override fun remove(element: Mob): Boolean {
        if (!members.remove(element)) return false
        element.state.`vanadium$setNavigationMember`(false)
        unbounded.remove(element)
        areas.remove(element)
        return true
    }

    override val size: Int
        @Synchronized get() = members.size

    @Synchronized
    override fun contains(element: Mob): Boolean = element in members

    /** A snapshot; removal through the iterator removes from the index. */
    @Synchronized
    override fun iterator(): MutableIterator<Mob> = object : MutableIterator<Mob> {
        private val snapshot = members.toList().iterator()
        private var current: Mob? = null
        override fun hasNext(): Boolean = snapshot.hasNext()
        override fun next(): Mob = snapshot.next().also { current = it }
        override fun remove() {
            remove(current ?: throw IllegalStateException())
            current = null
        }
    }

    fun candidates(pos: BlockPos): List<Mob> {
        val dirtyMobs = dirtyCount.sum()
        synchronized(this) {
            if (queriesSinceRefresh < MIN_QUERIES_FOR_REFRESH) queriesSinceRefresh++
            val nearby = areas.objectsAt(ChunkPos.pack(pos))
            if (dirtyMobs <= 0 && unbounded.isEmpty()) return ArrayList(nearby)
            // A full snapshot is cheaper than merging candidate sets that already cover the population.
            if (nearby.size + dirtyMobs + unbounded.size >= members.size) return ArrayList(members)
            val candidates = ReferenceOpenHashSet(nearby)
            candidates.addAll(unbounded)
            for (stripe in dirty) {
                for (entry in stripe) {
                    if (entry is Mob && entry.state.`vanadium$navigationMember`()) candidates.add(entry)
                }
            }
            return ArrayList(candidates)
        }
    }

    /**
     * Called between world ticks. Never acquire a navigation monitor while holding this index's lock.
     * Only entries queued before the refresh starts are processed; later invalidations stay dirty.
     */
    fun refresh() {
        val recentQueries = synchronized(this) { queriesSinceRefresh.also { queriesSinceRefresh = 0 } }
        val changedAgain = touched
        if (changedAgain) touched = false
        val dirtyMobs = dirtyCount.sum()
        if (dirtyMobs <= 0) return
        // Rebuilding most paths costs more than one full scan. Keep their conservative fallback until
        // edits become frequent or the pending paths stop changing.
        if (changedAgain && recentQueries < MIN_QUERIES_FOR_REFRESH && dirtyMobs > size / 2) return

        // An entry added to a skipped empty stripe after this check waits for the next refresh.
        val marker = Any()
        val marked = dirty.filter { it.isNotEmpty() }.onEach { it.add(marker) }
        for (stripe in marked) {
            while (true) {
                val entry = stripe.poll() ?: break
                if (entry === marker) break
                if (entry is Mob) rebuild(entry) // Anything else is a stale marker from an interrupted refresh.
            }
        }
    }

    private fun rebuild(mob: Mob) {
        val state = mob.state
        // Clear first: an invalidation racing with the snapshot below re-queues the mob.
        state.`vanadium$navigationDirty`().set(false)
        dirtyCount.decrement()
        if (!state.`vanadium$navigationMember`()) return
        val navigation = mob.navigation
        val bounds = synchronized(navigation) {
            if (VANILLA_PREDICATE.get(navigation.javaClass)) bounds(mob, navigation.path) else Bounds.UNBOUNDED
        }
        synchronized(this) {
            if (mob !in members) return
            unbounded.remove(mob)
            when {
                bounds == null -> areas.remove(mob)
                bounds === Bounds.UNBOUNDED -> {
                    areas.remove(mob)
                    unbounded.add(mob)
                }
                mob in areas -> areas.update(mob, bounds.x, bounds.z, bounds.radius)
                else -> areas.add(mob, bounds.x, bounds.z, bounds.radius)
            }
        }
    }

    private class Bounds(val x: Int, val z: Int, val radius: Int) {
        companion object {
            val UNBOUNDED = Bounds(0, 0, -1)
        }
    }

    companion object {
        private const val MIN_QUERIES_FOR_REFRESH = 2
        private const val MAX_INDEXED_PATH_NODES = 256
        private const val STRIPES = 64

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
            if (remaining > MAX_INDEXED_PATH_NODES || !x.isFinite() || !z.isFinite()) return Bounds.UNBOUNDED
            return Bounds(floor(x / 16.0).toInt(), floor(z / 16.0).toInt(), (remaining + 31) / 16)
        }
    }
}
