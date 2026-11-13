package com.synsenetwork.vanadium.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Physical cores among the CPUs this process may run on, from Linux sysfs. Only the default worker count uses it,
 * once per server start; nothing on the tick path reads it.
 */
internal object CpuTopology {
    private val SYSFS_CPU: Path = Path.of("/sys/devices/system/cpu")
    private val PROC_STATUS: Path = Path.of("/proc/self/status")

    /** Physical cores this process may use, or null where the topology cannot be read (not Linux, no sysfs). */
    val cores: Int? by lazy { cores(SYSFS_CPU, allowedCpus(PROC_STATUS)) }

    /**
     * Counts distinct physical cores among the online CPUs in [allowed] (a kernel CPU list such as `0-3,8`, or null
     * for every online CPU). Each CPU's sibling list names its core, so two CPUs on one core share it. Null when the
     * online list or any allowed CPU's sibling list is missing or unreadable: a partial count would be wrong.
     */
    fun cores(cpuRoot: Path, allowed: String?): Int? {
        val online = readCpuList(cpuRoot.resolve("online")) ?: return null
        val usable = if (allowed == null) online else (parseCpuList(allowed) ?: return null).filter { it in online }
        if (usable.isEmpty()) return null
        val siblings = HashSet<String>()
        for (cpu in usable) {
            val topology = cpuRoot.resolve("cpu$cpu").resolve("topology")
            // core_cpus_list replaced thread_siblings_list in Linux 5.6; both still exist on current kernels.
            val list = readLine(topology.resolve("core_cpus_list")) ?: readLine(topology.resolve("thread_siblings_list"))
                ?: return null
            siblings.add(list)
        }
        return siblings.size
    }

    /** The calling thread's affinity (`Cpus_allowed_list` in `/proc/self/status`), or null if unreadable. */
    fun allowedCpus(status: Path): String? = try {
        Files.readAllLines(status).firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** Parses a kernel CPU list (`0-3,8,10-11`) into sorted CPU numbers; null if malformed. */
    fun parseCpuList(text: String): Set<Int>? {
        val cpus = sortedSetOf<Int>()
        for (part in text.trim().split(',')) {
            if (part.isEmpty()) continue
            val dash = part.indexOf('-')
            val first = (if (dash < 0) part else part.substring(0, dash)).toIntOrNull() ?: return null
            val last = (if (dash < 0) part else part.substring(dash + 1)).toIntOrNull() ?: return null
            if (first < 0 || last < first) return null
            for (cpu in first..last) cpus.add(cpu)
        }
        return cpus
    }

    private fun readCpuList(file: Path): Set<Int>? = readLine(file)?.let(::parseCpuList)

    private fun readLine(file: Path): String? = try {
        Files.readAllLines(file).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
