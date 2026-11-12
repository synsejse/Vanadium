package com.synsenetwork.vanadium.bench

import com.sun.management.ThreadMXBean
import com.synsenetwork.vanadium.entity.EntitySectionIndex
import net.minecraft.core.SectionPos
import net.minecraft.util.AbortableIterationConsumer
import net.minecraft.util.Continuation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntitySection
import net.minecraft.world.level.entity.Visibility
import org.jctools.maps.NonBlockingHashMapLong
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Box queries over a populated entity section index: the column layout against the previous two-map
 * layout (column Ys plus a per-section lookup). Synthetic; no Minecraft server or performance assertions.
 */
object SectionIndexBench {
    private const val WARMUP_MILLIS = 500
    private const val MEASURED = 200_000
    private const val AREA = 48 // chunks per side, centred on 0 so queries cross negative coordinates

    @Volatile
    private var sink = 0L

    private interface Index {
        fun query(xMin: Int, yMin: Int, zMin: Int, xMax: Int, yMax: Int, zMax: Int, output: AbortableIterationConsumer<EntitySection<EntityAccess>>)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val entity = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EntityAccess::class.java)) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                else -> null
            }
        } as EntityAccess
        // About 70% of columns hold sections, mostly one or two around the surface, sometimes a cave one.
        val random = Random(7)
        val keys = ArrayList<Long>()
        for (x in -AREA / 2 until AREA / 2) for (z in -AREA / 2 until AREA / 2) {
            if (random.nextInt(10) >= 7) continue
            keys.add(SectionPos.asLong(x, 4, z))
            if (random.nextInt(3) == 0) keys.add(SectionPos.asLong(x, 3 + random.nextInt(0, 3) * 2 - 2, z))
            if (random.nextInt(8) == 0) keys.add(SectionPos.asLong(x, -2, z))
        }
        val current = EntitySectionIndex<EntityAccess>()
        val legacy = LegacyIndex()
        for (key in keys) {
            val section = EntitySection(EntityAccess::class.java, Visibility.TICKING).apply { add(entity) }
            current.getOrCreate(key) { section }
            legacy.put(key, section)
        }
        val layouts = listOf<Pair<String, Index>>(
            "column" to object : Index {
                override fun query(xMin: Int, yMin: Int, zMin: Int, xMax: Int, yMax: Int, zMax: Int,
                                   output: AbortableIterationConsumer<EntitySection<EntityAccess>>) =
                    current.forEachAccessibleNonEmpty(xMin, yMin, zMin, xMax, yMax, zMax, output)
            },
            "two-map" to object : Index {
                override fun query(xMin: Int, yMin: Int, zMin: Int, xMax: Int, yMax: Int, zMax: Int,
                                   output: AbortableIterationConsumer<EntitySection<EntityAccess>>) =
                    legacy.forEachAccessibleNonEmpty(xMin, yMin, zMin, xMax, yMax, zMax, output)
            },
        )
        val cpus = Runtime.getRuntime().availableProcessors()
        println("warmupMs=$WARMUP_MILLIS measured=$MEASURED/thread sections=${keys.size} area=${AREA}x$AREA cpus=$cpus java=${System.getProperty("java.version")}")
        println("query,threads,layout,ns/query,bytes/query,sections/query")
        // Block radius around a random point: 37 is a 35-block target search plus the 2-block grace, 3 a push.
        for ((name, radius) in listOf("target35" to 37, "push" to 3)) {
            for (threads in intArrayOf(1, 4)) {
                for ((layout, index) in layouts) {
                    val results = runThreads(threads, bean) { seed, measured ->
                        val rng = Random(seed)
                        var visited = 0L
                        val output = AbortableIterationConsumer<EntitySection<EntityAccess>> { visited += it.size(); Continuation.CONTINUE }
                        val span = AREA * 16 - 2 * radius
                        repeat(measured) {
                            val x = rng.nextInt(span) - span / 2
                            val z = rng.nextInt(span) - span / 2
                            val y = 64 + rng.nextInt(-8, 8)
                            index.query(SectionPos.blockToSectionCoord(x - radius), SectionPos.blockToSectionCoord(y - 8),
                                SectionPos.blockToSectionCoord(z - radius), SectionPos.blockToSectionCoord(x + radius),
                                SectionPos.blockToSectionCoord(y + 4), SectionPos.blockToSectionCoord(z + radius), output)
                        }
                        visited
                    }
                    println(String.format(Locale.ROOT, "%s,%d,%s,%.1f,%d,%.2f", name, threads, layout, results.nanos, results.bytes,
                        results.visited.toDouble() / (MEASURED.toLong() * threads)))
                }
            }
        }
        println("sink=$sink")
    }

    private class Result(val nanos: Double, val bytes: Long, val visited: Long)

    /** Runs [work] on [threads] threads after a common warmup; per-query time averaged over threads. */
    private fun runThreads(threads: Int, bean: ThreadMXBean, work: (seed: Int, measured: Int) -> Long): Result {
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val nanos = LongArray(threads)
        val bytes = LongArray(threads)
        val visited = LongArray(threads)
        val workers = List(threads) { t ->
            Thread {
                val warmupEnd = System.nanoTime() + WARMUP_MILLIS * 1_000_000L
                do sink += work(t + 100, 1_000) while (System.nanoTime() < warmupEnd)
                ready.countDown()
                check(go.await(30, TimeUnit.SECONDS)) { "bench gate timed out" }
                val id = Thread.currentThread().threadId()
                val before = bean.getThreadAllocatedBytes(id)
                val start = System.nanoTime()
                visited[t] = work(t, MEASURED)
                nanos[t] = System.nanoTime() - start
                bytes[t] = bean.getThreadAllocatedBytes(id) - before
            }.apply { start() }
        }
        check(ready.await(60, TimeUnit.SECONDS)) { "bench warmup timed out" }
        go.countDown()
        workers.forEach { it.join(TimeUnit.MINUTES.toMillis(5)) }
        sink += visited.sum()
        return Result(nanos.average() / MEASURED, bytes.sum() / (MEASURED.toLong() * threads), visited.sum())
    }

    /** The previous layout: chunk key → sorted Ys, and section key → section. */
    private class LegacyIndex {
        private val sections = NonBlockingHashMapLong<EntitySection<EntityAccess>>()
        private val columns = NonBlockingHashMapLong<IntArray>()

        fun put(key: Long, section: EntitySection<EntityAccess>) {
            sections.put(key, section)
            val chunk = ChunkPos.pack(SectionPos.x(key), SectionPos.z(key))
            val y = SectionPos.y(key)
            val ys = columns[chunk] ?: IntArray(0)
            var at = 0
            while (at < ys.size && (ys[at] and 0xFFFFF) < (y and 0xFFFFF)) at++
            val next = IntArray(ys.size + 1)
            ys.copyInto(next, 0, 0, at)
            next[at] = y
            ys.copyInto(next, at + 1, at)
            columns.put(chunk, next)
        }

        fun forEachAccessibleNonEmpty(xMin: Int, yMin: Int, zMin: Int, xMax: Int, yMax: Int, zMax: Int,
                                      output: AbortableIterationConsumer<EntitySection<EntityAccess>>) {
            for (x in xMin..xMax) {
                if (zMin < 0 && zMax >= 0) {
                    for (z in 0..zMax) if (visitColumn(x, z, yMin, yMax, output)) return
                    for (z in zMin..-1) if (visitColumn(x, z, yMin, yMax, output)) return
                } else {
                    for (z in zMin..zMax) if (visitColumn(x, z, yMin, yMax, output)) return
                }
            }
        }

        private fun visitColumn(x: Int, z: Int, yMin: Int, yMax: Int, output: AbortableIterationConsumer<EntitySection<EntityAccess>>): Boolean {
            val ys = columns[ChunkPos.pack(x, z)] ?: return false
            for (y in ys) {
                if (y < yMin || y > yMax) continue
                val section = sections[SectionPos.asLong(x, y, z)] ?: continue
                if (!section.isEmpty && section.status.isAccessible && output.accept(section).shouldAbort()) return true
            }
            return false
        }
    }
}
