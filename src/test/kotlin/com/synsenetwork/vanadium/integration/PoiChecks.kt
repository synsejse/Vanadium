package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.concurrent.PoiDistanceAccess
import com.synsenetwork.vanadium.concurrent.PoiTicketAccess
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongSet
import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Predicate
import net.caffeinemc.mods.lithium.common.world.interests.PointOfInterestStorageExtended
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.Brain
import net.minecraft.world.entity.ai.behavior.AcquirePoi
import net.minecraft.world.entity.ai.behavior.declarative.MemoryAccessor
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiRecord
import net.minecraft.world.entity.ai.village.poi.PoiSection
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.SectionStorage

/**
 * POI locking (docs/src/content/docs/architecture.mdx, "POI locking"): records are lock-free leaves, the distance graph's monitor is
 * terminal, the village-centre level is published from a read made under that monitor, and claims honour the
 * ticket they actually took, on a record still installed. C2ME's POI unloading keeps columns that are in use (during
 * parallel waves, or holding a village centre). Lock edges are probed by holding the monitor a regression would need
 * on a helper thread and waiting a bounded time for the probe; the holder then lets go, so a regression fails the
 * check instead of hanging the server. Column eviction and persistence through C2ME's own unload entry points are in
 * [PoiEvictionChecks], which [run] calls in place; [PoiUnloadChecks] drives the real downgrade over ticks.
 */
internal object PoiChecks {
    const val WAIT_SECONDS = 5L
    /** The graph's highest level (levelCount - 1): where a section ends after its village centre goes; 7 if never near one. */
    const val NOT_A_VILLAGE = 6
    val ARMORER = PoiTypes.ARMORER
    private val armorer: Holder<PoiType> get() = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(ARMORER)
    val isArmorer = Predicate<Holder<PoiType>> { it.`is`(ARMORER) }

    fun run(level: ServerLevel) {
        checkSynchronization()
        // Sites 256 blocks apart: village levels reach six sections (96 blocks), so the checks cannot see each other.
        val site = { i: Int -> BlockPos(80_000 + i * 256, 64, -80_000) }
        checkRecordTakesNoMonitor(level, site(0))
        checkGraphTakesNoSectionMonitor(level, site(1))
        checkLevelPublishedUnderGraphMonitor(level, site(2))
        checkLoadedAndEmptySections(level, site(3))
        // Lithium's ai.poi option adds takeAt (ai.poi.tasks makes AcquirePoi call it); users can turn either off.
        val takeAt = level.poiManager as? PointOfInterestStorageExtended
        println("POI_CLAIM_PATHS takeAt=${takeAt != null}")
        checkTakeHonoursTicket(level, site(4))
        takeAt?.let { checkLithiumTakeAtHonoursTicket(level, it, site(5)) }
        checkOneTicketRace(level, takeAt, site(6))
        checkAcquirePoiOnlyAfterClaim(level, site(7))
        // Required, not skipped: default pinned runs must exercise C2ME's POI unloading (PoiUnloadChecks, ticked).
        val c2me = PoiEvictionChecks.C2meUnload.require(level.poiManager)
        println("POI_UNLOAD_PATHS c2me=true allowPOIUnloading=${c2me.enabled} hook=true")
        checkStaleCandidatesNotClaimed(level, takeAt, c2me, site(8))
        PoiEvictionChecks.checkReloadKeepsTicketsAndCentre(level, c2me, site(9))
        PoiEvictionChecks.checkUnloadKeepsColumnsInUse(level, c2me, site(10))
        checkCreateOnce(level, site(11))
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

    /** Vanilla's take skips a candidate whose last ticket was claimed after the occupancy filter, and searches on. */
    private fun checkTakeHonoursTicket(level: ServerLevel, pos: BlockPos) {
        val poi = level.poiManager
        val first = addArmorer(level, pos)
        val second = addArmorer(level, pos.offset(2, 0, 0))
        try {
            val stolen = AtomicReference<BlockPos>()
            // The filter runs between the occupancy test and the claim: another claimant takes the ticket there.
            val taken = poi.take(isArmorer, { _, p ->
                if (stolen.compareAndSet(null, p)) {
                    val record = if (p == first.pos) first else second
                    check((record as PoiTicketAccess).`vanadium$acquireTicket`()) { "steal failed" }
                }
                true
            }, pos, 8)
            val other = if (stolen.get() == first.pos) second.pos else first.pos
            check(taken == Optional.of(other)) { "take returned ${taken.orElse(null)} after losing ${stolen.get()}, not $other" }
            check(first.pack().freeTickets() == 0 && second.pack().freeTickets() == 0) { "tickets not both taken" }
            check(poi.take(isArmorer, { _, _ -> true }, pos, 8).isEmpty) { "take returned a POI without a free ticket" }
        } finally {
            poi.remove(pos)
            poi.remove(pos.offset(2, 0, 0))
        }
    }

    /** Lithium's exact-position claim (AcquirePoi's take) returns empty when the record had no free ticket. */
    private fun checkLithiumTakeAtHonoursTicket(level: ServerLevel, takeAt: PointOfInterestStorageExtended, pos: BlockPos) {
        val poi = level.poiManager
        val record = addArmorer(level, pos)
        try {
            check(takeAt.`lithium$takeAt`(isArmorer, { _, _ -> true }, pos) == Optional.of(pos)) { "free POI not taken at its position" }
            check(record.pack().freeTickets() == 0) { "takeAt did not take the ticket" }
            check(takeAt.`lithium$takeAt`(isArmorer, { _, _ -> true }, pos).isEmpty) { "takeAt returned a POI without a free ticket" }
            check(record.pack().freeTickets() == 0) { "failed takeAt changed the tickets" }
        } finally {
            poi.remove(pos)
        }
    }

    /** Claimants racing for one live record's ticket through both claims (take alone without takeAt): one wins each round. */
    private fun checkOneTicketRace(level: ServerLevel, takeAt: PointOfInterestStorageExtended?, pos: BlockPos) {
        val poi = level.poiManager
        val record = addArmorer(level, pos)
        val threads = 8
        try {
            repeat(50) { round ->
                val start = CountDownLatch(1)
                val done = CountDownLatch(threads)
                val wins = AtomicInteger()
                val failure = AtomicReference<Throwable>()
                repeat(threads) { t ->
                    daemon("poi-race-$t") {
                        try {
                            check(start.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "race gate timed out" }
                            val taken = if (takeAt == null || t % 2 == 0) poi.take(isArmorer, { _, p -> p == pos }, pos, 1)
                            else takeAt.`lithium$takeAt`(isArmorer, { _, _ -> true }, pos)
                            if (taken.isPresent) wins.incrementAndGet()
                        } catch (e: Throwable) {
                            failure.set(e)
                        } finally {
                            done.countDown()
                        }
                    }
                }
                start.countDown()
                check(done.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "race round $round did not finish" }
                failure.get()?.let { throw AssertionError("race round $round failed", it) }
                check(wins.get() == 1) { "round $round: ${wins.get()} claimants won one ticket" }
                check(record.pack().freeTickets() == 0) { "round $round left ${record.pack().freeTickets()} tickets" }
                check(poi.release(pos)) { "round $round ticket not released" }
            }
        } finally {
            poi.remove(pos)
        }
    }

    /**
     * AcquirePoi's claim step, through whichever claim it calls (take, or takeAt under Lithium's ai.poi.tasks): with
     * the ticket gone it leaves memory and retry cache alone; with a free ticket it claims, remembers the site and
     * resets the cache.
     */
    private fun checkAcquirePoiOnlyAfterClaim(level: ServerLevel, pos: BlockPos) {
        val poi = level.poiManager
        val record = addArmorer(level, pos)
        val villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND)!!
        val memory = MemoryModuleType.POTENTIAL_JOB_SITE
        val accessor = MemoryAccessor::class.java.getConstructor(Brain::class.java, MemoryModuleType::class.java,
            com.mojang.datafixers.kinds.App::class.java).newInstance(villager.brain, memory, null)
        val claim = AcquirePoi::class.java.getDeclaredMethod("lambda\$create\$7", PoiManager::class.java, Predicate::class.java,
            BlockPos::class.java, MemoryAccessor::class.java, ServerLevel::class.java, Optional::class.java,
            PathfinderMob::class.java, it.unimi.dsi.fastutil.longs.Long2ObjectMap::class.java, Holder::class.java)
            .apply { isAccessible = true }
        try {
            val retries = Long2ObjectOpenHashMap<Any>().apply { put(1L, Any()) }
            check((record as PoiTicketAccess).`vanadium$acquireTicket`()) { "free ticket was not acquired" }
            claim.invoke(null, poi, isArmorer, pos, accessor, level, Optional.empty<Byte>(), villager, retries, armorer)
            check(villager.brain.getMemory(memory).isEmpty) { "villager remembers a job site it holds no ticket for" }
            check(retries.size == 1) { "a failed claim reset the retry cache" }
            check(poi.release(pos)) { "ticket not released" }
            claim.invoke(null, poi, isArmorer, pos, accessor, level, Optional.empty<Byte>(), villager, retries, armorer)
            check(villager.brain.getMemory(memory) == Optional.of(GlobalPos.of(level.dimension(), pos))) { "claimed job site not remembered" }
            check(retries.isEmpty()) { "a successful claim kept the retry cache" }
            check(record.pack().freeTickets() == 0) { "remembered job site has a free ticket" }
        } finally {
            villager.discard()
            poi.remove(pos)
        }
    }

    /**
     * A claim's candidate can go stale before its claim: removed by a block change, dropped by a refresh of its
     * section, or detached by a column unload. Each invalidation runs inside the claim, between the search and the
     * commit (take's filter, takeAt's type test), for both claim routes. The stale record must keep its ticket and the
     * claim must fail; a record the refresh kept is the same live record, and stays claimable.
     */
    private fun checkStaleCandidatesNotClaimed(level: ServerLevel, takeAt: PointOfInterestStorageExtended?, c2me: PoiEvictionChecks.C2meUnload, base: BlockPos) {
        val poi = level.poiManager
        val invalidations = linkedMapOf<String, (PoiRecord, PoiRecord) -> Unit>(
            "removal" to { stale, _ -> poi.remove(stale.pos) },
            "refresh" to { stale, kept ->
                val section = section(poi, SectionPos.asLong(stale.pos)) ?: throw AssertionError("refresh: section missing")
                PoiSection::class.java.getDeclaredField("isValid").apply { isAccessible = true }.setBoolean(section, false)
                // Refresh rebuilds an invalid section from its blocks: only the kept position still has a POI.
                section.refresh(Consumer { out: BiConsumer<BlockPos, Holder<PoiType>> -> out.accept(kept.pos, armorer) })
            })
        invalidations["unload"] = { stale, _ -> c2me.unload(ChunkPos.containing(stale.pos)) }
        val routes = linkedMapOf<String, (BlockPos, () -> Unit) -> Optional<BlockPos>>(
            "take" to { pos, invalidate -> poi.take(isArmorer, { _, p -> if (p == pos) invalidate(); p == pos }, pos, 1) })
        takeAt?.let { lithium ->
            routes["takeAt"] = { pos, invalidate -> lithium.`lithium$takeAt`({ type -> invalidate(); type.`is`(ARMORER) }, { _, _ -> true }, pos) }
        }
        var case = 0
        for ((route, claim) in routes) for ((kind, invalidate) in invalidations) {
            // A column of its own per case (16 blocks apart), the kept record two blocks over in the same section.
            val pos = base.offset(16 * case++, 0, 0)
            val keptPos = pos.offset(2, 0, 0)
            val stale = addArmorer(level, pos)
            val kept = addArmorer(level, keptPos)
            try {
                val fired = AtomicBoolean()
                val taken = claim(pos) { if (fired.compareAndSet(false, true)) invalidate(stale, kept) }
                check(fired.get()) { "$route/$kind: the claim never reached its candidate" }
                check(taken.isEmpty) { "$route/$kind: claimed a record that is no longer installed" }
                check(stale.pack().freeTickets() == 1) { "$route/$kind: took a ticket on a detached record" }
                val current = currentRecord(poi, pos)
                if (kind == "unload") {
                    check(current != null && current !== stale) { "$route/$kind: column did not reload a new record" }
                    check(claim(pos) {} == Optional.of(pos)) { "$route/$kind: the reloaded record is not claimable" }
                } else {
                    check(current == null) { "$route/$kind: the invalidated POI is still installed" }
                }
                val keptNow = currentRecord(poi, keptPos)
                if (kind == "unload") check(keptNow !== kept) { "$route/$kind: kept record survived its column's unload" }
                else check(keptNow === kept) { "$route/$kind: a record the change kept was replaced" }
                check(claim(keptPos) {} == Optional.of(keptPos)) { "$route/$kind: the kept record is not claimable" }
                check(keptNow?.pack()?.freeTickets() == 0) { "$route/$kind: claim of the kept record took no ticket" }
            } finally {
                for (p in listOf(pos, keptPos)) if (poi.getType(p).isPresent) poi.remove(p)
            }
        }
        println("POI_STALE_CANDIDATES cases=$case routes=${routes.keys} invalidations=${invalidations.keys}")
    }

    /**
     * Creating an empty section holds its column's unpack stripe (SectionStorageMixin), so two first adds to one
     * empty section cannot each put a section and lose the other's record. Deterministic: a helper holds the stripe,
     * a second adder must block on it, then the helper creates the section and adds its record before letting go;
     * the second add must land in that section. Shows the lock is taken and both records survive this forced order,
     * not that every natural interleaving is covered.
     */
    private fun checkCreateOnce(level: ServerLevel, pos: BlockPos) {
        val poi = level.poiManager
        val other = pos.offset(2, 0, 0)
        val sectionPos = SectionPos.asLong(pos)
        val chunk = ChunkPos.containing(pos)
        try {
            poi.getType(pos) // Loads the column: a never-saved one is all empty slots.
            check(section(poi, sectionPos, allowEmpty = true) == null) { "createOnce fixture section is not loaded empty" }
            val locks = SectionStorage::class.java.declaredFields.firstOrNull { it.name.endsWith("unpackLocks") }
                ?.apply { isAccessible = true }?.get(poi) as Array<*>?
                ?: throw AssertionError("SectionStorageMixin's unpack stripes are not merged into SectionStorage")
            val stripe = locks[it.unimi.dsi.fastutil.HashCommon.mix(chunk.pack()).toInt() and (locks.size - 1)]!!
            val held = CountDownLatch(1)
            val go = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val first = daemon("poi-create-first") {
                try {
                    synchronized(stripe) {
                        held.countDown()
                        check(go.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "second adder never blocked" }
                        addArmorer(level, pos) // Reentrant: creates the section under the held stripe.
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
            check(held.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "stripe holder did not start" }
            val second = daemon("poi-create-second") {
                try {
                    addArmorer(level, other)
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
            try {
                awaitBlockedOn(second, stripe, "the column's unpack stripe (getOrCreate)")
            } finally {
                go.countDown()
            }
            first.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            second.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            check(!first.isAlive && !second.isAlive) { "createOnce threads did not finish" }
            failure.get()?.let { throw AssertionError("createOnce adder failed", it) }
            val created = section(poi, sectionPos) ?: throw AssertionError("no section was created")
            val records = synchronized(created) { listOf(pos, other).map { (created as com.synsenetwork.vanadium.concurrent.PoiSectionAccess).`vanadium$recordAt`(it) } }
            check(records.all { it != null }) { "a first add to an empty section was lost: $records" }
        } finally {
            for (p in listOf(pos, other)) if (poi.getType(p).isPresent) poi.remove(p)
        }
        println("POI_CREATE_ONCE_PASSED")
    }

    /** The record installed at [pos], loading its column if needed; null if none. */
    internal fun currentRecord(poi: PoiManager, pos: BlockPos): PoiRecord? {
        poi.getType(pos)
        val section = section(poi, SectionPos.asLong(pos), allowEmpty = true) ?: return null
        return synchronized(section) { (section as com.synsenetwork.vanadium.concurrent.PoiSectionAccess).`vanadium$recordAt`(pos) }
    }

    /** The storage slot without loading: null when the column is not loaded. */
    internal fun stored(poi: PoiManager, sectionPos: Long): Optional<*>? =
        SectionStorage::class.java.getDeclaredMethod("get", Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(poi, sectionPos) as Optional<*>?

    internal fun addArmorer(level: ServerLevel, pos: BlockPos): PoiRecord {
        check(level.poiManager.getType(pos).isEmpty) { "test POI position $pos is occupied" }
        return level.poiManager.add(pos, armorer) ?: throw AssertionError("POI not added at $pos")
    }

    internal fun section(poi: PoiManager, sectionPos: Long, allowEmpty: Boolean = false): PoiSection? {
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

    private fun awaitBlockedOn(thread: Thread, monitor: Any, what: String = "the graph") {
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            val info = threads.getThreadInfo(thread.threadId())
            if (info != null && info.threadState == Thread.State.BLOCKED &&
                info.lockInfo?.identityHashCode == System.identityHashCode(monitor)) return
            check(thread.isAlive) { "${thread.name} finished without waiting for $what" }
            Thread.onSpinWait()
        }
        throw AssertionError("${thread.name} never waited for $what: ${thread.stackTrace.joinToString("\n  at ")}")
    }

    private fun daemon(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply { isDaemon = true; start() }
}
