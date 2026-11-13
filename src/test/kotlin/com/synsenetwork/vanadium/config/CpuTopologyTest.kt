package com.synsenetwork.vanadium.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class CpuTopologyTest {
    @TempDir
    lateinit var root: Path

    /** Writes a fake `/sys/devices/system/cpu` with [threadsPerCore] consecutive siblings per core, as on AMD/most VMs. */
    private fun sysfs(cpus: Int, threadsPerCore: Int, file: String = "core_cpus_list"): Path {
        root.resolve("online").writeText(if (cpus == 1) "0\n" else "0-${cpus - 1}\n")
        for (cpu in 0 until cpus) {
            val first = cpu / threadsPerCore * threadsPerCore
            val list = if (threadsPerCore == 1) "$first" else "$first-${first + threadsPerCore - 1}"
            root.resolve("cpu$cpu/topology").createDirectories().resolve(file).writeText("$list\n")
        }
        return root
    }

    @Test
    fun countsCoresNotSiblings() {
        assertEquals(36, CpuTopology.cores(sysfs(72, 2), null))
    }

    @Test
    fun withoutSmtEveryCpuIsACore() {
        assertEquals(8, CpuTopology.cores(sysfs(8, 1), null))
    }

    @Test
    fun readsTheOlderSiblingFile() {
        assertEquals(4, CpuTopology.cores(sysfs(8, 2, "thread_siblings_list"), null))
    }

    @Test
    fun intelStyleSiblingsFarApart() {
        // Intel numbering: CPU n and n + 4 share a core (siblings "0,4").
        root.resolve("online").writeText("0-7\n")
        for (cpu in 0 until 8) {
            val core = cpu % 4
            root.resolve("cpu$cpu/topology").createDirectories().resolve("core_cpus_list").writeText("$core,${core + 4}\n")
        }
        assertEquals(4, CpuTopology.cores(root, null))
        assertEquals(2, CpuTopology.cores(root, "0-1,4-5"), "two whole cores")
        assertEquals(4, CpuTopology.cores(root, "0-3"), "one thread on each of four cores")
    }

    @Test
    fun countsOnlyAllowedCpus() {
        sysfs(72, 2)
        assertEquals(4, CpuTopology.cores(root, "0-7"), "taskset to four cores with their siblings")
        assertEquals(2, CpuTopology.cores(root, "0,2"), "one thread on each of two cores")
        assertEquals(1, CpuTopology.cores(root, "4-5"))
    }

    @Test
    fun ignoresAllowedCpusThatAreNotOnline() {
        // The default affinity mask often spans every possible CPU, beyond those present.
        assertEquals(4, CpuTopology.cores(sysfs(8, 2), "0-127"))
    }

    @Test
    fun unknownWhenTopologyIsMissing() {
        assertNull(CpuTopology.cores(root.resolve("absent"), null), "no sysfs at all")
        root.resolve("online").writeText("0-3\n")
        assertNull(CpuTopology.cores(root, null), "online list without per-CPU topology")
        sysfs(4, 2)
        Files.delete(root.resolve("cpu3/topology/core_cpus_list"))
        assertNull(CpuTopology.cores(root, null), "one CPU missing makes the count unknown, not smaller")
    }

    @Test
    fun unknownWhenListsAreMalformed() {
        root.resolve("online").writeText("garbage\n")
        assertNull(CpuTopology.cores(root, null))
        assertNull(CpuTopology.cores(sysfs(4, 2), "3-1"))
        assertNull(CpuTopology.cores(root, "100-101"), "no allowed CPU is online")
    }

    @Test
    fun parsesKernelCpuLists() {
        assertEquals(setOf(0, 1, 2, 3, 8, 10, 11), CpuTopology.parseCpuList("0-3,8,10-11"))
        assertEquals(setOf(5), CpuTopology.parseCpuList(" 5\n"))
        assertNull(CpuTopology.parseCpuList("0-"))
        assertNull(CpuTopology.parseCpuList("a"))
    }

    @Test
    fun readsAffinityFromProcStatus() {
        val status = root.resolve("status")
        status.writeText("Name:\tjava\nCpus_allowed:\tff\nCpus_allowed_list:\t0-7\nMems_allowed_list:\t0\n")
        assertEquals("0-7", CpuTopology.allowedCpus(status))
        assertNull(CpuTopology.allowedCpus(root.resolve("missing")))
    }
}
