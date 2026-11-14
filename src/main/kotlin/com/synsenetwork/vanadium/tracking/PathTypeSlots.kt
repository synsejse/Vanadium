package com.synsenetwork.vanadium.tracking

import it.unimi.dsi.fastutil.HashCommon
import net.minecraft.world.level.pathfinder.PathType
import java.util.concurrent.atomic.AtomicLongArray

/**
 * The level-wide path type cache (`PathTypeCacheMixin`), read and written by every pathfinding mob on every
 * worker without a lock. Vanilla's index is the low bits of `HashCommon.mix(key)`, and `mix` is a bijection,
 * so the slot index plus the hash's remaining high bits identify the position exactly. Each slot is one long:
 * those high bits, with the type's ordinal + 1 in the low bits the index already implies (0 = empty). A slot is
 * read and written as one atomic word, so a reader sees a whole entry or a miss, and a store allocates nothing.
 * A lost race only costs a recomputation.
 */
object PathTypeSlots {
    const val SIZE = 4096
    private const val MASK = SIZE - 1
    /** The hash bits a slot stores: all but the index bits. */
    private const val HIGH = -SIZE.toLong()
    private val TYPES: Array<PathType> = PathType.entries.toTypedArray()

    init {
        check(TYPES.size < MASK) { "${TYPES.size} path types do not fit in a slot's low bits" }
    }

    @JvmStatic
    fun create(): AtomicLongArray = AtomicLongArray(SIZE)

    /** The cached type of [key] (a packed block position), or null. */
    @JvmStatic
    fun get(slots: AtomicLongArray, key: Long): PathType? {
        val hash = HashCommon.mix(key)
        val slot = slots.getOpaque(hash.toInt() and MASK)
        if (slot == 0L || ((slot xor hash) and HIGH) != 0L) return null
        return TYPES[(slot and MASK.toLong()).toInt() - 1]
    }

    @JvmStatic
    fun put(slots: AtomicLongArray, key: Long, type: PathType) {
        val hash = HashCommon.mix(key)
        slots.setOpaque(hash.toInt() and MASK, (hash and HIGH) or (type.ordinal + 1).toLong())
    }

    /** Clears [key]'s entry if its slot still holds it; an entry for another key in that slot stays. */
    @JvmStatic
    fun invalidate(slots: AtomicLongArray, key: Long) {
        val hash = HashCommon.mix(key)
        val index = hash.toInt() and MASK
        val slot = slots.getOpaque(index)
        if (slot != 0L && ((slot xor hash) and HIGH) == 0L) slots.compareAndSet(index, slot, 0L)
    }
}
