package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.integration.PoiChecks.ARMORER
import com.synsenetwork.vanadium.integration.PoiChecks.NOT_A_VILLAGE
import com.synsenetwork.vanadium.integration.PoiChecks.WAIT_SECONDS
import com.synsenetwork.vanadium.integration.PoiChecks.addArmorer
import com.synsenetwork.vanadium.integration.PoiChecks.currentRecord
import com.synsenetwork.vanadium.integration.PoiChecks.isArmorer
import com.synsenetwork.vanadium.integration.PoiChecks.section
import com.synsenetwork.vanadium.integration.PoiChecks.stored
import com.synsenetwork.vanadium.tick.WorkerPool
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.LongSet
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.SectionStorage

/**
 * POI column eviction and persistence through C2ME's own unload entry points, called directly so each veto and
 * reload is forced in a fixed order (part of [PoiChecks.run]): a column holding a village centre, or reached during a
 * parallel wave, stays installed with its tickets; a released column unloads and reloads with its saved free ticket.
 * [PoiUnloadChecks] drives the same unloading through C2ME's real downgrade over ticks, where an in-wave downgrade is
 * not guaranteed to happen, so the wave veto is forced only here.
 */
internal object PoiEvictionChecks {
    /**
     * Saving and reloading a column keeps its claimed tickets and its village centre: the column is flushed and its
     * sections dropped as C2ME's unload does (by hand, since that unload keeps occupied columns), then read back.
     */
    fun checkReloadKeepsTicketsAndCentre(level: ServerLevel, c2me: C2meUnload, pos: BlockPos) {
        val poi = level.poiManager
        val chunk = ChunkPos.containing(pos)
        val sectionPos = SectionPos.asLong(pos)
        val record = addArmorer(level, pos)
        try {
            check(poi.take(isArmorer, { _, p -> p == pos }, pos, 1) == Optional.of(pos)) { "free job site not claimed" }
            check(poi.sectionsToVillage(SectionPos.of(pos)) == 0) { "claimed job site is not a village centre" }
            val before = section(poi, sectionPos)
            check(c2me.unloadVetoed(chunk)) { "C2ME may unload a column holding a village centre" }
            c2me.unload(chunk)
            check(section(poi, sectionPos) === before) { "C2ME unloaded a column holding a village centre" }
            evictLikeC2me(poi, chunk)
            check(stored(poi, sectionPos) == null) { "evicted column is still loaded" }
            check(poi.getType(pos).orElse(null)?.`is`(ARMORER) == true) { "reloaded column lost its job site" }
            val reloaded = currentRecord(poi, pos) ?: throw AssertionError("reloaded job site has no record")
            check(reloaded !== record && section(poi, sectionPos) !== before) { "column was not read back" }
            check(reloaded.pack().freeTickets() == 0) { "reloaded job site lost its claimed ticket" }
            check(poi.take(isArmorer, { _, p -> p == pos }, pos, 1).isEmpty) { "reloaded job site gave out its claimed ticket again" }
            check(poi.sectionsToVillage(SectionPos.of(pos)) == 0) { "reloaded occupied job site is not a village centre" }
            check(poi.release(pos)) { "reloaded ticket not released" }
        } finally {
            if (poi.getType(pos).isPresent) poi.remove(pos)
        }
    }

    /**
     * C2ME's unload, through its own entry points: refused while the server thread runs a parallel wave with a worker
     * in it (the worker then claims the job site on the record that stayed), refused outside waves while the column
     * holds that village centre (through a save), and done once the ticket is released, after which the column
     * reloads with the saved free ticket.
     */
    fun checkUnloadKeepsColumnsInUse(level: ServerLevel, c2me: C2meUnload, pos: BlockPos) {
        val poi = level.poiManager
        val chunk = ChunkPos.containing(pos)
        val sectionPos = SectionPos.asLong(pos)
        val record = addArmorer(level, pos)
        try {
            val before = section(poi, sectionPos)
            check(!c2me.unloadVetoed(chunk)) { "C2ME would not unload a column with a free job site outside waves" }
            val workerStarted = CountDownLatch(1)
            val callerDone = CountDownLatch(1)
            val vetoed = AtomicReference<Boolean>()
            val kept = AtomicReference<Boolean>()
            val claimed = AtomicReference<Boolean>()
            WorkerPool(1).use { pool ->
                pool.runWave(List(2) {
                    Runnable {
                        if (WorkerPool.isWorkerThread()) {
                            workerStarted.countDown()
                            check(callerDone.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "unload attempt gate timed out" }
                            claimed.set(poi.take(isArmorer, { _, p -> p == pos }, pos, 1) == Optional.of(pos))
                        } else {
                            try {
                                check(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "no worker joined the wave" }
                                check(WorkerPool.isParallelWaveCaller()) { "caller is not running a parallel wave" }
                                vetoed.set(c2me.unloadVetoed(chunk))
                                c2me.unload(chunk)
                                kept.set(section(poi, sectionPos) === before)
                            } finally {
                                callerDone.countDown()
                            }
                        }
                    }
                })
            }
            check(vetoed.get() == true) { "C2ME may unload POI while a parallel wave runs" }
            check(kept.get() == true) { "C2ME unloaded a POI column during a parallel wave" }
            check(claimed.get() == true) { "the worker could not claim the job site the wave kept" }
            check(section(poi, sectionPos) === before && record.pack().freeTickets() == 0) { "the claimed ticket is not on the installed record" }
            check(poi.sectionsToVillage(SectionPos.of(pos)) == 0) { "claimed job site is not a village centre" }

            check(c2me.unloadVetoed(chunk)) { "C2ME may unload a column holding a village centre" }
            c2me.unload(chunk)
            poi.flush(chunk)
            check(section(poi, sectionPos) === before && record.pack().freeTickets() == 0) { "the occupied column was not kept with its ticket" }
            check(poi.sectionsToVillage(SectionPos.of(pos)) == 0) { "the kept column lost its village centre" }

            check(poi.release(pos)) { "claimed ticket not released" }
            check(!c2me.unloadVetoed(chunk)) { "a released column is still kept" }
            c2me.unload(chunk)
            check(stored(poi, sectionPos) == null) { "C2ME did not unload the released column" }
            val reloaded = currentRecord(poi, pos) ?: throw AssertionError("unloaded job site did not reload")
            check(reloaded !== record && reloaded.pack().freeTickets() == 1) { "reloaded job site did not keep its free ticket" }
            check(poi.sectionsToVillage(SectionPos.of(pos)) >= NOT_A_VILLAGE) { "a released, reloaded job site is a village centre" }
        } finally {
            if (poi.getType(pos).isPresent) poi.remove(pos)
        }
    }

    /** C2ME's POI unload entry points, merged into section storage; reflective, since C2ME is not on the compile path. */
    internal class C2meUnload(private val poi: PoiManager, private val should: java.lang.reflect.Method, private val unload: java.lang.reflect.Method,
                              val enabled: Boolean) {
        /** The merged answer, C2ME's own check and Vanadium's vetoes together: false when the column must stay. */
        fun unloadVetoed(chunk: ChunkPos): Boolean = !(should.invoke(poi, chunk) as Boolean)

        fun unload(chunk: ChunkPos) {
            unload.invoke(poi, chunk)
        }

        companion object {
            private const val PREREQUISITES = "POI unload checks need the pinned C2ME (c2me_version in gradle.properties, " +
                "installed into the run's mods/ by prepareRuntimeMods) with its default chunkSystem.allowPOIUnloading = true"

            /**
             * Fails, rather than skipping, when the run lacks what default pinned runs have: C2ME's merged methods,
             * POI unloading on, and Vanadium's veto merged (SynchronisePlugin fails the transformation when the method
             * exists without a call to it, so present here means bound).
             */
            fun require(poi: PoiManager): C2meUnload {
                val should = PoiManager::class.java.declaredMethods.firstOrNull { it.name == "c2me\$shouldUnloadPoi" }
                    ?: throw AssertionError("$PREREQUISITES: PoiManager has no c2me\$shouldUnloadPoi (C2ME or its chunk-system rewrite is missing)")
                val unload = SectionStorage::class.java.declaredMethods.firstOrNull { it.name == "c2me\$unloadPoi" }
                    ?: throw AssertionError("$PREREQUISITES: SectionStorage has no c2me\$unloadPoi")
                check(PoiManager::class.java.declaredMethods.any { it.name.endsWith("keepResident") }) {
                    "Vanadium's PoiUnloadMixin handler (vanadium\$keepResident) is not merged into PoiManager"
                }
                val config = Class.forName("com.ishland.c2me.rewrites.chunksystem.common.Config", true, PoiManager::class.java.classLoader)
                val enabled = config.getField("allowPOIUnloading").getBoolean(null)
                check(enabled) { "$PREREQUISITES: chunkSystem.allowPOIUnloading is off in this run's config/c2me.toml" }
                return C2meUnload(poi, should, unload, enabled)
            }
        }
    }

    /** Mirrors pinned C2ME's `c2me$unloadPoi` past its checks: flush, drop the sections, forget the column. */
    private fun evictLikeC2me(poi: PoiManager, chunk: ChunkPos) {
        fun field(name: String) = SectionStorage::class.java.getDeclaredField(name).apply { isAccessible = true }.get(poi)
        poi.flush(chunk)
        @Suppress("UNCHECKED_CAST")
        val storage = field("storage") as Long2ObjectMap<Optional<*>>
        for (y in level(poi).minSectionY..level(poi).maxSectionY) storage.remove(SectionPos.asLong(chunk.x(), y, chunk.z()))
        synchronized(field("loadLock")) {
            (field("loadedChunks") as LongSet).remove(chunk.pack())
            (field("pendingLoads") as Long2ObjectMap<*>).remove(chunk.pack())
        }
    }

    private fun level(poi: PoiManager) = SectionStorage::class.java.getDeclaredField("levelHeightAccessor").apply { isAccessible = true }
        .get(poi) as net.minecraft.world.level.LevelHeightAccessor
}
