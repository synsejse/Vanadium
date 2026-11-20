package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.stage.BlockEntityStage
import com.synsenetwork.vanadium.stage.ChunkTickAccess
import com.synsenetwork.vanadium.stage.EntityTickAccess
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.DistanceManager
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ClassInstanceMultiMap
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.behavior.ShowTradesToPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiSection
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.level.Level
import net.minecraft.world.level.entity.EntityLookup
import net.minecraft.world.level.entity.EntityTickList
import net.minecraft.world.level.storage.SavedDataStorage

/**
 * Every vanilla collection Vanadium replaces must hold the replacement at runtime. Mixin can drop `@Shadow`
 * field initializers when a mixin also adds initialized fields, which no compile-time check would notice.
 * Every level also needs its own stage owners, which the same `<init>` RETURN hooks install.
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
            expect(poiSection(), PoiSection::class.java, "byType", "java.util.concurrent.ConcurrentHashMap"),
            poiTypeSet(),
        ) + level.server.allLevels.flatMap(::stageOwners)
        check(failures.isEmpty()) { "Vanilla collections were not replaced:\n" + failures.joinToString("\n") }
        println("COLLECTION_REPLACEMENT_CHECKS_PASSED levels=${level.server.allLevels.count()}")
    }

    /** Mismatches in [level]'s stage owners: each installed, and each the level's own. */
    private fun stageOwners(level: ServerLevel): List<String> {
        val name = level.dimension().identifier()
        val failures = ArrayList<String>()
        if ((level.chunkSource as ChunkTickAccess).`vanadium$chunkStage`() == null) failures.add("$name has no chunk stage")
        val blockEntityStage = BLOCK_ENTITY_STAGE.get(level)
        if (blockEntityStage == null) {
            failures.add("$name has no block-entity stage")
        } else if (BLOCK_ENTITY_STAGE_LEVEL.get(blockEntityStage) !== level) {
            failures.add("$name's block-entity stage belongs to another level")
        }
        val entityTickList = field(level, ServerLevel::class.java, "entityTickList") as EntityTickAccess
        if (entityTickList.`vanadium$entityTicks`() == null) failures.add("$name has no entity tick tasks")
        for ((kind, ticks) in listOf("block" to level.blockTicks, "fluid" to level.fluidTicks)) {
            val queue = expect(ticks, null, "toRunThisTick", "com.synsenetwork.vanadium.stage.ScheduledTickStage\$Queue")
            if (queue != null) failures.add("$name $kind ticks: $queue")
        }
        return failures
    }

    private val BLOCK_ENTITY_STAGE = Level::class.java.declaredFields.single { it.type == BlockEntityStage::class.java }
        .apply { isAccessible = true }

    private val BLOCK_ENTITY_STAGE_LEVEL = BlockEntityStage::class.java.getDeclaredField("level").apply { isAccessible = true }

    private fun poiSection(): PoiSection = PoiSection {}.apply {
        add(BlockPos(1, 2, 3), BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME))
    }

    /** Sets inside the per-type map are created on first use, by the section's add. */
    private fun poiTypeSet(): String? {
        val byType = field(poiSection(), PoiSection::class.java, "byType") as Map<*, *>
        val set = byType.values.single()!!.javaClass.name
        return if (set == "java.util.concurrent.ConcurrentHashMap\$KeySetView") null else "PoiSection type set is $set"
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
