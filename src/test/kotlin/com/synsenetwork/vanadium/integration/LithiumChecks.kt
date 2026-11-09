package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.collections.ConcurrentLongObjectMap
import com.synsenetwork.vanadium.collections.ConcurrentLongObjectOpenHashMap
import com.synsenetwork.vanadium.collections.ListeningConcurrentLongObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.SectionStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** POI loading is safe for concurrent callers, and Lithium keeps its optimizations on shared storage. */
internal object LithiumChecks {
    fun run(level: ServerLevel) {
        val storageField = SectionStorage::class.java.getDeclaredField("storage").apply { isAccessible = true }
        val storage = storageField.get(level.poiManager)
        // Vanilla also raced here: a second loader of a column could read it before its sections were stored.
        checkConcurrentPoiLoads(level.poiManager)
        if (!FabricLoader.getInstance().isModLoaded("lithium")) {
            check(storage is ConcurrentLongObjectMap<*>) { "POI storage is not concurrent: ${storage.javaClass}" }
            println("LITHIUM_CHECKS_SKIPPED lithium=absent")
            return
        }
        check(storage is ListeningConcurrentLongObjectMap<*>) { "Lithium POI storage was not made concurrent: ${storage.javaClass}" }
        val columns = SectionStorage::class.java.getDeclaredField("columns").apply { isAccessible = true }.get(level.poiManager)
        check(columns is ConcurrentLongObjectOpenHashMap<*>) { "Lithium POI column index was not made concurrent: ${columns.javaClass}" }
        checkGameEventStorage(level)
        println("LITHIUM_CHECKS_PASSED")
    }

    /** Overlapping queries over columns nobody has loaded yet; each column must load once, for every caller. */
    private fun checkConcurrentPoiLoads(poi: PoiManager) {
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val jobs = List(threads) { t ->
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS)) { "POI load gate timed out" }
                    repeat(4) { round ->
                        val center = BlockPos(60_000 + round * 96 + t % 2 * 8, 64, 60_000)
                        poi.getInRange({ true }, center, 48, PoiManager.Occupancy.ANY).count()
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** The wrapped per-world dispatcher map stays consistent while threads add, read and remove chunks. */
    private fun checkGameEventStorage(level: ServerLevel) {
        val data = level.javaClass.getMethod("lithium\$getData").invoke(level)
        val storage = data.javaClass.getMethod("gameEventDispatchers").invoke(data)
        val type = storage.javaClass
        val add = type.getMethod("addChunk", java.lang.Long.TYPE, it.unimi.dsi.fastutil.ints.Int2ObjectMap::class.java)
        val get = type.getMethod("get", java.lang.Long.TYPE)
        val remove = type.getMethod("removeChunk", java.lang.Long.TYPE)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = List(4) { t ->
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS)) { "game event gate timed out" }
                    for (i in 0 until 500) {
                        val key = ChunkPos.pack(70_000 + t * 1_000 + i, 70_000)
                        val dispatchers = Int2ObjectOpenHashMap<Any>()
                        add.invoke(storage, key, dispatchers)
                        check(get.invoke(storage, key) === dispatchers) { "game event storage lost a chunk" }
                        remove.invoke(storage, key)
                        check(get.invoke(storage, key) == null) { "game event storage kept a removed chunk" }
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
