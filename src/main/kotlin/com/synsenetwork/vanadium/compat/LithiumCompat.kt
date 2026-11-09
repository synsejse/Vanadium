package com.synsenetwork.vanadium.compat

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.collections.ConcurrentLongObjectMap
import com.synsenetwork.vanadium.collections.ConcurrentLongObjectOpenHashMap
import com.synsenetwork.vanadium.collections.ListeningConcurrentLongObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.SectionPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.SectionStorage
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.function.LongUnaryOperator

/**
 * Keeps Lithium's optimizations from undoing Vanadium's concurrent storage. Lithium is optional: every
 * hook checks for its classes at runtime and leaves storage untouched when they are absent.
 */
object LithiumCompat {
    private const val LISTENING_MAP = "net.caffeinemc.mods.lithium.common.util.collections.ListeningLong2ObjectOpenHashMap"

    /** Lithium's POI column index, merged into [SectionStorage] by its `ai.poi` mixin; null without it. */
    private val columnsField: Field? = SectionStorage::class.java.declaredFields
        .firstOrNull { it.name == "columns" && it.type == Long2ObjectOpenHashMap::class.java }
        ?.apply { isAccessible = true }

    @Volatile
    private var reported = false

    /**
     * Called after every `SectionStorage` constructor. Replaces vanilla's map with a [ConcurrentLongObjectMap];
     * when Lithium's `ai.poi` has installed its listening map and column index, replaces both with concurrent
     * equivalents that drive Lithium's own callbacks. Returns the storage map to install.
     */
    @JvmStatic
    fun <R> concurrentSectionStorage(owner: SectionStorage<*, *>, storage: Long2ObjectMap<R>): Long2ObjectMap<R> {
        if (storage is ConcurrentLongObjectMap<*> || storage is ListeningConcurrentLongObjectMap<*>) return storage
        if (storage.javaClass == Long2ObjectOpenHashMap::class.java) {
            return ConcurrentLongObjectMap<R>().also { it.putAll(storage) }
        }
        val columns = columnsField
        if (storage.javaClass.name != LISTENING_MAP || columns == null) {
            if (!reported) {
                reported = true
                Vanadium.LOGGER.warn("Section storage was replaced by {}; Vanadium cannot make it thread-safe", storage.javaClass.name)
            }
            return storage
        }
        val add = callback(storage, "addCallback")
        val remove = callback(storage, "removeCallback")
        val apply = callbackMethod(storage)
        @Suppress("UNCHECKED_CAST")
        val previousColumns = columns.get(owner) as Long2ObjectOpenHashMap<Any?>
        columns.set(owner, ConcurrentLongObjectOpenHashMap<Any?>().also { it.putAll(previousColumns) })
        val concurrent = ListeningConcurrentLongObjectMap<R>(
            { key, value, added -> apply.invoke(if (added) add else remove, key, value) },
            SECTION_COLUMN,
        )
        concurrent.putAllSilently(storage)
        if (!reported) {
            reported = true
            Vanadium.LOGGER.info("Lithium POI storage made thread-safe for parallel ticking")
        }
        return concurrent
    }

    private fun callback(map: Any, name: String): Any =
        map.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(map)

    private fun callbackMethod(map: Any): Method =
        Class.forName("$LISTENING_MAP\$Callback", false, map.javaClass.classLoader)
            .getMethod("apply", java.lang.Long.TYPE, Any::class.java)

    /** Section keys of one chunk column share a stripe: Lithium keeps one bit set per column. */
    private val SECTION_COLUMN = LongUnaryOperator { key -> ChunkPos.pack(SectionPos.x(key), SectionPos.z(key)) }
}
