package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap

/**
 * A [Long2ReferenceOpenHashMap] whose single-key operations hold its monitor, for rarely written maps that
 * other mods type concretely. Iteration and bulk views are not synchronized; callers iterate under the
 * map's monitor themselves.
 */
class SynchronizedLong2ReferenceOpenHashMap<V> : Long2ReferenceOpenHashMap<V>() {
    @Synchronized override fun get(k: Long): V? = super.get(k)

    @Synchronized override fun containsKey(k: Long): Boolean = super.containsKey(k)

    @Synchronized override fun put(k: Long, v: V): V? = super.put(k, v)

    @Synchronized override fun putIfAbsent(k: Long, v: V): V? = super.putIfAbsent(k, v)

    @Synchronized override fun remove(k: Long): V? = super.remove(k)

    @Synchronized override fun remove(k: Long, v: Any?): Boolean = super.remove(k, v)

    @Synchronized override fun clear() = super.clear()
}
