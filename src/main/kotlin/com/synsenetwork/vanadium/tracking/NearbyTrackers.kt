package com.synsenetwork.vanadium.tracking

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.mixin.server.network.ServerEntityAccessor
import com.synsenetwork.vanadium.mixin.server.world.TrackedEntityAccessor
import com.synsenetwork.vanadium.tick.Preparation
import com.synsenetwork.vanadium.tick.Stage
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.objects.Reference2LongLinkedOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ChunkMap.TrackedEntity
import net.minecraft.server.level.DistanceManager
import net.minecraft.server.level.ServerEntity
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/**
 * Inverted entity-tracking index, replacing the O(entities x players) scans in vanilla's tracking tick.
 * Algorithm derived from VMP-fabric's NearbyEntityTracking (MIT, Copyright (c) ishland), restructured
 * for Vanadium's wave model. The caller maintains the index; independent player diffs prepare actions,
 * then one task per tracker applies them in player order. TRACKING cells ensure each tracker's listener
 * set and entry state are only touched by one worker.
 *
 * Each tracker is painted onto an [AreaMap] square of its own track distance, so "which trackers must
 * consider player P" is one lookup. Freshly loaded trackers spend [STAGING_TICKS] in a brute-force
 * staging queue first: short-lived entities (items, arrows, XP) usually die before ever paying the
 * paint/erase churn. Trackers with no player in range are not ticked; the first watcher triggers a
 * forced full resync (see [forceResync]).
 *
 * [add]/[remove] are synchronized because entity load/unload can run on workers mid-wave (spawns);
 * [tick] runs on the server thread between waves.
 */
class NearbyTrackers {
    private val areaMap = AreaMap<TrackedEntity>()

    /** Each player's known trackers and reusable action row. */
    private val playerDiffs = Reference2ObjectOpenHashMap<ServerPlayer, PlayerDiff>()
    private val trackingTasks = Reference2ObjectOpenHashMap<TrackedEntity, TrackingTask>()

    /** Last observed position identity per player; a fresh key counts as moved. */
    private val playerPrevPos = Reference2ObjectOpenHashMap<ServerPlayer, Vec3>()

    /** Every task in slot order; rebuilt on the server thread after membership changes. */
    private val ordered = ArrayList<TrackingTask>()
    private val moveScans = ArrayList<MoveScan>()
    private var membershipChanged = false

    /** Staged trackers in arrival order, with the tick they were added. */
    private val staging = Reference2LongLinkedOpenHashMap<TrackedEntity>()
    private var tick = 0L

    /**
     * Registers a freshly loaded tracker and runs the initial visibility pass (the same per-player scan
     * vanilla's addEntity does). Called from addEntity in both config modes.
     */
    @Synchronized
    fun add(tracker: TrackedEntity, players: List<ServerPlayer>) {
        (tracker.entity as? ServerPlayer)?.let { playerDiffs[it] = PlayerDiff(it) }
        trackingTasks[tracker] = TrackingTask(tracker)
        membershipChanged = true
        staging.put(tracker, tick)
        for (player in players) tracker.updatePlayer(player)
    }

    /** Unregisters a tracker; the caller still runs vanilla's removal broadcast afterwards. */
    @Synchronized
    fun remove(tracker: TrackedEntity) {
        (tracker.entity as? ServerPlayer)?.let(::removePlayer)
        staging.removeLong(tracker)
        trackingTasks.remove(tracker)?.let { task ->
            membershipChanged = true
            if (task.painted != UNPAINTED) {
                task.painted = UNPAINTED
                areaMap.remove(tracker)
            }
        }
        for (diff in playerDiffs.values) diff.known.remove(tracker)
    }

    private fun removePlayer(player: ServerPlayer) {
        for (tracker in staging.keys) tracker.removePlayer(player)
        playerPrevPos.remove(player)
        playerDiffs.remove(player)?.known?.forEach { it.removePlayer(player) }
    }

    /**
     * Prepares the TRACKING stage: the caller maintains the index, independent player diffs run inline or
     * in parallel, then the caller enqueues per-tracker callbacks into cells. The caller begins the stage
     * before this method and drains those cells after it returns.
     */
    @Synchronized
    fun tick(players: List<ServerPlayer>, tickets: DistanceManager) {
        tick++
        if (membershipChanged) rebuildOrder()
        // Moves matter only to players' diffs. Without players the scan is skipped: moves are detected by
        // position identity against the last scan, so the first scan after a player joins catches up on all
        // of them (and repaints) before the first diff, and the first watcher of a tracker resyncs its entry.
        val scanned = players.isNotEmpty()
        if (scanned) detectMoves()
        migrateStaging()
        tickStaging(players, tickets, scanned)
        diffPlayers(players, tickets)
        for (player in players) playerPrevPos[player] = player.position()
    }

    /** Assigns stable slots and move-scan ranges; no membership change can occur during [tick]. */
    private fun rebuildOrder() {
        membershipChanged = false
        ordered.clear()
        ordered.addAll(trackingTasks.values)
        ordered.forEachIndexed { slot, task -> task.slot = slot }
        moveScans.clear()
        for (start in ordered.indices step TRACKERS_PER_JOB) {
            moveScans.add(MoveScan(start, minOf(ordered.size, start + TRACKERS_PER_JOB)))
        }
    }

    /**
     * Records which trackers moved since last tick, then repaints chunk-crossers. Each tracker's scan
     * writes only its own task fields, so large populations scan in parallel; only the caller updates
     * the area map.
     */
    private fun detectMoves() {
        if (ordered.size < MIN_PARALLEL_TRACKERS || moveScans.size < 2) moveScans.forEach(MoveScan::run)
        else Vanadium.scheduler.prepare(Preparation.TRACKING_MOVES, moveScans)
        // Only chunk-crossers, in slot order as before; each scan listed its own.
        for (scan in moveScans) {
            val repaints = scan.repaints
            for (j in 0 until repaints.size) {
                val task = ordered[repaints.getInt(j)]
                task.repaint = false
                val chunk = task.tracker.entity.chunkPosition()
                areaMap.update(task.tracker, chunk.x(), chunk.z(), task.tracker.radius)
                task.painted = task.chunk
            }
        }
    }

    /** One contiguous slice of trackers; independent of every other slice. */
    private inner class MoveScan(private val start: Int, private val end: Int) : Runnable {
        /** Slots in this slice that crossed a chunk while painted, ascending. */
        val repaints = IntArrayList()

        override fun run() {
            repaints.clear()
            for (i in start until end) {
                val task = ordered[i]
                val entity = task.tracker.entity
                val position = entity.position()
                task.moved = position !== task.prevPos
                if (!task.moved) continue
                task.prevPos = position
                val chunk = entity.chunkPosition().pack()
                task.chunk = chunk
                task.repaint = task.painted != UNPAINTED && chunk != task.painted
                if (task.repaint) repaints.add(i)
                // Vanilla's own tracking loop reads this; keep it current without allocating per move.
                val access = task.tracker.access
                val section = access.`vanadium$trackedSection`()
                val sx = SectionPos.blockToSectionCoord(entity.blockX)
                val sy = SectionPos.blockToSectionCoord(entity.blockY)
                val sz = SectionPos.blockToSectionCoord(entity.blockZ)
                if (section == null || section.x() != sx || section.y() != sy || section.z() != sz) {
                    access.`vanadium$setTrackedSection`(SectionPos.of(sx, sy, sz))
                }
            }
        }
    }

    /** Moves surviving trackers into the area map in arrival order, after their staging window. */
    private fun migrateStaging() {
        while (!staging.isEmpty()) {
            val tracker = staging.firstKey()
            if (tick - staging.getLong(tracker) <= STAGING_TICKS) break
            staging.removeFirstLong()
            val chunk = tracker.entity.chunkPosition()
            areaMap.add(tracker, chunk.x(), chunk.z(), tracker.radius)
            trackingTasks[tracker]!!.painted = chunk.pack()
        }
    }

    /**
     * Staged trackers update their status against every player on every tick. Without this tick's scan,
     * a staged tracker counts as moved while its position differs from the last scanned one.
     */
    private fun tickStaging(players: List<ServerPlayer>, tickets: DistanceManager, scanned: Boolean) {
        for (tracker in staging.keys) {
            val entity = tracker.entity
            val task = trackingTasks[tracker]!!
            val moved = if (scanned) task.moved else entity.position() !== task.prevPos
            val shouldTick = moved || entity.needsSync || tickets.inEntityTickingRange(entity.chunkPosition().pack())
            enqueue(tracker) {
                if (shouldTick) tracker.entry.sendChanges()
                for (player in players) tracker.updatePlayer(player)
            }
        }
    }

    /** Diffs each player's in-range tracker set against what they knew last tick. */
    private fun diffPlayers(players: List<ServerPlayer>, tickets: DistanceManager) {
        if (players.isEmpty()) return
        val diffs = players.mapNotNull { player ->
            playerDiffs[player]?.apply {
                current = areaMap.objectsAt(player.chunkPosition().pack())
                playerMoved = playerPrevPos[player] !== player.position()
                this.tickets = tickets
                if (actions.size < ordered.size) actions = ByteArray(maxOf(ordered.size, actions.size * 2))
            }
        }
        // tick() excludes membership/index changes. Each job owns exactly one player's known set; workers
        // write separate action arrays and never invoke tracking callbacks. Slots are assigned on the
        // caller and remain stable through both preparation and tracking barriers.
        val comparisons = diffs.sumOf { it.known.size.toLong() + it.current.size }
        if (diffs.size < 2 || comparisons < MIN_PARALLEL_COMPARISONS) diffs.forEach(PlayerDiff::run)
        else Vanadium.scheduler.prepare(Preparation.TRACKING_DIFFS, diffs)
        for (task in ordered) {
            if (diffs.any { it.actions[task.slot].toInt() != 0 }) {
                task.diffs = diffs
                enqueue(task.tracker, task)
            }
        }
    }

    private inner class PlayerDiff(val player: ServerPlayer) : Runnable {
        val known = ReferenceOpenHashSet<TrackedEntity>()
        var current: Set<TrackedEntity> = emptySet()
        var playerMoved = false
        lateinit var tickets: DistanceManager
        var actions = ByteArray(0)

        override fun run() {
            actions.fill(0, 0, ordered.size)
            val iterator = known.iterator()
            while (iterator.hasNext()) {
                val tracker = iterator.next()
                if (tracker !in current) {
                    iterator.remove()
                    actions[trackingTasks[tracker]!!.slot] = REMOVE.toByte()
                }
            }
            for (tracker in current) {
                val fresh = known.add(tracker)
                val task = trackingTasks[tracker]!!
                val entity = tracker.entity
                val send = entity.needsSync || tickets.inEntityTickingRange(entity.chunkPosition().pack())
                val update = fresh || playerMoved || task.moved
                if (send || update) actions[task.slot] = ((if (send) SEND else 0) or (if (update) UPDATE else 0)).toByte()
            }
        }
    }

    /** Reads the completed player-owned rows; one scheduled task owns all callbacks for this tracker. */
    private class TrackingTask(val tracker: TrackedEntity) : Runnable {
        var slot = 0
        var diffs: List<PlayerDiff>? = null

        /** Position identity at the previous scan; null counts as moved. */
        var prevPos: Vec3? = null

        /** Chunk the tracker's area-map square is centered on, or [UNPAINTED] while staged. */
        var painted = UNPAINTED
        var chunk = 0L
        var moved = false
        var repaint = false

        override fun run() {
            var sent = false
            try {
                for (diff in diffs!!) {
                    val action = diff.actions[slot].toInt()
                    if (action and REMOVE != 0) {
                        tracker.removePlayer(diff.player)
                        continue
                    }
                    if (!sent && action and SEND != 0) {
                        tracker.entry.sendChanges()
                        sent = true
                    }
                    if (action and UPDATE != 0) tracker.updatePlayer(diff.player)
                }
            } finally {
                diffs = null
            }
        }
    }

    private fun enqueue(tracker: TrackedEntity, task: Runnable) {
        val chunk = tracker.entity.chunkPosition()
        Vanadium.scheduler.enqueue(Stage.TRACKING, chunk.x(), chunk.z(), task, null)
    }

    companion object {
        private const val STAGING_TICKS = 200
        private const val MIN_PARALLEL_COMPARISONS = 1024L

        /**
         * Below this many trackers (two jobs), move detection runs inline on the caller. A scan costs roughly
         * 0.3–0.6 µs per tracker (0.17–0.35 ms for 600 on the Xeon VM). A wave of k jobs wakes only k - 1
         * helpers, the caller claims jobs too, and helpers that wake late find nothing left, so the caller pays
         * a few microseconds per helper plus at most one job's tail: one job of 128 trackers (about 50 µs).
         */
        private const val MIN_PARALLEL_TRACKERS = 256
        private const val TRACKERS_PER_JOB = 128
        private const val UNPAINTED = Long.MIN_VALUE
        /** Per-player action flags for one tracker, stored as bytes. */
        private const val REMOVE = 1
        private const val SEND = 2
        private const val UPDATE = 4

        private val TrackedEntity.access: TrackedEntityAccessor get() = this as TrackedEntityAccessor
        private val TrackedEntity.entity: Entity get() = access.`vanadium$entity`()
        private val TrackedEntity.entry: ServerEntity get() = access.`vanadium$entry`()
        private val TrackedEntity.radius: Int get() = ceil(access.`vanadium$maxTrackDistance`() / 16.0).toInt() + 1

        /**
         * Forces the entry's next tick to send a full absolute position/velocity/data sync. Watcher-less
         * trackers are not ticked, so their entry state goes stale; this runs just before the first
         * listener is added (while the listener set is still empty, so nothing is sent) to re-baseline it.
         */
        @JvmStatic
        fun forceResync(entry: ServerEntity) {
            val access = entry as ServerEntityAccessor
            access.`vanadium$entity`().syncPosition = true
            access.`vanadium$setUpdatesWithoutVehicle`(1 shl 16)
            access.`vanadium$entity`().needsSync = true
            entry.sendChanges()
        }
    }
}
