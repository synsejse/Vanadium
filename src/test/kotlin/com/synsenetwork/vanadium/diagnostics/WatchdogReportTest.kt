package com.synsenetwork.vanadium.diagnostics

import com.synsenetwork.vanadium.tick.Stage
import com.synsenetwork.vanadium.tick.TickScheduler
import com.synsenetwork.vanadium.tick.WorkerPool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchdogReportTest {
    @Test
    fun watchedRegionWaveNamesStageRegionAndBusyAndIdleWorkers() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = WorkerPool(2)
        val caller = Executors.newSingleThreadExecutor { Thread(it, "watchdog-caller") }
        try {
            WaveDiagnostics({}, System::nanoTime, false).use { diagnostics ->
                diagnostics.configure(60_000, true)
                val scheduler = TickScheduler(pool, 2)
                scheduler.diagnostics = diagnostics
                scheduler.setDimension("test:hung")
                scheduler.begin(Stage.BLOCK_ENTITY)
                val blocked = Runnable {
                    entered.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) throw AssertionError("fixture timeout")
                }
                // Far enough apart to be separate, non-conflicting regions: the caller and one worker run them.
                scheduler.enqueue(Stage.BLOCK_ENTITY, 0, 0, blocked, "example:machine")
                scheduler.enqueue(Stage.BLOCK_ENTITY, 64, 0, blocked, "example:machine")
                val wave = caller.submit { scheduler.run(Stage.BLOCK_ENTITY) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))

                val details = WatchdogReport.details(scheduler, Thread.getAllStackTraces()).toMap()
                assertEquals("2 workers + server thread, isolation 2 chunks", details["Scheduler"])
                val summary = details.getValue("Wave")
                assertTrue("stage=BLOCK_ENTITY dimension=test:hung isolation=2" in summary, summary)
                assertTrue("2 participants" in details.getValue("Latest parallel wave"), details.toString())
                val busy = details.getValue("Busy workers")
                for (expected in listOf("Vanadium-Worker-1 [", "region=chunks (", "task=example:machine", javaClass.name)) {
                    assertTrue(expected in busy, busy)
                }
                assertTrue("Vanadium-Worker-2" in details.getValue("Idle workers"), details.toString())

                release.countDown()
                wave.get(5, TimeUnit.SECONDS)
            }
        } finally {
            release.countDown()
            caller.shutdownNow()
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS))
            pool.close()
        }
    }

    @Test
    fun unwatchedWaveReportsStageDimensionProgressAndStacks() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = WorkerPool(1)
        val caller = Executors.newSingleThreadExecutor { Thread(it, "watchdog-caller") }
        try {
            val scheduler = TickScheduler(pool, 2)
            scheduler.setDimension("test:unwatched")
            val before = WatchdogReport.details(scheduler, Thread.getAllStackTraces()).toMap()
            assertEquals("dimension=test:unwatched; no stage begun in it yet", before["Wave"])
            scheduler.begin(Stage.CHUNK)
            val blocked = Runnable {
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) throw AssertionError("fixture timeout")
            }
            // The caller and the worker each claim one task and block in it; the third stays unclaimed.
            val wave = caller.submit { pool.runWave(listOf(blocked, blocked, blocked)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val details = WatchdogReport.details(scheduler, Thread.getAllStackTraces()).toMap()
            assertTrue(details.getValue("Wave").startsWith("stage=CHUNK dimension=test:unwatched (the stage last begun"), details.toString())
            assertTrue(details.getValue("Latest parallel wave").startsWith("0 of 3 tasks finished, 1 unclaimed, 2 participants"), details.toString())
            val busy = details.getValue("Busy workers")
            assertTrue("Vanadium-Worker-1 [" in busy && javaClass.name in busy, busy)

            release.countDown()
            wave.get(5, TimeUnit.SECONDS)
            val finished = WatchdogReport.details(scheduler, Thread.getAllStackTraces()).toMap()
            assertTrue(finished.getValue("Latest parallel wave").startsWith("3 of 3 tasks finished, 0 unclaimed"), finished.toString())
            scheduler.setDimension("test:next")
            val next = WatchdogReport.details(scheduler, Thread.getAllStackTraces()).toMap()
            assertEquals("dimension=test:next; no stage begun in it yet", next["Wave"], "a stage outlived its world")
        } finally {
            release.countDown()
            caller.shutdownNow()
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS))
            pool.close()
        }
    }
}
