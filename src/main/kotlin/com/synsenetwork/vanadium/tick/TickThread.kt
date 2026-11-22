package com.synsenetwork.vanadium.tick

import java.util.concurrent.atomic.AtomicLong

/** A pool worker. Fields here give tick code per-thread state without a thread-local lookup. */
abstract class TickThread(name: String) : Thread(name) {
    @JvmField
    var context: Any? = null

    /** Cached [com.synsenetwork.vanadium.concurrent.PerThreadRandomSource] instance and this thread's stream for it. */
    @JvmField
    var randomOwner: Any? = null

    @JvmField
    var random: Any? = null

    /** Per-thread `Level.randValue` LCG state ([com.synsenetwork.vanadium.concurrent.PerThreadRandValue]). */
    @JvmField
    var randValue: Int = nextRandSeed()

    /** Per-thread reusable buffers for [com.synsenetwork.vanadium.concurrent.StableDistance.sortByDistance]. */
    @JvmField
    var sortScratch: Any? = null

    /** Cached primary `CollectingNeighborUpdater` and this thread's delegate updater for it. */
    @JvmField
    var neighborUpdaterOwner: Any? = null

    @JvmField
    var neighborUpdater: Any? = null

    /** Per-thread 1-entry spawn potential charge cache (`NaturalSpawnerSpawnStateMixin`). */
    @JvmField
    var spawnChargeCache: Any? = null

    /** Per-thread multi-cell candidate buffer for [com.synsenetwork.vanadium.entity.CrowdGrid]. */
    @JvmField
    var crowdScratch: Any? = null

    /** Per-thread visited-block table for [com.synsenetwork.vanadium.entity.InsideBlockFastPath]. */
    @JvmField
    var insideBlockScratch: Any? = null

    /** Per-thread pathfinding spatial caches and node pool ([com.synsenetwork.vanadium.navigation.PathfindingFastPath]). */
    @JvmField
    var walkScratch: Any? = null

    /** Per-thread reusable candidate buffer for [com.synsenetwork.vanadium.navigation.NavigationIndex]. */
    @JvmField
    var navScratch: Any? = null

    private companion object {
        private val SEED = AtomicLong(System.nanoTime() xor -0x61c8864680b583ebL)

        private fun nextRandSeed(): Int {
            var z = SEED.addAndGet(-0x61c8864680b583ebL)
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return (z xor (z ushr 31)).toInt()
        }
    }
}
