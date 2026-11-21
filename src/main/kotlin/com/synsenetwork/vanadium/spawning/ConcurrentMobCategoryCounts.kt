package com.synsenetwork.vanadium.spawning

import com.synsenetwork.vanadium.tick.TickThread
import it.unimi.dsi.fastutil.objects.AbstractObject2IntMap
import it.unimi.dsi.fastutil.objects.AbstractObjectSet
import it.unimi.dsi.fastutil.objects.Object2IntMap
import it.unimi.dsi.fastutil.objects.ObjectIterator
import it.unimi.dsi.fastutil.objects.ObjectSet
import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.function.IntBinaryOperator
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory

/**
 * Lock-free `Object2IntMap<MobCategory>` backed by an [AtomicIntegerArray], replacing the hash map inside
 * `LocalMobCapCalculator.MobCounts` so spawning workers can check and update per-player mob caps without
 * taking a shared monitor or boxing integers.
 */
class ConcurrentMobCategoryCounts : AbstractObject2IntMap<MobCategory>() {
    private val counts = AtomicIntegerArray(CATEGORIES.size)

    fun increment(category: MobCategory): Int = counts.incrementAndGet(category.ordinal)

    fun getCount(category: MobCategory): Int = counts.get(category.ordinal)

    override fun getInt(key: Any?): Int {
        val category = key as? MobCategory ?: return defRetValue
        val count = counts.get(category.ordinal)
        return if (count == 0) defRetValue else count
    }

    override fun getOrDefault(key: Any?, defaultValue: Int): Int {
        val category = key as? MobCategory ?: return defaultValue
        val count = counts.get(category.ordinal)
        return if (count == 0) defaultValue else count
    }

    override fun containsKey(key: MobCategory?): Boolean = key != null && counts.get(key.ordinal) != 0

    override fun put(key: MobCategory, value: Int): Int {
        val prev = counts.getAndSet(key.ordinal, value)
        return if (prev == 0) defRetValue else prev
    }

    override fun removeInt(key: Any?): Int {
        val category = key as? MobCategory ?: return defRetValue
        val prev = counts.getAndSet(category.ordinal, 0)
        return if (prev == 0) defRetValue else prev
    }

    override fun mergeInt(key: MobCategory, value: Int, remappingFunction: IntBinaryOperator): Int {
        val ordinal = key.ordinal
        while (true) {
            val current = counts.get(ordinal)
            val next = if (current == 0) value else remappingFunction.applyAsInt(current, value)
            if (counts.compareAndSet(ordinal, current, next)) return next
        }
    }

    override val size: Int
        get() {
            var count = 0
            for (i in CATEGORIES.indices) if (counts.get(i) != 0) count++
            return count
        }

    override fun clear() {
        for (i in CATEGORIES.indices) counts.set(i, 0)
    }

    override fun object2IntEntrySet(): ObjectSet<Object2IntMap.Entry<MobCategory>> = entrySetView

    private val entrySetView = object : AbstractObjectSet<Object2IntMap.Entry<MobCategory>>() {
        override val size: Int get() = this@ConcurrentMobCategoryCounts.size

        override fun iterator(): ObjectIterator<Object2IntMap.Entry<MobCategory>> = object : ObjectIterator<Object2IntMap.Entry<MobCategory>> {
            private var nextIndex = advance(0)
            private var lastIndex = -1

            private fun advance(from: Int): Int {
                var i = from
                while (i < CATEGORIES.size && counts.get(i) == 0) i++
                return i
            }

            override fun hasNext(): Boolean = nextIndex < CATEGORIES.size

            override fun next(): Object2IntMap.Entry<MobCategory> {
                val i = nextIndex
                if (i >= CATEGORIES.size) throw NoSuchElementException()
                val value = counts.get(i)
                lastIndex = i
                nextIndex = advance(i + 1)
                return BasicEntry(CATEGORIES[i], value)
            }

            override fun remove() {
                check(lastIndex >= 0) { "remove() before next()" }
                counts.set(lastIndex, 0)
                lastIndex = -1
            }
        }
    }

    /** Per-thread 1-entry cache replacing `NaturalSpawner.SpawnState`'s shared `lastCheckedPos/Type/Charge` fields. */
    class SpawnChargeCache {
        @JvmField var pos: BlockPos? = null
        @JvmField var type: EntityType<*>? = null
        @JvmField var charge: Double = 0.0
    }

    companion object {
        private val CATEGORIES: Array<MobCategory> = MobCategory.entries.toTypedArray()
        private val CHARGE_CACHE = ThreadLocal.withInitial(::SpawnChargeCache)

        @JvmStatic
        fun chargeCache(): SpawnChargeCache {
            val thread = Thread.currentThread()
            if (thread is TickThread) {
                var cache = thread.spawnChargeCache as SpawnChargeCache?
                if (cache == null) {
                    cache = SpawnChargeCache()
                    thread.spawnChargeCache = cache
                }
                return cache
            }
            return CHARGE_CACHE.get()
        }
    }
}
