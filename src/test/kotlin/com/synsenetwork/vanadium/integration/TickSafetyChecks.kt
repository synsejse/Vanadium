package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.concurrent.PerThreadRandomSource
import com.synsenetwork.vanadium.tick.EntityTickRules
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.WorkerPool
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.BlockEventData
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.ticks.LevelChunkTicks
import net.minecraft.world.ticks.LevelTicks
import net.minecraft.world.ticks.ScheduledTick
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiConsumer

/** Exercises transformed vanilla classes, including the actual scheduled-tick dispatch injection. */
internal object TickSafetyChecks {
    fun run(level: ServerLevel) {
        checkScheduledTicks(level)
        checkPassengers(level)
        checkBlockEvents(level)
        checkRandomPositions(level)
        println("TICK_SAFETY_CHECKS_PASSED")
    }

    private fun checkScheduledTicks(level: ServerLevel) {
        val dispatch = ServerLevel::class.java.declaredMethods.first { it.name.endsWith("dispatchScheduledTicks") }
            .apply { isAccessible = true }
        fun run(ticks: LevelTicks<String>, time: Long, callback: (BlockPos, String) -> Unit) =
            dispatch.invoke(level, ticks, time, 100, BiConsumer(callback))
        val previous = Vanadium.scheduler
        val enabled = Vanadium.config.enabled
        val parallel = Vanadium.config.parallelScheduledTicks
        WorkerPool(2).use { pool ->
            try {
                // Every wave uses both threads, whatever its measured cost: the check waits for two callbacks at once.
                Vanadium.scheduler = TickScheduler(pool, 1, participantCostNanos = 0.0)
                Vanadium.config.enabled = true
                Vanadium.config.parallelScheduledTicks = true
                val ticks = LevelTicks<String> { true }
                ticks.addContainer(ChunkPos(0, 0), LevelChunkTicks())
                ticks.addContainer(ChunkPos(2, 0), LevelChunkTicks())
                val first = BlockPos(0, 100, 0)
                val next = first.offset(1, 0, 0)
                val cancelled = first.offset(2, 0, 0)
                ticks.schedule(ScheduledTick("test", first, 0, 0))
                ticks.schedule(ScheduledTick("test", next, 0, 1))
                ticks.schedule(ScheduledTick("test", cancelled, 0, 2))
                val ran = ArrayList<BlockPos>()
                run(ticks, 0) { pos, type ->
                    ran.add(pos)
                    check(!ticks.willTickThisTick(pos, type), "executing tick still pending")
                    if (pos == first) {
                        check(ticks.willTickThisTick(next, type), "next tick disappeared before execution")
                        ticks.copyArea(BoundingBox(0, 100, 0, 1, 100, 0), Vec3i(32, 0, 0))
                        check(ticks.hasScheduledTick(first.offset(32, 0, 0), type), "copy lost executed tick")
                        check(ticks.hasScheduledTick(next.offset(32, 0, 0), type), "copy lost pending tick")
                        ticks.clearArea(BoundingBox(cancelled))
                        check(!ticks.willTickThisTick(cancelled, type), "cleared tick still pending")
                    }
                }
                check(ran == listOf(first, next)) { "callback order/cancellation failed: $ran" }
                ticks.clearArea(BoundingBox(32, 0, 0, 47, 255, 15))

                val both = CountDownLatch(2)
                for (x in intArrayOf(0, 32)) ticks.schedule(ScheduledTick("test", BlockPos(x, 100, 0), 1, x.toLong()))
                val calls = AtomicInteger()
                run(ticks, 1) { pos, type ->
                    both.countDown()
                    check(both.await(10, TimeUnit.SECONDS), "wave could not run two callbacks")
                    ticks.schedule(ScheduledTick(type, pos, 2, pos.x.toLong()))
                    calls.incrementAndGet()
                }
                check(calls.get() == 2 && ticks.count() == 2, "follow-up scheduling lost ticks")
                try {
                    run(ticks, 2) { _, _ -> throw IllegalStateException("expected fixture failure") }
                    throw AssertionError("callback failure swallowed")
                } catch (expected: InvocationTargetException) {
                    check(expected.cause is RuntimeException, "wrong callback failure")
                }
                check(!ticks.willTickThisTick(first, "test"), "failed wave leaked bookkeeping")
                Vanadium.config.parallelScheduledTicks = false
                ticks.schedule(ScheduledTick("test", first, 3, 0))
                run(ticks, 3) { _, _ -> calls.incrementAndGet() }
                check(calls.get() == 3, "serial fallback failed")
            } finally {
                Vanadium.scheduler = previous
                Vanadium.config.enabled = enabled
                Vanadium.config.parallelScheduledTicks = parallel
            }
        }
    }

    private fun checkPassengers(level: ServerLevel) {
        val previous = Vanadium.config.serialEntityTypes
        try {
            val root = Pig(EntityTypes.PIG, level)
            val middle = Pig(EntityTypes.PIG, level)
            val passenger = ArmorStand(level, 0.0, 100.0, 0.0)
            check(middle.startRiding(root, true, false), "fixture could not mount vehicle")
            check(passenger.startRiding(middle, true, false), "fixture could not mount nested passenger")
            Vanadium.config.serialEntityTypes = mutableListOf()
            Vanadium.config.refreshSerialRules()
            check(!EntityTickRules.requiresSerial(root), "ordinary tree unexpectedly serial")
            Vanadium.config.serialEntityTypes = mutableListOf("minecraft:armor_stand")
            Vanadium.config.refreshSerialRules()
            check(EntityTickRules.requiresSerial(root), "restricted passenger did not serialize root")
            middle.stopRiding()
            check(!EntityTickRules.requiresSerial(root), "dismount did not release root")
        } finally {
            Vanadium.config.serialEntityTypes = previous
            Vanadium.config.refreshSerialRules()
        }
    }

    private fun checkBlockEvents(level: ServerLevel) {
        val events = ServerLevel::class.java.getDeclaredField("blockEvents").apply { isAccessible = true }.get(level) as Set<*>
        val first = BlockPos(0, 100, 0)
        val second = first.offset(1, 0, 0)
        level.blockEvent(first, Blocks.PISTON, 0, 0)
        level.blockEvent(second, Blocks.PISTON, 0, 0)
        level.blockEvent(first, Blocks.PISTON, 0, 0)
        check(events.size == 2, "duplicate block event admitted")
        check(events.first() == BlockEventData(first, Blocks.PISTON, 0, 0), "event order changed")
        level.clearBlockEvents(BoundingBox(0, 100, 0, 1, 100, 0))
        check(events.isEmpty(), "event clear failed")
    }

    private fun checkRandomPositions(level: ServerLevel) {
        val random = level.random as? PerThreadRandomSource ?: throw AssertionError("level random is not per-thread")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val start = CountDownLatch(1)
            val tasks = (0 until 4).map {
                pool.submit<Any> {
                    check(start.await(10, TimeUnit.SECONDS), "random start timeout")
                    repeat(4096) {
                        val pos = level.getBlockRandomPos(32, 64, -48, 15)
                        check(pos.x in 32 until 48 && pos.y in 64 until 80 && pos.z in -48 until -32, "random position outside its section")
                        random.nextInt(16)
                    }
                    random.current()
                }
            }
            start.countDown()
            val streams = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            for (task in tasks) streams.add(task.get(15, TimeUnit.SECONDS))
            check(streams.size == 4 && random.current() !in streams, "threads shared a random stream")
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(5, TimeUnit.SECONDS), "random workers did not stop")
        }
    }
}
