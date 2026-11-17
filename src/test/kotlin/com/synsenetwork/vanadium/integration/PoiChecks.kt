package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.concurrent.PoiDistanceAccess
import com.synsenetwork.vanadium.concurrent.PoiTicketAccess
import it.unimi.dsi.fastutil.longs.LongSet
import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiRecord
import net.minecraft.world.entity.ai.village.poi.PoiSection
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.SectionStorage

/**
 * POI locking (docs/ARCHITECTURE.md, "POI locking"): records are lock-free leaves, the distance graph's monitor is
 * terminal, and the village-centre level is published from a read made under that monitor. Lock edges are
 * probed by holding the monitor a regression would need on a helper thread and waiting a bounded time for the
 * probe; the holder then lets go, so a regression fails the check instead of hanging the server.
 */
internal object PoiChecks {
    private const val WAIT_SECONDS = 5L
    /** The graph's highest level (levelCount - 1): where a section ends after its village centre goes; 7 if never near one. */
    private const val NOT_A_VILLAGE = 6
    private val ARMORER = PoiTypes.ARMORER
    private val armorer: Holder<PoiType> get() = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(ARMORER)
    private val isArmorer = Predicate<Holder<PoiType>> { it.`is`(ARMORER) }

    fun run(level: ServerLevel) {
        checkSynchronization()
        // Sites 256 blocks apart: village levels reach six sections (96 blocks), so the checks cannot see each other.
        val site = { i: Int -> BlockPos(80_000 + i * 256, 64, -80_000) }
        checkRecordTakesNoMonitor(level, site(0))
        checkGraphTakesNoSectionMonitor(level, site(1))
        checkLevelPublishedUnderGraphMonitor(level, site(2))
        checkLoadedAndEmptySections(level, site(3))
        println("POI_CHECKS_PASSED")
    }

    /** Records hold no monitor; sections keep theirs on structural state, except the lazy record stream. */
    private fun checkSynchronization() {
        val record = PoiRecord::class.java.declaredMethods.filter { Modifier.isSynchronized(it.modifiers) }
        check(record.isEmpty()) { "PoiRecord methods are synchronized: ${record.map { it.name }}" }
        val section = PoiSection::class.java.declaredMethods.associateBy({ it.name }) { Modifier.isSynchronized(it.modifiers) }
        check(section["getRecords"] == false) { "PoiSection.getRecords is synchronized" }
        for (name in listOf("add", "remove", "release", "getType", "refresh", "pack")) {
            check(section[name] == true) { "PoiSection.$name lost its section monitor" }
        }
    }

    /** Section reads, claims, releases and saving must not wait for a record's monitor (section then record). */
    private fun checkRecordTakesNoMonitor(level: ServerLevel, pos: BlockPos) {
        val poi = level.poiManager
        val record = addArmorer(level, pos)
        try {
            holding(record, "record monitor") {
                check(poi.getType(pos).orElse(null)?.`is`(ARMORER) == true) { "POI type was not read" }
                check(poi.getCountInRange(isArmorer, pos, 1, PoiManager.Occupancy.HAS_SPACE) == 1L) { "free POI not counted" }
                check(poi.take(isArmorer, { _, p -> p == pos }, pos, 1) == Optional.of(pos)) { "free POI was not taken" }
                check(poi.getCountInRange(isArmorer, pos, 1, PoiManager.Occupancy.IS_OCCUPIED) == 1L) { "taken POI not occupied" }
                check(record.pack().freeTickets() == 0) { "saved record kept its ticket" }
                check(poi.release(pos)) { "taken POI was not released" }
                check(!poi.release(pos)) { "a free POI released a ticket it did not have" }
            }
        } finally {
            poi.remove(pos)
        }
    }

    /**
     * A ticket change publishes the section's village-centre level under the graph's monitor, and graph updates
     * read every nearby section: neither may wait for a section's monitor (the edge of the original deadlock:
     * record, then section; a section's own writes take the graph after it).
     */
    private fun checkGraphTakesNoSectionMonitor(level: ServerLevel, pos: BlockPos) {
        val poi = level.poiManager
        val record = addArmorer(level, pos)
        try {
            val section = section(poi, SectionPos.asLong(pos)) ?: throw AssertionError("POI section missing")
            holding(section, "section monitor") {
                check((record as PoiTicketAccess).`vanadium$acquireTicket`()) { "free ticket was not acquired" }
                check(poi.sectionsToVillage(SectionPos.of(pos)) == 0) { "occupied job site is not a village centre" }
            }
            check(poi.release(pos)) { "acquired ticket was not released" }
            val released = poi.sectionsToVillage(SectionPos.of(pos))
            check(released == NOT_A_VILLAGE) { "released job site is still in a village: level $released" }
        } finally {
            poi.remove(pos)
        }
    }

    /**
     * One thread occupies the job site and reads its level; before it can publish, another frees the site and
     * publishes. The first must publish what holds when it gets the monitor, not what it read before: vanilla
     * computes the level before taking the monitor and would leave a village centre with no occupied POI.
     */
    private fun checkLevelPublishedUnderGraphMonitor(level: ServerLevel, pos: BlockPos) {
        val poi = level.poiManager
        val record = addArmorer(level, pos)
        val tracker = PoiManager::class.java.getDeclaredField("distanceTracker").apply { isAccessible = true }.get(poi)
        try {
            val held = CountDownLatch(1)
            val claimantBlocked = CountDownLatch(1)
            val holderFailure = AtomicReference<Throwable>()
            val holder = daemon("poi-graph-holder") {
                try {
                    synchronized(tracker) {
                        held.countDown()
                        check(claimantBlocked.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "claimant never waited for the graph" }
                        // Reentrant: this release publishes "not a centre" before the claimant can publish.
                        check(poi.release(pos)) { "claimed ticket was not released" }
                    }
                } catch (t: Throwable) {
                    holderFailure.set(t)
                }
            }
            check(held.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "graph holder did not start" }
            val claimed = AtomicReference<Boolean>()
            val claimant = daemon("poi-claimant") { claimed.set((record as PoiTicketAccess).`vanadium$acquireTicket`()) }
            awaitBlockedOn(claimant, tracker)
            claimantBlocked.countDown()
            holder.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            claimant.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            check(!holder.isAlive && !claimant.isAlive) { "graph probe threads did not finish" }
            holderFailure.get()?.let { throw AssertionError("graph holder failed", it) }
            check(claimed.get() == true) { "claimant did not acquire the free ticket" }
            check(record.pack().freeTickets() == 1) { "released ticket not saved" }
            val published = poi.sectionsToVillage(SectionPos.of(pos))
            check(published >= NOT_A_VILLAGE) { "a stale level left a village centre with no occupied POI: level $published" }
        } finally {
            poi.remove(pos)
        }
    }

    /** Loaded sections without POI and sections never loaded are not village centres; occupied ones are. */
    private fun checkLoadedAndEmptySections(level: ServerLevel, pos: BlockPos) {
        val poi = level.poiManager
        val tracker = PoiManager::class.java.getDeclaredField("distanceTracker").apply { isAccessible = true }.get(poi) as PoiDistanceAccess
        val record = addArmorer(level, pos)
        try {
            val above = SectionPos.asLong(pos.above(16))
            check(section(poi, above, allowEmpty = true) == null) { "section above the POI is not loaded empty" }
            check(tracker.`vanadium$levelFromSource`(above) == 7) { "empty loaded section is a village centre" }
            check(tracker.`vanadium$levelFromSource`(SectionPos.asLong(pos.offset(4_096, 0, 0))) == 7) { "unloaded section is a village centre" }
            check(tracker.`vanadium$levelFromSource`(SectionPos.asLong(pos)) == 7) { "free job site is a village centre" }
            check((record as PoiTicketAccess).`vanadium$acquireTicket`()) { "free ticket was not acquired" }
            check(tracker.`vanadium$levelFromSource`(SectionPos.asLong(pos)) == 0) { "occupied job site is not a village centre" }
            val dirty = SectionStorage::class.java.getDeclaredField("dirtyChunks").apply { isAccessible = true }.get(poi) as LongSet
            check(dirty.contains(ChunkPos.containing(pos).pack())) { "ticket change did not mark its column for saving" }
        } finally {
            poi.remove(pos)
        }
    }

    private fun addArmorer(level: ServerLevel, pos: BlockPos): PoiRecord {
        check(level.poiManager.getType(pos).isEmpty) { "test POI position $pos is occupied" }
        return level.poiManager.add(pos, armorer) ?: throw AssertionError("POI not added at $pos")
    }

    private fun section(poi: PoiManager, sectionPos: Long, allowEmpty: Boolean = false): PoiSection? {
        val get = SectionStorage::class.java.getDeclaredMethod("get", Long::class.javaPrimitiveType).apply { isAccessible = true }
        val section = get.invoke(poi, sectionPos) as Optional<*>? ?: throw AssertionError("section $sectionPos not loaded")
        if (section.isEmpty) {
            check(allowEmpty) { "section $sectionPos has no POI" }
            return null
        }
        return section.get() as PoiSection
    }

    /** Runs [probe] on this thread while a helper holds [monitor]; fails if the probe still waits after the bound. */
    private fun holding(monitor: Any, name: String, probe: () -> Unit) {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = daemon("poi-holder") {
            synchronized(monitor) {
                held.countDown()
                release.await(WAIT_SECONDS * 4, TimeUnit.SECONDS)
            }
        }
        check(held.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "$name holder did not start" }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val worker = daemon("poi-probe") {
            try {
                probe()
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }
        val finished = done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        val stack = if (finished) "" else worker.stackTrace.joinToString("\n  at ", prefix = "\n  at ")
        release.countDown()
        holder.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        // A regressed probe finishes once the holder lets go: no permanent hang.
        check(done.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "probe still blocked after the $name was released" }
        check(finished) { "probe waited for a $name it must not take:$stack" }
        failure.get()?.let { throw AssertionError("probe failed while another thread held the $name", it) }
    }

    private fun awaitBlockedOn(thread: Thread, monitor: Any) {
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            val info = threads.getThreadInfo(thread.threadId())
            if (info != null && info.threadState == Thread.State.BLOCKED &&
                info.lockInfo?.identityHashCode == System.identityHashCode(monitor)) return
            check(thread.isAlive) { "${thread.name} finished without waiting for the graph" }
            Thread.onSpinWait()
        }
        throw AssertionError("${thread.name} never waited for the graph: ${thread.stackTrace.joinToString("\n  at ")}")
    }

    private fun daemon(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply { isDaemon = true; start() }
}
