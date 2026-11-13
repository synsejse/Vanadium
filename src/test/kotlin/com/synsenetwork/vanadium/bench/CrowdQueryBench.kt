package com.synsenetwork.vanadium.bench

import com.sun.management.ThreadMXBean
import com.synsenetwork.vanadium.entity.CrowdAccess
import com.synsenetwork.vanadium.entity.CrowdedSection
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.AbortableIterationConsumer
import net.minecraft.util.Continuation
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.entity.EntityAccess
import net.minecraft.world.level.entity.EntityInLevelCallback
import net.minecraft.world.phys.AABB
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import java.util.stream.Stream
import kotlin.random.Random

/**
 * Push-sized box queries (each entity's own box) in one entity section holding n mobs on flat ground: the
 * full scan Vanadium's copy-on-write section list gets without a grid, against [CrowdedSection]'s cells with
 * the scan as fallback. Readers query while writers move entities at full speed (far more moves per query
 * than a tick makes). Synthetic; no Minecraft server or performance assertions.
 */
object CrowdQueryBench {
    private const val WARMUP_MILLIS = 400
    private const val MEASURED = 200_000

    @Volatile
    private var sink = 0L

    private class Mob(@JvmField var box: AABB) : EntityAccess, CrowdAccess {
        @Volatile private var crowd: CrowdedSection.Member<*>? = null
        override fun `vanadium$crowd`(): CrowdedSection.Member<*>? = crowd
        override fun `vanadium$setCrowd`(member: CrowdedSection.Member<*>?) {
            crowd = member
        }

        /** As `EntityMixin.vanadium$moveInCells`. */
        @Suppress("UNCHECKED_CAST")
        fun move(next: AABB) {
            val member = crowd as CrowdedSection.Member<EntityAccess>?
            if (member == null) {
                box = next
                return
            }
            val grid = member.grid
            val range = grid.rangeOf(next)
            if (range == member.range || !grid.beginMove(member, range)) {
                box = next
                return
            }
            try {
                box = next
            } finally {
                grid.endMove(member)
            }
        }

        override fun getBoundingBox(): AABB = box
        override fun getId(): Int = 0
        override fun blockPosition(): BlockPos = BlockPos.ZERO
        override fun setLevelCallback(callback: EntityInLevelCallback) = Unit
        override fun getSelfAndPassengers(): Stream<out EntityAccess> = Stream.of(this)
        override fun getPassengersAndSelf(): Stream<out EntityAccess> = Stream.of(this)
        override fun setRemoved(removalReason: Entity.RemovalReason) = Unit
        override fun shouldBeSaved(): Boolean = false
        override fun isAlwaysTicking(): Boolean = false
        override fun getUUID(): UUID = UUID(0, 0)
        override fun isRemoved(): Boolean = false
    }

    private class Setup(n: Int, cells: Boolean) {
        val key = SectionPos.asLong(0, -4, 0) // Blocks y -64..-49; mobs stand at -60 as on a superflat world.
        val list = CopyOnWriteArrayList<EntityAccess>()
        val grid = if (cells) CrowdedSection<EntityAccess>(key, 0L) else null
        val mobs = List(n) { i ->
            val r = Random(i)
            Mob(box(r.nextDouble(0.3, 15.7), r.nextDouble(0.3, 15.7)))
        }

        init {
            for (mob in mobs) {
                if (grid == null) list.add(mob) else synchronized(grid) {
                    list.add(mob)
                    grid.addLocked(mob)
                }
            }
        }

        fun scan(box: AABB, out: AbortableIterationConsumer<EntityAccess>) {
            for (entity in list) if (entity.boundingBox.intersects(box) && out.accept(entity).shouldAbort()) return
        }
    }

    private fun box(x: Double, z: Double) = AABB(x - 0.3, -60.0, z - 0.3, x + 0.3, -58.05, z + 0.3)

    @JvmStatic
    fun main(args: Array<String>) {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        println("warmupMs=$WARMUP_MILLIS measured=$MEASURED/reader cpus=${Runtime.getRuntime().availableProcessors()} java=${System.getProperty("java.version")}")
        println("Readers only, and stress: 2 extra threads move mobs at full speed while readers query.")
        println("mobs,readers,writers,layout,ns/query,bytes/query,found/query,fallback%,moves/query")
        for (n in intArrayOf(10, 50, 200)) {
            for ((readers, writers) in listOf(1 to 0, 4 to 0, 4 to 2)) {
                for (cells in booleanArrayOf(false, true)) {
                    val setup = Setup(n, cells)
                    val r = run(setup, readers, writers, bean)
                    println(String.format(Locale.ROOT, "%d,%d,%d,%s,%.1f,%d,%.2f,%.2f,%.2f", n, readers, writers,
                        if (cells) "cells" else "scan", r.nanos, r.bytes, r.found, r.fallbackPercent, r.movesPerQuery))
                }
            }
        }
        println("Ticks: each thread moves one of its own mobs, then queries that mob's box (its push), then works busyNs elsewhere.")
        println("mobs,threads,busyNs,layout,ns/(move+query),bytes/tick,found/query,fallback%")
        for (n in intArrayOf(10, 50, 200)) {
            for (busy in longArrayOf(0L, 2_000L)) {
                for (cells in booleanArrayOf(false, true)) {
                    val r = ticks(Setup(n, cells), 4, busy, bean)
                    println(String.format(Locale.ROOT, "%d,%d,%d,%s,%.1f,%d,%.2f,%.2f", n, 4, busy,
                        if (cells) "cells" else "scan", r.nanos, r.bytes, r.found, r.fallbackPercent))
                }
            }
        }
        println("sink=$sink")
    }

    private const val TICKS = 50_000

    /** [threads] entity-tick-shaped loops: each owns every threads-th mob, moves it, then pushes from it. */
    private fun ticks(setup: Setup, threads: Int, busyNanos: Long, bean: ThreadMXBean): Result {
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val nanos = LongArray(threads)
        val bytes = LongArray(threads)
        val found = LongArray(threads)
        val fallbacks = LongArray(threads)
        fun work(t: Int, count: Int, record: Boolean) {
            val rng = Random(t * 31 + count)
            val own = setup.mobs.filterIndexed { i, _ -> i % threads == t }
            val out = AbortableIterationConsumer<EntityAccess> { found[t]++; Continuation.CONTINUE }
            val grid = setup.grid
            var spent = 0L
            var fell = 0L
            repeat(count) {
                val mob = own[rng.nextInt(own.size)]
                val c = mob.box.center
                val x = (c.x + rng.nextDouble(-0.25, 0.25)).coerceIn(0.3, 15.7)
                val z = (c.z + rng.nextDouble(-0.25, 0.25)).coerceIn(0.3, 15.7)
                val next = box(x, z)
                val start = System.nanoTime()
                mob.move(next)
                if (grid == null) {
                    setup.scan(next, out)
                } else if (grid.query(next, out) == null) {
                    fell++
                    setup.scan(next, out)
                }
                val end = System.nanoTime()
                spent += end - start
                if (busyNanos > 0) while (System.nanoTime() - end < busyNanos) Thread.onSpinWait()
            }
            if (record) {
                nanos[t] = spent
                fallbacks[t] = fell
            } else {
                found[t] = 0
            }
        }
        val workers = List(threads) { t ->
            Thread {
                val warmupEnd = System.nanoTime() + WARMUP_MILLIS * 1_000_000L
                do work(t, 1_000, false) while (System.nanoTime() < warmupEnd)
                ready.countDown()
                check(go.await(30, TimeUnit.SECONDS)) { "bench gate timed out" }
                val id = Thread.currentThread().threadId()
                val before = bean.getThreadAllocatedBytes(id)
                work(t, TICKS, true)
                bytes[t] = bean.getThreadAllocatedBytes(id) - before
            }.apply { start() }
        }
        check(ready.await(60, TimeUnit.SECONDS)) { "bench warmup timed out" }
        go.countDown()
        workers.forEach { it.join(TimeUnit.MINUTES.toMillis(5)) }
        val total = TICKS.toLong() * threads
        sink += found.sum()
        return Result(nanos.sum().toDouble() / total, bytes.sum() / total, found.sum().toDouble() / total,
            100.0 * fallbacks.sum() / total, 1.0)
    }

    private class Result(val nanos: Double, val bytes: Long, val found: Double, val fallbackPercent: Double, val movesPerQuery: Double)

    private fun run(setup: Setup, readers: Int, writers: Int, bean: ThreadMXBean): Result {
        val stop = AtomicBoolean()
        val moves = LongAdder()
        val writerThreads = List(writers) { w ->
            Thread {
                val rng = Random(1000 + w)
                val own = setup.mobs.filterIndexed { i, _ -> i % writers == w }
                while (!stop.get()) {
                    val mob = own[rng.nextInt(own.size)]
                    val c = mob.box.center
                    val x = (c.x + rng.nextDouble(-0.25, 0.25)).coerceIn(0.3, 15.7)
                    val z = (c.z + rng.nextDouble(-0.25, 0.25)).coerceIn(0.3, 15.7)
                    mob.move(box(x, z))
                    moves.increment()
                }
            }.apply { isDaemon = true; start() }
        }
        val ready = CountDownLatch(readers)
        val go = CountDownLatch(1)
        val nanos = LongArray(readers)
        val bytes = LongArray(readers)
        val found = LongArray(readers)
        val fallbacks = LongArray(readers)
        fun work(seed: Int, count: Int, t: Int, record: Boolean) {
            val rng = Random(seed)
            var hits = 0L
            var fell = 0L
            val out = AbortableIterationConsumer<EntityAccess> { hits++; Continuation.CONTINUE }
            val grid = setup.grid
            repeat(count) {
                val box = setup.mobs[rng.nextInt(setup.mobs.size)].box
                if (grid == null) {
                    setup.scan(box, out)
                } else if (grid.query(box, out) == null) {
                    fell++
                    setup.scan(box, out)
                }
            }
            sink += hits
            if (record) {
                found[t] = hits
                fallbacks[t] = fell
            }
        }
        val threads = List(readers) { t ->
            Thread {
                val warmupEnd = System.nanoTime() + WARMUP_MILLIS * 1_000_000L
                do work(t + 100, 1_000, t, false) while (System.nanoTime() < warmupEnd)
                ready.countDown()
                check(go.await(30, TimeUnit.SECONDS)) { "bench gate timed out" }
                val id = Thread.currentThread().threadId()
                val before = bean.getThreadAllocatedBytes(id)
                val start = System.nanoTime()
                work(t, MEASURED, t, true)
                nanos[t] = System.nanoTime() - start
                bytes[t] = bean.getThreadAllocatedBytes(id) - before
            }.apply { start() }
        }
        check(ready.await(60, TimeUnit.SECONDS)) { "bench warmup timed out" }
        val movesAtStart = moves.sum()
        go.countDown()
        threads.forEach { it.join(TimeUnit.MINUTES.toMillis(5)) }
        val measuredMoves = moves.sum() - movesAtStart
        stop.set(true)
        writerThreads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        val queries = MEASURED.toLong() * readers
        return Result(nanos.average() / MEASURED, bytes.sum() / queries, found.sum().toDouble() / queries,
            100.0 * fallbacks.sum() / queries, measuredMoves.toDouble() / queries)
    }
}
