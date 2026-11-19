package com.synsenetwork.vanadium.featurebench

import com.synsenetwork.vanadium.concurrent.Interactions
import com.synsenetwork.vanadium.tick.WorkerPool
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.redstone.CollectingNeighborUpdater
import net.minecraft.world.level.redstone.CollectingNeighborUpdater.NeighborUpdates

internal object LockBench {
    /**
     * Item stacks lying together each try to merge with the other in the same tick (the pairwise case
     * `ItemEntityMixin` protects). On: one flat entity wave on [pool], every merge inside its pair scope (both
     * owners' locks, then the recheck). Off: the same ticks serially on the caller, outside any wave, as with
     * `enabled = false`. Nonmatching stacks keep every merge attempt repeatable without removals.
     */
    fun items(level: ServerLevel, measurements: Measurements, pool: WorkerPool) {
        val merge = ItemEntity::class.java.getDeclaredMethod("tryToMerge", ItemEntity::class.java).apply { isAccessible = true }
        for (pairs in intArrayOf(1, 32)) {
            val ticks = (0 until pairs).flatMap { i ->
                val a = ItemEntity(level, i * 64.0, 100.0, 0.0, ItemStack(Items.STONE, 16))
                val b = ItemEntity(level, i * 64.0, 100.0, 0.0, ItemStack(Items.DIRT, 16))
                listOf(a to b, b to a).map { (self, other) ->
                    Runnable { Interactions.tick(self, Consumer { repeat(128) { merge.invoke(self, other) } }) }
                }
            }
            val wave = Runnable { pool.runWave(ticks) }
            val name = "item-merge-$pairs-pairs"
            // Each arm's route: the wave takes a pair scope for every merge attempt, the serial ticks none.
            Interactions.takeCounts()
            Interactions.runWave(wave, null)
            val scoped = Interactions.takeCounts()[Interactions.Write.PAIR]
            ticks.forEach(Runnable::run)
            val serial = Interactions.takeCounts()[Interactions.Write.PAIR]
            check(scoped == ticks.size * 128L && serial == 0L) {
                "$name: ${ticks.size * 128} merges took $scoped pair scopes in the wave and $serial serially"
            }
            var parallel = true
            measurements.compare(name, { parallel = it }) {
                if (parallel) Interactions.runWave(wave, null) else ticks.forEach(Runnable::run)
            }
            check(!Interactions.active) { "$name left the entity wave active" }
        }
    }

    fun neighbors(level: ServerLevel, measurements: Measurements) {
        val add = CollectingNeighborUpdater::class.java.getDeclaredMethod("addAndRun", BlockPos::class.java, NeighborUpdates::class.java)
            .apply { isAccessible = true }
        val added = CollectingNeighborUpdater::class.java.getDeclaredField("addedThisLayer").apply { isAccessible = true }
        for (width in intArrayOf(6, 256)) {
            val updater = CollectingNeighborUpdater(level, 10000)
            val array = ArrayList<NeighborUpdates>()
            val copy = CopyOnWriteArrayList<NeighborUpdates>()
            val completed = LongArray(1)
            val leaf = object : NeighborUpdates {
                override fun runNext(level: Level): Boolean {
                    completed[0]++
                    return false
                }

                override fun forEachUpdatedPos(output: Consumer<BlockPos>) = Unit
            }
            val root = object : NeighborUpdates {
                override fun runNext(level: Level): Boolean {
                    repeat(width) { add.invoke(updater, BlockPos.ZERO, leaf) }
                    return false
                }

                override fun forEachUpdatedPos(output: Consumer<BlockPos>) = Unit
            }
            measurements.compare("neighbor-queue-width-$width", { on -> added.set(updater, if (on) array else copy) }) {
                add.invoke(updater, BlockPos.ZERO, root)
            }
            FeatureBench.sink = completed
        }
    }
}
