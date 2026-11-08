package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.tracking.NavigationAccess
import com.synsenetwork.vanadium.tracking.NavigationIndex
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object NavigationChecks {
    fun run(level: ServerLevel) {
        val index = (level as NavigationAccess).`vanadium$navigations`()
        val pathField = PathNavigation::class.java.getDeclaredField("path").apply { isAccessible = true }
        val mobs = ArrayList<Mob>()
        try {
            for (i in 0 until 128) {
                val mob = Pig(EntityTypes.PIG, level)
                val x = (i - 64) * 256
                mob.setPos(x.toDouble(), 100.0, -32.0)
                val nodes = List(64) { j -> Node(x + j, 100, -32) }
                pathField.set(mob.navigation, Path(nodes, BlockPos(x + 63, 100, -32), true))
                mobs.add(mob)
                index.add(mob)
            }
            index.refresh()
            for (mob in mobs) {
                for (offset in intArrayOf(-80, -64, -32, -1, 0, 15, 31, 63, 80, 96)) {
                    val pos = mob.blockPosition().offset(offset, 0, 0)
                    val candidates = index.candidates(pos)
                    for (reference in mobs) {
                        check(!reference.navigation.shouldRecomputePath(pos) || reference in candidates, "index missed vanilla navigation")
                    }
                }
            }
            checkRefreshInvalidation(index, mobs)
            val moved = mobs.first()
            moved.setPos(100000.0, 100.0, 0.0)
            check(moved in index.candidates(BlockPos(100000, 100, 0)), "moving mob missed dirty fallback")
            index.refresh()
            moved.navigation.moveTo(null as Path?, 1.0)
            check(moved in index.candidates(BlockPos(100000, 100, 0)), "path replacement missed dirty fallback")
            index.refresh()
            check(moved !in index.candidates(BlockPos(100000, 100, 0)), "finished path stayed indexed")

            val edit = mobs[64].blockPosition()
            val candidates = index.candidates(edit).size
            check(candidates < mobs.size, "dispersed navigation query did not reduce candidates")
            val fullStart = System.nanoTime()
            var fullMatches = 0
            repeat(2000) { for (mob in mobs) if (mob.navigation.shouldRecomputePath(edit)) fullMatches++ }
            val fullNanos = System.nanoTime() - fullStart
            val indexedStart = System.nanoTime()
            var indexedMatches = 0
            repeat(2000) { for (mob in index.candidates(edit)) if (mob.navigation.shouldRecomputePath(edit)) indexedMatches++ }
            val indexedNanos = System.nanoTime() - indexedStart
            check(fullMatches == indexedMatches, "indexed scan changed matches")
            println("NAVIGATION_CHECKS_PASSED mobs=128 candidates=$candidates fullNanos=$fullNanos indexedNanos=$indexedNanos")
        } finally {
            mobs.forEach(index::remove)
        }
    }

    private fun checkRefreshInvalidation(index: NavigationIndex, mobs: List<Mob>) {
        val blocked = mobs.first()
        val changed = mobs.last()
        NavigationIndex.invalidate(blocked)
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val refresh: Thread
        synchronized(blocked.navigation) {
            refresh = Thread.ofPlatform().daemon().start {
                started.countDown()
                try {
                    index.refresh()
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }
            check(started.await(5, TimeUnit.SECONDS), "refresh thread did not start")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (refresh.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait()
            check(refresh.state == Thread.State.BLOCKED, "refresh did not reach navigation snapshot")
            NavigationIndex.invalidate(changed)
        }
        refresh.join(5000)
        check(!refresh.isAlive && failure.get() == null) { "concurrent refresh failed: ${failure.get()}" }
        val distant = BlockPos(1000000, 100, 1000000)
        check(changed in index.candidates(distant), "refresh cleared a concurrent invalidation")
        index.refresh()
        check(index.candidates(distant).isEmpty(), "unchanged refresh did not clear dirty entries")

        index.refresh() // Begin a quiet interval before invalidating the whole population.
        mobs.forEach(NavigationIndex::invalidate)
        val snapshot = index.candidates(distant).toMutableList()
        check(snapshot.size == mobs.size && snapshot.containsAll(mobs), "full fallback lost or duplicated mobs")
        snapshot.clear()
        check(index.size == mobs.size, "candidate snapshot mutated membership")
        index.refresh()
        check(index.candidates(distant).containsAll(mobs), "deferred refresh lost dirty fallback")
        index.refresh()
        check(index.candidates(distant).isEmpty(), "stable pending entries were not refreshed")
    }
}
