package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.concurrent.PoiDistanceAccess
import com.synsenetwork.vanadium.concurrent.PoiSectionAccess
import com.synsenetwork.vanadium.tick.WorkerPool
import it.unimi.dsi.fastutil.longs.LongSet
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiRecord
import net.minecraft.world.entity.ai.village.poi.PoiSection
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.RegionFileVersion
import net.minecraft.world.level.chunk.storage.SectionStorage
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.storage.LevelResource

/**
 * C2ME's POI unloading driven by its own scheduler, not by calling its methods: two fresh chunks get a load ticket,
 * reach FULL, get a job site each, lose the ticket, and C2ME downgrades them through `ReadFromDisk` on the server
 * thread, which (in one synchronous task) fires Fabric's CHUNK_UNLOAD, flushes the POI column, and calls
 * `c2me$unloadPoi`. The event is the observed downgrade; the POI storage is read afterwards, without loading.
 *
 * - Free column (claimed, then released; eligible: no wave, no village centre): its sections must be physically
 *   gone from storage, and the column forgotten. If the downgrade happened to run inside a parallel wave (the
 *   server thread serving a worker's chunk load), Vanadium's wave veto keeps it; that attempt is checked as kept and
 *   the chunk is loaded and released again, up to [ATTEMPTS] times.
 * - Occupied column: claimed by a worker inside a real WorkerPool wave right after the caller flushed the column (an
 *   early flush, as C2ME's chunk save does): the claim must mark it dirty again. This fixture forces that order by
 *   calling flush and take; it does not show that natural ticking produces it. The real downgrade must then happen
 *   (event, chunk gone) while the column stays installed with its ticket, and the graph, drained, keeps the centre.
 * - Persistence: after C2ME's storage thread is synchronised, the region file itself (read directly, read-only, not
 *   through the IO thread and its cache) holds the free ticket and the claimed one. Reloading the removed free
 *   column then goes through the IO thread, which may serve it from memory: that shows the stored state round-trips,
 *   the file read shows it reached disk (as written by the OS; no claim about power-loss durability).
 */
internal object PoiUnloadChecks {
    private const val ATTEMPTS = 3
    private const val LOAD_TICKS = 200
    private const val UNLOAD_TICKS = 200
    private const val IO_TICKS = 100
    // Fresh, eight chunks apart (beyond the graph's six-section reach), one region file; far from every other fixture.
    private val FREE = ChunkPos(6000, -6000)
    private val OCCUPIED = ChunkPos(6008, -6000)
    private val isArmorer = Predicate<Holder<PoiType>> { it.`is`(PoiTypes.ARMORER) }

    private class Unload(val inWave: Boolean, val thread: String)

    fun schedule(level: ServerLevel, checks: TickedChecks) {
        val poi = level.poiManager
        val source = level.chunkSource
        val c2me = PoiChecks.C2meUnload.require(poi)
        val tracker = field(PoiManager::class.java, "distanceTracker", poi) as PoiDistanceAccess
        val dirty = field(SectionStorage::class.java, "dirtyChunks", poi) as LongSet
        val freePos = BlockPos(FREE.minBlockX + 8, -40, FREE.minBlockZ + 8)
        val occupiedPos = BlockPos(OCCUPIED.minBlockX + 8, -40, OCCUPIED.minBlockZ + 8)
        val freeSectionPos = SectionPos.asLong(freePos)
        val occupiedSectionPos = SectionPos.asLong(occupiedPos)
        val sectionYs = level.minSectionY..level.maxSectionY
        // C2ME fires this from its ReadFromDisk downgrade, on the server thread, just before c2me$unloadPoi.
        val unloads = ConcurrentHashMap<ChunkPos, Unload>()
        var listening = true
        ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
            if (listening && world === level && (chunk.pos == FREE || chunk.pos == OCCUPIED)) {
                unloads[chunk.pos] = Unload(WorkerPool.isParallelWaveCaller() || WorkerPool.isWorkerThread(), Thread.currentThread().name)
            }
        }
        fun loaded(chunk: ChunkPos) = synchronized(field(SectionStorage::class.java, "loadLock", poi)) {
            (field(SectionStorage::class.java, "loadedChunks", poi) as LongSet).contains(chunk.pack())
        }
        fun installed(section: PoiSection, pos: BlockPos): PoiRecord? = synchronized(section) { (section as PoiSectionAccess).`vanadium$recordAt`(pos) }
        /** The graph's level with every pending update run; reads installed sections only. */
        fun drainedLevel(pos: BlockPos): Int = poi.sectionsToVillage(SectionPos.of(pos)).also {
            check(!tracker.`vanadium$hasPendingUpdates`()) { "graph still has pending updates after draining" }
        }

        lateinit var freeRecord: PoiRecord
        lateinit var freeSection: PoiSection
        lateinit var occupiedRecord: PoiRecord
        lateinit var occupiedSection: PoiSection
        var attempts = 0
        var freeTicketed = false
        var freeRemoved = false
        var occupiedKept = false
        var io: CompletableFuture<Void>? = null

        checks.then("POI unload fixture tickets") {
            for (chunk in listOf(FREE, OCCUPIED)) {
                check(source.getVisibleChunkIfPresent(chunk.pack()) == null) { "POI unload fixture chunk $chunk is already tracked" }
                check(!loaded(chunk)) { "POI unload fixture column $chunk is already loaded" }
                source.addTicketWithRadius(TicketType.PLAYER_LOADING, chunk, 0)
            }
            freeTicketed = true
        }
        checks.awaitAsync("POI unload fixture chunks FULL", LOAD_TICKS) {
            source.getChunkNow(FREE.x(), FREE.z()) != null && source.getChunkNow(OCCUPIED.x(), OCCUPIED.z()) != null
        }
        checks.then("POI unload fixture claims") {
            freeRecord = PoiChecks.addArmorer(level, freePos)
            check(poi.take(isArmorer, { _, p -> p == freePos }, freePos, 1) == Optional.of(freePos)) { "free job site not claimed" }
            check(poi.release(freePos)) { "free job site not released" }
            check(freeRecord.pack().freeTickets() == 1) { "released job site has no free ticket" }
            check(drainedLevel(freePos) >= PoiChecks.NOT_A_VILLAGE) { "released job site is a village centre" }
            freeSection = PoiChecks.section(poi, freeSectionPos) ?: throw AssertionError("free POI section missing")

            occupiedRecord = PoiChecks.addArmorer(level, occupiedPos)
            occupiedSection = PoiChecks.section(poi, occupiedSectionPos) ?: throw AssertionError("occupied POI section missing")
            check(dirty.contains(OCCUPIED.pack())) { "adding a job site did not mark its column for saving" }
            val workerStarted = CountDownLatch(1)
            val flushed = CountDownLatch(1)
            val cleanAfterFlush = AtomicReference<Boolean>()
            val claimed = AtomicReference<Boolean>()
            WorkerPool(1).use { pool ->
                pool.runWave(List(2) {
                    Runnable {
                        if (WorkerPool.isWorkerThread()) {
                            workerStarted.countDown()
                            check(flushed.await(5, TimeUnit.SECONDS)) { "early flush gate timed out" }
                            claimed.set(poi.take(isArmorer, { _, p -> p == occupiedPos }, occupiedPos, 1) == Optional.of(occupiedPos))
                        } else {
                            try {
                                check(workerStarted.await(5, TimeUnit.SECONDS)) { "no worker joined the wave" }
                                poi.flush(OCCUPIED)
                                cleanAfterFlush.set(!dirty.contains(OCCUPIED.pack()))
                            } finally {
                                flushed.countDown()
                            }
                        }
                    }
                })
            }
            check(cleanAfterFlush.get() == true) { "the early flush left the column dirty" }
            check(claimed.get() == true) { "the worker could not claim the flushed job site" }
            check(dirty.contains(OCCUPIED.pack())) { "a claim after an early flush did not mark its column for saving" }
            check(occupiedRecord.pack().freeTickets() == 0 && drainedLevel(occupiedPos) == 0) { "claimed job site is not a village centre" }

            check(!c2me.unloadVetoed(FREE)) { "C2ME would not unload the free column outside waves (in PoiManager.loadedChunks?)" }
            check(c2me.unloadVetoed(OCCUPIED)) { "C2ME may unload a column holding a village centre" }
            unloads.clear()
            for (chunk in listOf(FREE, OCCUPIED)) source.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunk, 0)
            freeTicketed = false
        }
        var waited = 0
        checks.awaitAsync("C2ME downgrades the POI fixture chunks", UNLOAD_TICKS + 1) {
            if (++waited > UNLOAD_TICKS) {
                throw AssertionError("no C2ME downgrade within $UNLOAD_TICKS ticks: events=${unloads.mapValues { it.value.thread }} " +
                    "freeRemoved=$freeRemoved occupiedKept=$occupiedKept attempts=$attempts " +
                    "holders free=${source.getVisibleChunkIfPresent(FREE.pack()) != null} occupied=${source.getVisibleChunkIfPresent(OCCUPIED.pack()) != null} " +
                    "(needs C2ME's chunk-system rewrite and Fabric lifecycle events, which C2ME fires on unload)")
            }
            unloads.remove(OCCUPIED)?.let { event ->
                check(source.getChunkNow(OCCUPIED.x(), OCCUPIED.z()) == null) { "occupied chunk is still FULL after its unload" }
                check(PoiChecks.stored(poi, occupiedSectionPos)?.orElse(null) === occupiedSection && loaded(OCCUPIED)) {
                    "C2ME's downgrade unloaded a POI column holding a village centre (event on ${event.thread}, inWave=${event.inWave})"
                }
                check(installed(occupiedSection, occupiedPos) === occupiedRecord && occupiedRecord.pack().freeTickets() == 0) {
                    "the kept column lost its claimed record or ticket"
                }
                // ReadFromDisk's chunk save flushes the column before c2me$unloadPoi (a POI tick may have written it first).
                check(!dirty.contains(OCCUPIED.pack())) { "the downgrade left the kept column's claim unflushed" }
                check(drainedLevel(occupiedPos) == 0) { "the kept occupied column is no longer a village centre" }
                check(c2me.unloadVetoed(OCCUPIED)) { "the kept column is no longer vetoed" }
                occupiedKept = true
            }
            unloads.remove(FREE)?.let { event ->
                check(source.getChunkNow(FREE.x(), FREE.z()) == null) { "free chunk is still FULL after its unload" }
                if (event.inWave) {
                    // Vetoed by the wave: kept, as on a natural wave; load and release it again.
                    check(PoiChecks.stored(poi, freeSectionPos)?.orElse(null) === freeSection) { "C2ME unloaded POI inside a parallel wave" }
                    check(++attempts < ATTEMPTS) { "every free-column downgrade ran inside a parallel wave ($attempts attempts)" }
                    source.addTicketWithRadius(TicketType.PLAYER_LOADING, FREE, 0)
                    freeTicketed = true
                } else {
                    for (y in sectionYs) {
                        check(PoiChecks.stored(poi, SectionPos.asLong(FREE.x(), y, FREE.z())) == null) {
                            "C2ME's downgrade left section $y of the free, eligible POI column installed (event on ${event.thread})"
                        }
                    }
                    check(!loaded(FREE)) { "the removed free column is still marked loaded" }
                    check(drainedLevel(freePos) >= PoiChecks.NOT_A_VILLAGE) { "the removed free column is a village centre" }
                    freeRemoved = true
                }
            }
            if (freeTicketed && source.getChunkNow(FREE.x(), FREE.z()) != null) {
                source.removeTicketWithRadius(TicketType.PLAYER_LOADING, FREE, 0)
                freeTicketed = false
            }
            freeRemoved && occupiedKept
        }
        checks.then("POI storage synchronised") {
            listening = false
            io = (field(SectionStorage::class.java, "simpleRegionStorage", poi) as SimpleRegionStorage).synchronize(true)
        }
        checks.awaitAsync("POI storage synchronised to its region file", IO_TICKS) { io!!.isDone }
        checks.then("POI tickets on disk and reloaded") {
            io!!.join()
            check(diskTickets(level, freePos) == 1) { "the region file holds ${diskTickets(level, freePos)} free tickets for the released job site, not 1" }
            check(diskTickets(level, occupiedPos) == 0) {
                "the region file holds ${diskTickets(level, occupiedPos)} free tickets for the job site claimed after an early flush, not 0"
            }
            check(PoiChecks.stored(poi, freeSectionPos) == null) { "the free column was reloaded before its reload check" }
            val reloaded = PoiChecks.currentRecord(poi, freePos) ?: throw AssertionError("the removed free job site did not reload")
            check(reloaded !== freeRecord && reloaded.pack().freeTickets() == 1) { "the reloaded job site did not keep its free ticket" }
            check(drainedLevel(freePos) >= PoiChecks.NOT_A_VILLAGE) { "the reloaded free job site is a village centre" }
            check(drainedLevel(occupiedPos) == 0) { "the kept occupied job site is no longer a village centre" }
            poi.remove(freePos)
            poi.remove(occupiedPos)
            println("POI_UNLOAD_CHECKS_PASSED attempts=${attempts + 1}")
        }
    }

    /** Free tickets of the record at [pos] in the POI region file itself, read-only; null if not stored there. */
    private fun diskTickets(level: ServerLevel, pos: BlockPos): Int? {
        val chunk = ChunkPos.containing(pos)
        val folder = DimensionType.getStorageFolder(level.dimension(), level.server.getWorldPath(LevelResource.ROOT)).resolve("poi")
        val file = folder.resolve("r.${chunk.x() shr 5}.${chunk.z() shr 5}.mca")
        if (!Files.isRegularFile(file)) return null
        val tag = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val location = readFully(channel, 4L * ((chunk.x() and 31) + (chunk.z() and 31) * 32), 4).int
            if (location == 0) return null
            val sectors = readFully(channel, (location ushr 8).toLong() * 4096, (location and 0xFF) * 4096)
            val length = sectors.int
            val version = sectors.get().toInt()
            check(version and 0x80 == 0) { "POI column $chunk is in an external .mcc file, which this reader does not follow" }
            val compression = RegionFileVersion.fromId(version) ?: throw AssertionError("POI column $chunk has unknown compression $version")
            DataInputStream(compression.wrap(ByteArrayInputStream(sectors.array(), sectors.position(), length - 1))).use { NbtIo.read(it) }
        }
        val section = tag.getCompoundOrEmpty("Sections").get(SectionPos.blockToSectionCoord(pos.y).toString()) ?: return null
        val packed = PoiSection.Packed.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), section).getOrThrow()
        return packed.records().firstOrNull { it.pos() == pos }?.freeTickets()
    }

    private fun readFully(channel: FileChannel, at: Long, size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(size)
        while (buffer.hasRemaining()) {
            check(channel.read(buffer, at + buffer.position()) >= 0) { "region file ends before ${at + size}" }
        }
        return buffer.flip()
    }

    private fun field(owner: Class<*>, name: String, target: Any): Any = owner.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
