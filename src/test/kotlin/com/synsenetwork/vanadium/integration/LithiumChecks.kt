package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.chunk.ParallelChunkCache
import com.synsenetwork.vanadium.collections.ConcurrentIntObjectMap
import com.synsenetwork.vanadium.collections.ConcurrentLongObjectOpenHashMap
import com.synsenetwork.vanadium.collections.ListeningConcurrentLongObjectMap
import com.synsenetwork.vanadium.concurrent.PerThreadRandValue
import com.synsenetwork.vanadium.tick.WorkerPool
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.caffeinemc.mods.lithium.common.entity.movement.CollisionEntityClassGroups
import net.caffeinemc.mods.lithium.common.util.deduplication.LithiumInterner
import net.caffeinemc.mods.lithium.common.world.LithiumData
import net.caffeinemc.mods.lithium.common.world.WorldHelper
import net.caffeinemc.mods.lithium.common.world.chunk.ChunkStatusTracker
import net.caffeinemc.mods.lithium.common.world.chunk.ClassGroupFilterableList
import net.caffeinemc.mods.lithium.common.world.listeners.WorldBorderPositionListenerMulti
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ClassInstanceMultiMap
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.storage.SectionStorage
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Lithium's optimizations stay enabled on state that Vanadium's parallel stages share. */
internal object LithiumChecks {
    fun run(level: ServerLevel) {
        checkPoiStorage(level)
        checkGameEventStorage(level)
        checkChunkTracking(level)
        checkCallbacksWaitForWave(level)
        checkPerThreadRandValue(level)
        checkClassGroups(level)
        checkListenerSections(level)
        checkSynchronized()
        println("LITHIUM_CHECKS_PASSED")
    }

    private fun checkPoiStorage(level: ServerLevel) {
        val storage = SectionStorage::class.java.getDeclaredField("storage").apply { isAccessible = true }.get(level.poiManager)
        check(storage is ListeningConcurrentLongObjectMap<*>) { "Lithium POI storage was not made concurrent: ${storage.javaClass}" }
        val columns = SectionStorage::class.java.getDeclaredField("columns").apply { isAccessible = true }.get(level.poiManager)
        check(columns is ConcurrentLongObjectOpenHashMap<*>) { "Lithium POI column index was not made concurrent: ${columns.javaClass}" }
        // Vanilla also raced here: a second loader of a column could read it before its sections were stored.
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val jobs = List(threads) { t ->
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS)) { "POI load gate timed out" }
                    repeat(4) { round ->
                        val center = BlockPos(60_000 + round * 96 + t % 2 * 8, 64, 60_000)
                        level.poiManager.getInRange({ true }, center, 48, PoiManager.Occupancy.ANY).count()
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** The per-world dispatcher map stays consistent while threads add, read and remove chunks. */
    private fun checkGameEventStorage(level: ServerLevel) {
        val storage = (level as LithiumData).`lithium$getData`().gameEventDispatchers()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = List(4) { t ->
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS)) { "game event gate timed out" }
                    for (i in 0 until 500) {
                        val key = ChunkPos.pack(70_000 + t * 1_000 + i, 70_000)
                        val dispatchers = Int2ObjectOpenHashMap<net.minecraft.world.level.gameevent.GameEventListenerRegistry>()
                        storage.addChunk(key, dispatchers)
                        check(storage.get(key) === dispatchers) { "game event storage lost a chunk" }
                        storage.removeChunk(key)
                        check(storage.get(key) == null) { "game event storage kept a removed chunk" }
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Every published full chunk reached Lithium's load callbacks, whichever C2ME path loaded it. */
    private fun checkChunkTracking(level: ServerLevel) {
        val loaded = (level.chunkSource as ParallelChunkCache).loadedChunkPositions()
        val tracked = (level as LithiumData).`lithium$getData`().gameEventDispatchers().loadedChunks()
        check(loaded.isNotEmpty()) { "no loaded chunks to compare" }
        val missing = loaded.filter { !tracked.contains(it) }
        check(missing.isEmpty()) { "Lithium never saw ${missing.size} of ${loaded.size} loaded chunks, e.g. ${ChunkPos.unpack(missing.first())}" }
    }

    /** Chunk status callbacks fired by the server thread mid-wave run once the workers are done, in order. */
    private fun checkCallbacksWaitForWave(level: ServerLevel) {
        val probe = ChunkPos(-90_000, 90_000)
        val seen = ArrayList<String>()
        val active = AtomicBoolean(true)
        ChunkStatusTracker.registerUnloadCallback { callbackLevel, pos ->
            if (active.get() && callbackLevel === level && pos == probe) {
                seen.add("callback:${WorkerPool.isParallelWaveCaller()}")
            }
        }
        val callerDone = CountDownLatch(1)
        val workerDone = CountDownLatch(1)
        try {
            WorkerPool(1).use { pool ->
                pool.runWave(List(2) {
                    Runnable {
                        if (WorkerPool.isWorkerThread()) {
                            check(callerDone.await(10, TimeUnit.SECONDS)) { "callback gate timed out" }
                            workerDone.countDown()
                        } else {
                            ChunkStatusTracker.onChunkInaccessible(level, probe)
                            seen.add("wave")
                            callerDone.countDown()
                            check(workerDone.await(10, TimeUnit.SECONDS)) { "callback gate timed out" }
                        }
                    }
                })
            }
        } finally {
            active.set(false)
        }
        check(seen == listOf("wave", "callback:false")) { "chunk status callback ran during the wave: $seen" }
    }

    /** Random-tick positions draw from the calling thread's LCG, in vanilla and in Lithium's shortcut. */
    private fun checkPerThreadRandValue(level: ServerLevel) {
        val before = PerThreadRandValue.get(level)
        val other = Executors.newSingleThreadExecutor()
        try {
            other.submit { repeat(1_000) { level.getBlockRandomPos(0, 0, 0, 15) } }.get(10, TimeUnit.SECONDS)
        } finally {
            other.shutdownNow()
        }
        check(PerThreadRandValue.get(level) == before) { "another thread advanced this thread's randValue" }
        level.getBlockRandomPos(0, 0, 0, 15)
        check(PerThreadRandValue.get(level) == before * 3 + 1013904223) { "vanilla getBlockRandomPos does not use per-thread state" }
        val lithium = ServerLevel::class.java.declaredMethods.single { it.name == "getRandomBlockIndexForRandomTick" }
            .apply { isAccessible = true }
        val mid = PerThreadRandValue.get(level)
        lithium.invoke(level)
        check(PerThreadRandValue.get(level) == mid * 3 + 1013904223) { "Lithium's random tick does not use per-thread state" }
    }

    /** Lithium's collision, cramming and projectile shortcuts see Vanadium's class groups, safely. */
    private fun checkClassGroups(level: ServerLevel) {
        check(!WorldHelper.CUSTOM_TYPE_FILTERABLE_LIST_DISABLED) { "Lithium does not see entity class groups" }
        val group = CollisionEntityClassGroups.BOAT_SHULKER_LIKE_COLLISION
        val map = ClassInstanceMultiMap(Entity::class.java)
        @Suppress("UNCHECKED_CAST")
        val groups = map as ClassGroupFilterableList<Entity>
        val shulker: Entity = EntityTypes.SHULKER.create(level, EntitySpawnReason.COMMAND)!!
        val pig: Entity = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND)!!
        map.add(pig)
        map.add(shulker)
        val view = groups.`lithium$getAllOfGroupType`(group)
        check(view.toList() == listOf(shulker)) { "class group built wrong: $view" }
        map.remove(shulker)
        check(view.isEmpty() && groups.`lithium$getAllOfGroupType`(group) === view) { "class group missed a removal" }
        // One writer churns the group while readers iterate it; copy-on-write iteration never fails.
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val writer = pool.submit {
                check(start.await(10, TimeUnit.SECONDS)) { "class group gate timed out" }
                repeat(2_000) {
                    map.add(shulker)
                    map.remove(shulker)
                }
            }
            val readers = List(3) {
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS)) { "class group gate timed out" }
                    repeat(20_000) { for (entity in view) check(entity === shulker) { "class group held $entity" } }
                }
            }
            start.countDown()
            writer.get(30, TimeUnit.SECONDS)
            readers.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        check(view.isEmpty() && map.size == 1) { "class group churn left ${view.size} shulkers" }
    }

    /** Lithium's dispatch reads per-chunk listener maps without the chunk's monitor; they are concurrent. */
    private fun checkListenerSections(level: ServerLevel) {
        val chunk = level.getChunkAt(BlockPos.ZERO)
        chunk.getListenerRegistry(chunk.minSectionY)
        val sections = LevelChunk::class.java.getDeclaredField("gameEventListenerRegistrySections").apply { isAccessible = true }.get(chunk)
        check(sections is ConcurrentIntObjectMap<*>) { "chunk listener sections are ${sections?.javaClass}" }
    }

    private fun checkSynchronized() {
        for ((type, method) in listOf(LithiumInterner::class.java to "getCanonical", WorldBorderPositionListenerMulti::class.java to "add")) {
            check(type.declaredMethods.filter { it.name == method }.all { Modifier.isSynchronized(it.modifiers) }) {
                "${type.simpleName}.$method is not synchronized"
            }
        }
    }
}
