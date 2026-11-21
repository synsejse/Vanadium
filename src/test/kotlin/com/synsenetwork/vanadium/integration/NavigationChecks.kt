package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.navigation.NavigationAccess
import com.synsenetwork.vanadium.navigation.NavigationIndex
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path

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
            val expected = mobs.filter { it.navigation.shouldRecomputePath(edit) }.toSet()
            val actual = index.candidates(edit).filter { it.navigation.shouldRecomputePath(edit) }.toSet()
            check(actual == expected, "indexed scan changed matches")
            checkConcurrentCandidates(index, mobs, edit, expected)
            println("NAVIGATION_CHECKS_PASSED mobs=128 candidates=$candidates")
        } finally {
            mobs.forEach(index::remove)
        }
    }

    private fun checkConcurrentCandidates(index: NavigationIndex, mobs: List<Mob>, edit: BlockPos, expected: Set<Mob>) {
        // Empty distant query returns an empty list with no dirty stripes set.
        val distant = BlockPos(2_000_000, 100, 2_000_000)
        check(index.candidates(distant).isEmpty(), "distant query returned unexpected candidates")
        check(!index.candidateIterator(distant).hasNext(), "distant candidateIterator was not empty")
        // Nested candidateIterator on the same thread must fall back cleanly without clobbering the outer iterator.
        val outer = index.candidateIterator(edit)
        val outerCollected = ArrayList<Mob>()
        var nestedChecked = false
        while (outer.hasNext()) {
            outerCollected.add(outer.next())
            if (!nestedChecked) {
                nestedChecked = true
                val inner = ArrayList<Mob>()
                index.candidateIterator(edit).forEachRemaining(inner::add)
                check(inner.containsAll(expected), "nested candidateIterator missed indexed mobs")
            }
        }
        check(outerCollected.containsAll(expected), "outer candidateIterator missed indexed mobs")

        // Parallel readers with concurrent invalidations across different worker threads must never miss
        // expected indexed matches or newly invalidated mobs.
        val readers = 4
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val threads = List(readers) { id ->
            Thread.ofPlatform().daemon().start {
                try {
                    check(start.await(5, TimeUnit.SECONDS), "concurrent candidate reader timed out")
                    repeat(200) { iter ->
                        if (iter % 20 == 0) NavigationIndex.invalidate(mobs[(id * 17 + iter) % mobs.size])
                        val found = index.candidates(edit)
                        if (!found.containsAll(expected)) {
                            error("concurrent candidate read missed indexed mob on reader $id iter $iter")
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(10_000) }
        check(threads.none { it.isAlive } && failure.get() == null) { "concurrent candidate read failed: ${failure.get()}" }
        index.refresh()
        check(index.candidates(distant).isEmpty(), "refresh after concurrent invalidations did not clear dirty stripes")
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
