package com.synsenetwork.vanadium.bench

import com.sun.management.ThreadMXBean
import com.synsenetwork.vanadium.tracking.AreaMap
import net.minecraft.world.level.ChunkPos
import java.lang.management.ManagementFactory
import java.util.Locale

/** Synthetic index maintenance benchmark; no Minecraft server or performance assertions. */
object AreaMapBench {
    private const val WARMUP_MILLIS = 250
    private const val MEASURED = 50_000

    @Volatile
    private var sink = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        println("warmupMs=$WARMUP_MILLIS measured=$MEASURED thread=caller java=${System.getProperty("java.version")}")
        println("radius,workload,ns/update,bytes/update")
        for (radius in intArrayOf(2, 8, 16)) {
            for (workload in listOf("stationary", "one-chunk", "diagonal", "teleport", "resize")) {
                val map = AreaMap<Any>()
                val mover = Any()
                map.add(Any(), 0, 0, radius + 2) // overlapping, stationary coverage
                map.add(mover, 0, 0, radius)
                val dx = when (workload) {
                    "teleport" -> radius * 3
                    "stationary", "resize" -> 0
                    else -> 1
                }
                val dz = if (workload == "diagonal") 1 else 0
                val nextRadius = if (workload == "resize") radius + 1 else radius
                fun update(i: Int) {
                    val moved = i and 1 == 0
                    map.update(mover, if (moved) dx else 0, if (moved) dz else 0, if (moved) nextRadius else radius)
                }
                val warmupEnd = System.nanoTime() + WARMUP_MILLIS * 1_000_000L
                do repeat(1000, ::update) while (System.nanoTime() < warmupEnd)
                val thread = Thread.currentThread().threadId()
                val bytesBefore = bean.getThreadAllocatedBytes(thread)
                val start = System.nanoTime()
                repeat(MEASURED, ::update)
                val elapsed = System.nanoTime() - start
                val bytes = bean.getThreadAllocatedBytes(thread) - bytesBefore
                sink += map.objectsAt(ChunkPos.pack(0, 0)).size
                println(String.format(Locale.ROOT, "%d,%s,%.1f,%d", radius, workload, elapsed.toDouble() / MEASURED, bytes / MEASURED))
            }
        }
        println("sink=$sink")
    }
}
