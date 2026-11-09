package com.synsenetwork.vanadium.integration

import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.DistanceManager
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ClassInstanceMultiMap
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.behavior.ShowTradesToPlayer
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.level.entity.EntityLookup
import net.minecraft.world.level.entity.EntityTickList
import net.minecraft.world.level.storage.SavedDataStorage

/**
 * Every vanilla collection Vanadium replaces must hold the replacement at runtime. Mixin can drop `@Shadow`
 * field initializers when a mixin also adds initialized fields, which no compile-time check would notice.
 */
internal object CollectionReplacementChecks {
    fun run(level: ServerLevel) {
        val source = level.chunkSource
        val chunkMap = source.chunkMap
        val distanceManager = field(chunkMap, ChunkMap::class.java, "distanceManager")
        val failures = listOfNotNull(
            expect(source, ServerChunkCache::class.java, "chunkHoldersToBroadcast", "java.util.concurrent.ConcurrentHashMap\$KeySetView", "java.util.Collections\$SetFromMap"),
            expect(chunkMap, ChunkMap::class.java, "entityMap", "com.synsenetwork.vanadium.collections.ConcurrentIntObjectMap"),
            expect(chunkMap, ChunkMap::class.java, "pendingGenerationTasks", "java.util.concurrent.CopyOnWriteArrayList"),
            expect(field(distanceManager, DistanceManager::class.java, "simulationChunkTracker"), null, "chunks",
                "com.synsenetwork.vanadium.collections.ConcurrentLongByteMap"),
            expect(field(level.poiManager, PoiManager::class.java, "distanceTracker"), null, "levels",
                "com.synsenetwork.vanadium.collections.ConcurrentLongByteMap"),
            expect(field(level, ServerLevel::class.java, "entityTickList"), EntityTickList::class.java, "active",
                "com.synsenetwork.vanadium.collections.ConcurrentIntObjectMap"),
            expect(field(level, ServerLevel::class.java, "entityTickList"), EntityTickList::class.java, "passive",
                "com.synsenetwork.vanadium.collections.ConcurrentIntObjectMap"),
            expect(source.dataStorage, SavedDataStorage::class.java, "cache", "java.util.concurrent.ConcurrentHashMap"),
            expect(EntityLookup<Entity>(), EntityLookup::class.java, "byUuid", "java.util.concurrent.ConcurrentHashMap"),
            expect(ClassInstanceMultiMap(Entity::class.java), ClassInstanceMultiMap::class.java, "byClass", "java.util.concurrent.ConcurrentHashMap"),
            expect(ClassInstanceMultiMap(Entity::class.java), ClassInstanceMultiMap::class.java, "allInstances", "java.util.concurrent.CopyOnWriteArrayList"),
            expect(ShowTradesToPlayer(1, 2), ShowTradesToPlayer::class.java, "displayItems", "java.util.concurrent.CopyOnWriteArrayList"),
            expect(level.getBlockTicks(), null, "toRunThisTick", "com.synsenetwork.vanadium.tick.ScheduledTickQueue"),
        )
        check(failures.isEmpty()) { "Vanilla collections were not replaced:\n" + failures.joinToString("\n") }
        println("COLLECTION_REPLACEMENT_CHECKS_PASSED")
    }

    private fun declared(type: Class<*>, name: String): java.lang.reflect.Field {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name }?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        error("No field $name on $type")
    }

    private fun field(owner: Any, type: Class<*>?, name: String): Any = declared(type ?: owner.javaClass, name).get(owner)

    /** Null when [name] holds one of [expected]; otherwise a description of the mismatch. */
    private fun expect(owner: Any, type: Class<*>?, name: String, vararg expected: String): String? {
        val actual = field(owner, type, name).javaClass.name
        return if (actual in expected) null else "${(type ?: owner.javaClass).simpleName}.$name is $actual"
    }
}
