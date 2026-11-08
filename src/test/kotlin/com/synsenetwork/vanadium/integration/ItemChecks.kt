package com.synsenetwork.vanadium.integration

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.synsenetwork.vanadium.concurrent.ItemLockAccess
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.HopperBlockEntity
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object ItemChecks {
    fun run(level: ServerLevel) {
        val merge = ItemEntity::class.java.getDeclaredMethod("tryToMerge", ItemEntity::class.java).apply { isAccessible = true }
        checkFailureRelease(level)
        repeat(16) {
            val items = List(32) { ItemEntity(level, 0.0, 100.0, 0.0, ItemStack(Items.STONE, 16)) }
            val containers = listOf(SimpleContainer(27), SimpleContainer(27))
            val pool = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().factory())
            try {
                val start = CountDownLatch(1)
                val tasks = (0 until 4).map { worker ->
                    pool.submit {
                        check(start.await(10, TimeUnit.SECONDS), "item start timeout")
                        for (i in 0 until 256) {
                            val first = items[i % items.size]
                            val second = items[(i + 1) % items.size]
                            when (worker) {
                                0 -> merge.invoke(first, second)
                                1 -> merge.invoke(second, first)
                                else -> HopperBlockEntity.addItem(containers[worker - 2], first)
                            }
                        }
                    }
                }
                start.countDown()
                for (task in tasks) task.get(15, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
                check(pool.awaitTermination(5, TimeUnit.SECONDS), "item workers failed to stop")
            }
            val total = items.filterNot { it.isRemoved }.sumOf { it.item.count } +
                    containers.sumOf { container -> (0 until container.containerSize).sumOf { container.getItem(it).count } }
            check(total == 512) { "concurrent merge/pickup changed total: $total" }
            for (item in items) check(item.item.count <= 64, "merge overflowed a stack")
        }
        println("ITEM_MERGE_PICKUP_CHECKS_PASSED rounds=16")
    }

    /** A failing merge must still release both item locks. */
    private fun checkFailureRelease(level: ServerLevel) {
        val first = ItemEntity(level, 0.0, 100.0, 0.0, ItemStack(Items.STONE, 16))
        val second = ItemEntity(level, 0.0, 100.0, 0.0, ItemStack(Items.STONE, 16))
        val wrapper = ItemEntity::class.java.declaredMethods.first { it.name.endsWith("tryMerge") && it.parameterCount == 2 }
            .apply { isAccessible = true }
        try {
            wrapper.invoke(first, second, Operation<Void> { throw IllegalStateException("fixture") })
            throw AssertionError("merge failure swallowed")
        } catch (expected: InvocationTargetException) {
            check(expected.cause is IllegalStateException, "wrong merge failure")
        }
        val executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())
        try {
            executor.submit {
                for (item in listOf(first, second)) {
                    val lock = (item as ItemLockAccess).`vanadium$itemLock`()
                    check(lock.tryLock(), "failed merge retained an item lock")
                    lock.unlock()
                }
            }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
