package com.synsenetwork.vanadium.tick

import net.minecraft.world.level.ChunkPos
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchEnqueueTest {
    /** Item i belongs to chunk (i % 5 - 2, i % 3 - 1); every seventh is serial, every eleventh skipped. */
    private class Item(val index: Int)

    private class Mapper : BatchEnqueue.Mapper<Item> {
        val ran = ConcurrentHashMap<Long, MutableList<Int>>()

        override fun key(item: Item): Long = when {
            item.index % 11 == 0 -> BatchEnqueue.SKIP
            item.index % 7 == 0 -> BatchEnqueue.SERIAL
            else -> ChunkPos.pack(item.index % 5 - 2, item.index % 3 - 1)
        }

        override fun task(item: Item): Runnable {
            val key = key(item)
            return Runnable { ran.computeIfAbsent(key) { Collections.synchronizedList(ArrayList()) }.add(item.index) }
        }
    }

    @Test
    fun keepsRecordingOrderPerChunkAndRunsSerialItemsInline() {
        for (size in intArrayOf(10, 3000)) { // inline and parallel mapping paths
            val pool = WorkerPool(4)
            val scheduler = TickScheduler(pool, 1)
            val mapper = Mapper()
            val batch = BatchEnqueue(mapper)
            val serial = ArrayList<Int>()
            repeat(2) {
                mapper.ran.clear()
                serial.clear()
                scheduler.begin(Stage.ENTITY)
                for (i in 0 until size) batch.add(Item(i))
                batch.dispatch(scheduler, Stage.ENTITY) { serial.add(it.index) }
                scheduler.run(Stage.ENTITY)

                val kept = (0 until size).filter { it % 11 != 0 }
                assertEquals(kept.filter { it % 7 == 0 }, serial)
                for (values in mapper.ran.values) assertEquals(values.sorted(), values, "order changed")
                assertEquals(kept.count { it % 7 != 0 }, mapper.ran.values.sumOf { it.size },
                    "batch must be cleared and fully dispatched each round")
            }
            pool.close()
        }
    }
}
