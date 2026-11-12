package com.synsenetwork.vanadium.entity

import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntitySection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongFunction

/**
 * Which sections of one entity section storage are indexed by a [CrowdedSection]. Sections reaching
 * [THRESHOLD] entities queue themselves on add; [refresh] builds their grids. It runs on the server thread
 * after the level's ticks (`PersistentEntitySectionManager.tick`), when no box is being written: a grid built
 * while an entity wrote its box unprotected by the grid could miss that write. A grid is dropped, from any
 * thread, when its section falls below half the threshold, and at [refresh] when indexing is disabled, its
 * section left the storage, or a write ran unprotected (it is rebuilt then).
 *
 * Storages nobody refreshes (the client's) never queue anything.
 */
class CrowdedSections<T : EntityAccess> {
    @Volatile
    private var armed = false
    private val queue = ConcurrentLinkedQueue<CrowdSlot<T>>()

    /** Server thread only. */
    private val indexed = ArrayList<CrowdSlot<T>>()

    fun slot(section: EntitySection<T>, key: Long): CrowdSlot<T> = CrowdSlot(section, key, this)

    internal fun offer(slot: CrowdSlot<T>) {
        if (armed && slot.grid == null && slot.section.size() >= THRESHOLD && slot.queued.compareAndSet(false, true)) {
            queue.add(slot)
        }
    }

    /** Sections indexed right now; for diagnostics and checks (server thread). */
    fun indexedCount(): Int = indexed.count { it.grid != null }

    /**
     * Server thread, with no box writes in flight. [sections] looks a key up in the storage, to skip sections
     * that were removed from it.
     */
    fun refresh(enabled: Boolean, sections: LongFunction<EntitySection<T>?>) {
        armed = enabled
        val epoch = CrowdedSection.unprotectedWrites.asLong
        var kept = 0
        for (i in indexed.indices) {
            val slot = indexed[i]
            val grid = slot.grid ?: continue
            val live = enabled && sections.apply(slot.key) === slot.section
            if (!live || grid.epoch != epoch) {
                slot.drop(grid)
                if (!live || !build(slot, epoch)) continue
            }
            indexed[kept++] = slot
        }
        while (indexed.size > kept) indexed.removeLast()
        while (true) {
            val slot = queue.poll() ?: break
            slot.queued.set(false)
            if (enabled && slot.grid == null && sections.apply(slot.key) === slot.section && build(slot, epoch)) indexed.add(slot)
        }
    }

    private fun build(slot: CrowdSlot<T>, epoch: Long): Boolean {
        val section = slot.section
        if (section.size() < THRESHOLD) return false
        val grid = CrowdedSection<T>(slot.key, epoch)
        synchronized(grid) {
            val entities = section.entities.iterator()
            while (entities.hasNext()) grid.addLocked(entities.next())
        }
        if (grid.dead) return false
        slot.grid = grid
        return true
    }

    companion object {
        /** Entities in a section before its box queries go through cells. */
        @JvmField
        val THRESHOLD: Int = Integer.getInteger("vanadium.crowdedSectionEntities", 24).coerceAtLeast(2)
    }
}

/** One entity section's link to its storage's [CrowdedSections]; created with the section. */
class CrowdSlot<T : EntityAccess> internal constructor(
    @JvmField val section: EntitySection<T>,
    @JvmField val key: Long,
    private val owner: CrowdedSections<T>,
) {
    /** Published at refresh; cleared when dropped. */
    @Volatile
    @JvmField
    var grid: CrowdedSection<T>? = null

    internal val queued = AtomicBoolean()

    /** After an add to the section while it has no grid. */
    fun offer() = owner.offer(this)

    /** After a removal under [grid]'s monitor: drops the grid when the section emptied out. */
    fun removedLocked(grid: CrowdedSection<T>) {
        if (section.size() < CrowdedSections.THRESHOLD / 2) {
            grid.dropLocked()
            if (this.grid === grid) this.grid = null
        }
    }

    internal fun drop(grid: CrowdedSection<T>) {
        synchronized(grid) { grid.dropLocked() }
        if (this.grid === grid) this.grid = null
    }
}
