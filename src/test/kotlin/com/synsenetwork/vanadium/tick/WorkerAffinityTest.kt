package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.tick.WorkerAffinity.Cpu
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class WorkerAffinityTest {
    /** The test VM's layout: SMT siblings adjacent (2k, 2k + 1), socket 1 from CPU 36. */
    @Test
    fun oneThreadPerCoreSocketBySocketThenTheSiblings() {
        val cpus = (0 until 72).map { Cpu(it, it / 36, (it % 36) / 2) }
        val order = WorkerAffinity.order(cpus.shuffled(java.util.Random(1)))
        assertContentEquals((0 until 36).map { it * 2 } + (0 until 36).map { it * 2 + 1 }, order.toList())
    }

    /** Siblings far apart (0 and 4 on core 0), core ids repeating per socket, and a CPU outside the process's set. */
    @Test
    fun coreIdsAreScopedBySocketAndMissingCpusAreSkipped() {
        val cpus = listOf(Cpu(0, 0, 0), Cpu(1, 0, 1), Cpu(2, 1, 0), Cpu(3, 1, 1), Cpu(4, 0, 0), Cpu(6, 1, 0), Cpu(7, 1, 1))
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 6, 7), WorkerAffinity.order(cpus))
    }

    @Test
    fun moreWorkersThanUsableCpusStayUnpinned() {
        assertNull(WorkerAffinity.cpusFor(1 shl 20))
    }
}
