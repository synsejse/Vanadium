package com.synsenetwork.vanadium.mixedbench

import com.google.gson.JsonObject
import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.npc.villager.Villager

/**
 * Tick times and CPU/allocation/GC counters for one measurement window. Tick times are vanilla's
 * own `tickServer` durations (what MSPT tools show); CPU is per Java thread, grouped by role, plus
 * whole-process CPU (which also includes GC, JIT and native threads). Read on the server thread.
 */
internal class WindowCounters(private val server: MinecraftServer) {
    private val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    private val serverThread = Thread.currentThread().threadId()
    private var ticks = LongArray(1024)
    private var count = 0
    private var start = Snapshot.empty()

    /** Per-thread CPU/allocation over all windows so far, by thread name. */
    val threadTotals = HashMap<String, LongArray>()

    init {
        threads.isThreadCpuTimeEnabled = true
        threads.isThreadAllocatedMemoryEnabled = true
    }

    private class Snapshot(val wall: Long, val process: Long, val cpu: Map<Long, Long>, val allocated: Map<Long, Long>,
                           val names: Map<Long, String>, val gcCount: Long, val gcMillis: Long) {
        companion object {
            fun empty() = Snapshot(0, 0, emptyMap(), emptyMap(), emptyMap(), 0, 0)
        }
    }

    private fun snapshot(): Snapshot {
        val cpu = HashMap<Long, Long>()
        val allocated = HashMap<Long, Long>()
        val names = HashMap<Long, String>()
        val ids = threads.allThreadIds
        val cpuTimes = threads.getThreadCpuTime(ids)
        val allocations = threads.getThreadAllocatedBytes(ids)
        for ((i, info) in threads.getThreadInfo(ids).withIndex()) {
            if (info == null || cpuTimes[i] < 0) continue
            cpu[ids[i]] = cpuTimes[i]
            allocated[ids[i]] = maxOf(0, allocations[i])
            names[ids[i]] = info.threadName
        }
        var gcCount = 0L
        var gcMillis = 0L
        for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += maxOf(0, gc.collectionCount)
            gcMillis += maxOf(0, gc.collectionTime)
        }
        return Snapshot(System.nanoTime(), os.processCpuTime, cpu, allocated, names, gcCount, gcMillis)
    }

    fun begin() {
        count = 0
        start = snapshot()
    }

    fun tick(nanos: Long) {
        if (count == ticks.size) ticks = ticks.copyOf(count * 2)
        ticks[count++] = nanos
    }

    private fun group(id: Long, name: String) = when {
        id == serverThread -> 0
        name.startsWith("Vanadium-Worker-") -> 1
        name.contains("c2me", ignoreCase = true) -> 2
        else -> 3
    }

    /** Ends the window and returns its row; [extra] adds world and workload-health columns. */
    fun end(extra: Map<String, Number>): LinkedHashMap<String, Number> {
        val end = snapshot()
        val groupCpu = LongArray(4)
        var allocated = 0L
        for ((id, cpu) in end.cpu) {
            val name = end.names.getValue(id)
            val cpuDelta = cpu - (start.cpu[id] ?: 0L)
            val allocDelta = end.allocated.getValue(id) - (start.allocated[id] ?: 0L)
            groupCpu[group(id, name)] += cpuDelta
            allocated += allocDelta
            threadTotals.getOrPut(name) { LongArray(2) }.let {
                it[0] += cpuDelta
                it[1] += allocDelta
            }
        }
        val sorted = ticks.copyOf(count).also { it.sort() }
        fun percentile(p: Double) = if (count == 0) 0.0 else sorted[minOf(count - 1, (p * count).toInt())] / 1e6
        val wallNanos = end.wall - start.wall
        val processCpu = end.process - start.process
        return linkedMapOf<String, Number>(
            "ticks" to count,
            "wall_ms" to wallNanos / 1e6,
            "tps" to if (wallNanos > 0) count * 1e9 / wallNanos else 0.0,
            "tick_mean_ms" to if (count == 0) 0.0 else sorted.sum() / 1e6 / count,
            "tick_p50_ms" to percentile(0.50),
            "tick_p95_ms" to percentile(0.95),
            "tick_p99_ms" to percentile(0.99),
            "tick_max_ms" to if (count == 0) 0.0 else sorted[count - 1] / 1e6,
            "process_cpu_ms" to processCpu / 1e6,
            "server_cpu_ms" to groupCpu[0] / 1e6,
            "worker_cpu_ms" to groupCpu[1] / 1e6,
            "c2me_cpu_ms" to groupCpu[2] / 1e6,
            "other_thread_cpu_ms" to groupCpu[3] / 1e6,
            "cpu_per_tick_ms" to if (count == 0) 0.0 else processCpu / 1e6 / count,
            "cores_busy" to if (wallNanos > 0) processCpu.toDouble() / wallNanos else 0.0,
            "allocated_mb" to allocated / 1048576.0,
            "gc_count" to end.gcCount - start.gcCount,
            "gc_ms" to end.gcMillis - start.gcMillis,
        ).apply { putAll(extra) }
    }

    /** Entity and chunk totals over every dimension, at the end of a window. */
    fun world(): Map<String, Number> {
        var entities = 0
        val categories = IntArray(MobCategory.entries.size)
        var villagers = 0
        var items = 0
        var chunks = 0
        for (level in server.allLevels) {
            chunks += level.chunkSource.loadedChunksCount
            for (entity in level.allEntities) {
                entities++
                categories[entity.type.category.ordinal]++
                if (entity is Villager) villagers++
                if (entity is ItemEntity) items++
            }
        }
        val water = listOf(MobCategory.WATER_CREATURE, MobCategory.WATER_AMBIENT, MobCategory.UNDERGROUND_WATER_CREATURE, MobCategory.AXOLOTLS)
        return linkedMapOf<String, Number>(
            "entities" to entities,
            "monsters" to categories[MobCategory.MONSTER.ordinal],
            "creatures" to categories[MobCategory.CREATURE.ordinal],
            "ambient" to categories[MobCategory.AMBIENT.ordinal],
            "water" to water.sumOf { categories[it.ordinal] },
            "villagers" to villagers,
            "items" to items,
            "loaded_chunks" to chunks,
        )
    }

    companion object {
        fun json(row: Map<String, Number>) = JsonObject().apply { row.forEach { (key, value) -> addProperty(key, value) } }
    }
}
