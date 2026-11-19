package com.synsenetwork.vanadium.compat

import com.synsenetwork.vanadium.collections.ConcurrentLongObjectMap
import com.synsenetwork.vanadium.collections.ConcurrentLongObjectOpenHashMap
import com.synsenetwork.vanadium.collections.ListeningConcurrentLongObjectMap
import com.synsenetwork.vanadium.mixin.compat.lithium.ListeningLong2ObjectOpenHashMapAccessor
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.lang.reflect.Field
import java.util.function.LongUnaryOperator
import net.caffeinemc.mods.lithium.common.util.collections.ListeningLong2ObjectOpenHashMap
import net.minecraft.core.SectionPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.SectionStorage

/** Keeps Lithium's POI section index on storage that parallel stages can share. */
object LithiumCompat {
    /**
     * Guards Lithium's entity movement tracking: per-section waiting lists and per-tracker state, reached by
     * entity ticks of any thread in the flat wave (see `EntitySectionMovementTrackingMixin`). Taken after any
     * section stripe; nothing under it takes another Vanadium lock.
     */
    @JvmField
    val MOVEMENT_TRACKING = Any()

    /**
     * Lithium's POI column index, merged into [SectionStorage] by its `ai.poi` option; null when a user disabled
     * that option (then the storage is vanilla's map).
     */
    private val columnsField: Field? = SectionStorage::class.java.declaredFields
        .firstOrNull { it.name == "columns" && it.type == Long2ObjectOpenHashMap::class.java }

    init {
        columnsField?.isAccessible = true
    }

    /**
     * Called after every `SectionStorage` constructor, after Lithium's. Lithium's listening map and column index
     * become concurrent equivalents that drive Lithium's own callbacks; vanilla's map (Lithium's `ai.poi`
     * disabled) becomes a [ConcurrentLongObjectMap]. Returns the storage map to install.
     */
    @JvmStatic
    fun <R> concurrentSectionStorage(owner: SectionStorage<*, *>, storage: Long2ObjectMap<R>): Long2ObjectMap<R> {
        if (storage is ConcurrentLongObjectMap<*> || storage is ListeningConcurrentLongObjectMap<*>) return storage
        if (storage.javaClass == Long2ObjectOpenHashMap::class.java) {
            val concurrent = ConcurrentLongObjectMap<R>()
            concurrent.putAll(storage)
            return concurrent
        }
        val columns = columnsField
        check(storage is ListeningLong2ObjectOpenHashMap<R> && columns != null) {
            "Unsupported POI section storage: ${storage.javaClass.name}. " +
                "Vanadium cannot make this layout thread-safe; check Lithium compatibility and other POI mods."
        }
        @Suppress("UNCHECKED_CAST")
        val callbacks = storage as ListeningLong2ObjectOpenHashMapAccessor<R>
        val add = callbacks.`vanadium$addCallback`()
        val remove = callbacks.`vanadium$removeCallback`()
        @Suppress("UNCHECKED_CAST")
        val previousColumns = columns.get(owner) as Long2ObjectOpenHashMap<Any?>
        val concurrentColumns = ConcurrentLongObjectOpenHashMap<Any?>()
        concurrentColumns.putAll(previousColumns)
        columns.set(owner, concurrentColumns)
        val concurrent = ListeningConcurrentLongObjectMap<R>(
            { key, value, added -> (if (added) add else remove).apply(key, value) },
            SECTION_COLUMN,
        )
        concurrent.putAllSilently(storage)
        return concurrent
    }

    /** Section keys of one chunk column share a stripe: Lithium keeps one bit set per column. */
    private val SECTION_COLUMN = LongUnaryOperator { key -> ChunkPos.pack(SectionPos.x(key), SectionPos.z(key)) }
}
