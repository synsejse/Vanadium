package com.synsenetwork.vanadium.featurebench

import com.synsenetwork.vanadium.Vanadium
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.redstone.CollectingNeighborUpdater
import net.minecraft.world.level.redstone.CollectingNeighborUpdater.NeighborUpdates
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer

internal object LockBench {
    fun items(level: ServerLevel, measurements: Measurements) {
        val merge = ItemEntity::class.java.getDeclaredMethod("tryToMerge", ItemEntity::class.java).apply { isAccessible = true }
        val lock = ItemEntity::class.java.getDeclaredField("vanadium\$lock").apply { isAccessible = true }
        for (pairs in intArrayOf(1, 32)) {
            val items = ArrayList<ItemEntity>()
            val individual = ArrayList<ReentrantLock>()
            val jobs = (0 until pairs).map { i ->
                // Nonmatching stacks keep candidate checks repeatable without removals/reset work.
                val a = ItemEntity(level, i * 64.0, 100.0, 0.0, ItemStack(Items.STONE, 16))
                val b = ItemEntity(level, i * 64.0, 100.0, 0.0, ItemStack(Items.DIRT, 16))
                items += listOf(a, b)
                individual += listOf(lock.get(a) as ReentrantLock, lock.get(b) as ReentrantLock)
                Runnable { repeat(128) { merge.invoke(a, b) } }
            }
            val global = ReentrantLock()
            // Same safe merge implementation, but all pairs contend on one lock in the off arm.
            measurements.compare("item-locks-$pairs-pairs", { on ->
                items.forEachIndexed { i, item -> lock.set(item, if (on) individual[i] else global) }
            }) { Vanadium.scheduler.prepare(jobs) }
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
