package com.synsenetwork.vanadium.tick

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WaveDiagnosticsTest {
    @Test
    fun disabledDiagnosticsDoNotCreateWatches() {
        val reports = ArrayList<String>()
        WaveDiagnostics(reports::add, { 0 }, false).use { diagnostics ->
            diagnostics.configure(0, true)
            assertFalse(diagnostics.detailed)
            assertNull(diagnostics.watch(Stage.ENTITY, "test:world", 3))
            diagnostics.poll()
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun reportsBlockedCallerAndWorkerWithLabelsAndRateLimit() {
        val reports = ArrayList<String>()
        val clock = AtomicLong()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val completed = AtomicInteger()
        val pool = WorkerPool(1)
        val caller = Executors.newSingleThreadExecutor { Thread(it, "diagnostic-caller") }
        try {
            WaveDiagnostics(reports::add, clock::get, false).use { diagnostics ->
                diagnostics.configure(100, true)
                val blocked = Runnable {
                    entered.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) throw AssertionError("fixture timeout")
                    completed.incrementAndGet()
                }
                val left = region(-2, 0, WaveDiagnostics.NamedTask("example:machine", blocked))
                val right = region(0, 0, WaveDiagnostics.NamedTask("minecraft:pig", blocked))
                val watch = diagnostics.watch(Stage.BLOCK_ENTITY, "test:dimension", 3)!!
                val wave = caller.submit { watch.use { pool.runWave(watch.wrap(listOf(left, right))) } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                clock.set(TimeUnit.MILLISECONDS.toNanos(99))
                diagnostics.poll()
                assertTrue(reports.isEmpty())
                clock.set(TimeUnit.MILLISECONDS.toNanos(100))
                diagnostics.poll()
                assertEquals(1, reports.size)
                val report = reports.first()
                for (expected in listOf("BLOCK_ENTITY", "test:dimension", "region=chunks (-2,0)..(-2,0)", "region=chunks (0,0)..(0,0)", "example:machine",
                        "minecraft:pig", "diagnostic-caller", "Vanadium-Worker-", "\n  at ")) {
                    assertTrue(expected in report, report)
                }
                assertEquals(0, completed.get())
                clock.addAndGet(TimeUnit.SECONDS.toNanos(1))
                diagnostics.poll()
                assertEquals(1, reports.size)
                clock.addAndGet(TimeUnit.SECONDS.toNanos(30))
                diagnostics.poll()
                assertEquals(2, reports.size)
                release.countDown()
                wave.get(5, TimeUnit.SECONDS)
                assertEquals(2, completed.get())
                clock.addAndGet(TimeUnit.SECONDS.toNanos(30))
                diagnostics.poll()
                assertEquals(2, reports.size, "completed wave was retained")
            }
        } finally {
            release.countDown()
            caller.shutdownNow()
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS))
            pool.close()
        }
    }

    @Test
    fun singleRegionStillRunsInlineAndFailureClearsWatch() {
        val clock = AtomicLong()
        val reports = ArrayList<String>()
        val pool = WorkerPool(1)
        try {
            WaveDiagnostics(reports::add, clock::get, false).use { diagnostics ->
                diagnostics.configure(1, false)
                val scheduler = TickScheduler(pool, 2)
                scheduler.diagnostics = diagnostics
                scheduler.setDimension("test:inline")
                scheduler.begin(Stage.ENTITY)
                val caller = Thread.currentThread()
                scheduler.enqueue(Stage.ENTITY, -1, -1, {
                    assertSame(caller, Thread.currentThread())
                    clock.set(TimeUnit.MILLISECONDS.toNanos(2))
                    diagnostics.poll()
                    throw IllegalStateException("fixture")
                }, null)
                assertFailsWith<IllegalStateException> { scheduler.run(Stage.ENTITY) }
                assertEquals(1, reports.size)
                assertTrue("region=chunks (-1,-1)..(-1,-1)" in reports.first())
                clock.addAndGet(TimeUnit.SECONDS.toNanos(31))
                diagnostics.poll()
                assertEquals(1, reports.size)
            }
        } finally {
            pool.close()
        }
    }

    private fun region(x: Int, z: Int, task: Runnable) = Region().apply {
        minX = x
        minZ = z
        maxX = x
        maxZ = z
        tasks.add(task)
    }
}
