package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.Vanadium
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Optional pinning of worker threads to CPUs (Linux only, through `sched_setaffinity` via the Foreign Function &
 * Memory API). Worker i gets the i-th CPU of [order]: one hardware thread per physical core, cores grouped by socket,
 * then the cores' second hardware threads. Consecutive workers, which own neighbouring homes of a homed wave
 * ([WaveHomes]), then share a socket. Any failure leaves threads unpinned.
 */
internal object WorkerAffinity {
    private const val MASK_BYTES = 128L // 1,024 CPUs.

    private val setAffinity: MethodHandle? by lazy { handle("sched_setaffinity") }
    private val getAffinity: MethodHandle? by lazy { handle("sched_getaffinity") }

    /** CPUs workers are pinned to, in worker order; empty when pinning is unavailable. */
    val order: IntArray by lazy { runCatching { computeOrder() }.getOrElse { IntArray(0) } }

    /** A CPU for each of [workers] workers, or null when there are fewer usable CPUs than workers (or none known). */
    fun cpusFor(workers: Int): IntArray? = order.takeIf { it.size >= workers }?.copyOf(workers)

    /** Pins the calling thread to [cpu]; false (and the thread stays as it was) where that fails. */
    fun pinCurrent(cpu: Int): Boolean = runCatching { set(cpu) }.getOrDefault(false)

    private fun handle(name: String): MethodHandle? = runCatching {
        if (!System.getProperty("os.name").startsWith("Linux")) return null
        val linker = Linker.nativeLinker()
        val symbol = linker.defaultLookup().find(name).orElse(null) ?: return null
        linker.downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
    }.onFailure { Vanadium.LOGGER.warn("Vanadium: CPU pinning unavailable: {}", it.toString()) }.getOrNull()

    /** CPUs this process may run on, or null when unknown. */
    private fun allowed(): Set<Int>? {
        val get = getAffinity ?: return null
        Arena.ofConfined().use { arena ->
            val mask = arena.allocate(MASK_BYTES)
            if (get.invokeWithArguments(0, MASK_BYTES, mask) as Int != 0) return null
            val cpus = HashSet<Int>()
            for (byte in 0 until MASK_BYTES.toInt()) {
                val bits = mask.get(ValueLayout.JAVA_BYTE, byte.toLong()).toInt() and 0xFF
                for (bit in 0 until 8) if (bits and (1 shl bit) != 0) cpus.add(byte * 8 + bit)
            }
            return cpus
        }
    }

    private fun set(cpu: Int): Boolean {
        val set = setAffinity ?: return false
        Arena.ofConfined().use { arena ->
            val mask: MemorySegment = arena.allocate(MASK_BYTES)
            mask.set(ValueLayout.JAVA_BYTE, (cpu / 8).toLong(), (1 shl (cpu % 8)).toByte())
            return set.invokeWithArguments(0, MASK_BYTES, mask) as Int == 0
        }
    }

    private fun computeOrder(): IntArray {
        if (setAffinity == null) return IntArray(0)
        val allowed = allowed() ?: return IntArray(0)
        val cpus = allowed.sorted().map { id ->
            val topology = File("/sys/devices/system/cpu/cpu$id/topology")
            val socket = File(topology, "physical_package_id").readText().trim().toInt()
            val core = File(topology, "core_id").readText().trim().toInt()
            Cpu(id, socket, core)
        }
        return order(cpus)
    }

    /** A CPU, its socket and its core (`core_id` is unique within a socket only). */
    internal data class Cpu(val id: Int, val socket: Int, val core: Int)

    /** First hardware threads of every core (by socket, then core), then second ones, and so on; threads by id. */
    internal fun order(cpus: List<Cpu>): IntArray {
        val byCore = cpus.sortedBy { it.id }.groupBy { it.socket to it.core }.toSortedMap(compareBy({ it.first }, { it.second }))
        val rounds = byCore.values.maxOfOrNull { it.size } ?: 0
        val out = IntArray(cpus.size)
        var i = 0
        for (round in 0 until rounds) for (threads in byCore.values) threads.getOrNull(round)?.let { out[i++] = it.id }
        return out
    }
}
