package com.synsenetwork.vanadium.coldexplosion

import com.synsenetwork.vanadium.live.TickLog

/** Consecutive ticks of a [TickLog], looked up by tick ID (`MinecraftServer.tickCount`). */
internal class TickSeries(private val log: TickLog) {
    private val first = if (log.size == 0) 0 else log.ids[0]

    fun contains(tick: Int): Boolean = (tick - first).let { it in 0 until log.size && log.ids[it] == tick }

    private fun index(tick: Int): Int {
        require(contains(tick)) { "tick $tick was not recorded (recorded ${first}..${first + log.size - 1})" }
        return tick - first
    }

    /** Vanilla `tickServer` duration. */
    fun nanos(tick: Int): Long = log.durations[index(tick)]

    fun end(tick: Int): Long = log.ends[index(tick)]

    /** The tick's completion gap: from the previous completion to its own (tick, pacing wait and anything between). */
    fun gapNanos(tick: Int): Long = end(tick) - end(tick - 1)

    /** Median `tickServer` duration of ticks [from]..[to] (inclusive; the upper middle for an even count). */
    fun median(from: Int, to: Int): Long {
        require(to >= from)
        val values = LongArray(to - from + 1) { nanos(from + it) }
        values.sort()
        return values[values.size / 2]
    }

    fun max(from: Int, to: Int): Long = (from..to).maxOf(::nanos)

    /** The recorded tick whose completion gap contains [uptime] (after the previous completion, up to its own), or -1. */
    fun tickAt(uptime: Long): Int {
        for (i in 1 until log.size) if (uptime > log.ends[i - 1] && uptime <= log.ends[i]) return log.ids[i]
        return -1
    }
}

/**
 * Classes the JVM loaded, from `-Xlog:class+load=info:file=...:uptimenanos` (lines `[<uptime>ns] <class> source: ...`),
 * on the uptime clock of [com.synsenetwork.vanadium.live.UptimeClock]. Other lines are ignored.
 */
internal class ClassLoads private constructor(val uptimes: LongArray, val names: List<String>) {
    val size: Int get() = names.size

    /** Indices of classes loaded after [from] and up to [to]. */
    fun between(from: Long, to: Long): List<Int> = names.indices.filter { uptimes[it] > from && uptimes[it] <= to }

    fun firstLoad(name: String): Long? = names.indexOf(name).takeIf { it >= 0 }?.let { uptimes[it] }

    companion object {
        private val LINE = Regex("""^\[(\d+)ns] (\S+)""")

        fun parse(lines: Sequence<String>): ClassLoads {
            val uptimes = ArrayList<Long>()
            val names = ArrayList<String>()
            for (line in lines) {
                val match = LINE.find(line) ?: continue
                uptimes.add(match.groupValues[1].toLong())
                names.add(match.groupValues[2])
            }
            return ClassLoads(uptimes.toLongArray(), names)
        }
    }
}
