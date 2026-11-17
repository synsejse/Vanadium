package com.synsenetwork.vanadium.tick

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegionCallerRequestTest {
    @Test
    fun claimingCallerServesRequestsWhileConflictsAreRunning() = checkRegionRequest(claiming = true)

    @Test
    fun dataflowCallerServesRequestsWhileSuccessorsAreNotReady() = checkRegionRequest(claiming = false)

    private fun checkRegionRequest(claiming: Boolean) {
        val caller = Thread.currentThread()
        val servedBy = AtomicReference<Thread>()
        val runs = AtomicIntegerArray(2)
        val work = listOf(Runnable {
            assertTrue(WorkerPool.isWorkerThread(), "the gated first region must run on the helper")
            servedBy.set(WorkerPool.callOnWaveCaller(Supplier { Thread.currentThread() }, Runnable {
                // Bound a broken implementation's wait too: unwinding releases the conflicting region,
                // allowing the caller to exit its scheduler loop and drain the abandoned request.
                error("the region scheduler did not service the helper's request")
            }))
            runs.incrementAndGet(0)
        }, Runnable {
            assertEquals(1, runs.get(0), "the successor ran before its conflict completed")
            runs.incrementAndGet(1)
        })
        val starts = intArrayOf(0, 1, 2)
        val neighbors = intArrayOf(1, 0)
        val wave: RegionWave = if (claiming) ClaimWave(work, starts, neighbors)
            else DataflowWave(work, starts, neighbors, intArrayOf(0, 1))
        val participants = wave.participants(2)
        val task = Runnable {
            if (WorkerPool.isWorkerThread()) {
                participants[0].run()
            } else {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!WorkerPool.callerHasRequests()) {
                    check(System.nanoTime() < deadline) { "helper did not queue its request" }
                    Thread.onSpinWait()
                }
                // The only unfinished work conflicts with the helper's region. Waiting for a region
                // completion here must still pump the request that lets that helper finish.
                participants[1].run()
            }
        }
        WorkerPool(1).use { pool -> pool.runWave(listOf(task, task)) }
        assertNull(wave.failure)
        assertSame(caller, servedBy.get())
        assertEquals(1, runs.get(0))
        assertEquals(1, runs.get(1))
        assertFalse(WorkerPool.callerHasRequests())
    }
}
