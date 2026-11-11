package com.synsenetwork.vanadium.concurrent

import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.RandomSupport

/**
 * Per-thread state for `Level.randValue`, the LCG behind random-tick positions. Vanilla's
 * `getBlockRandomPos` and Lithium's random-tick and position shortcuts advance one field per level from
 * every parallel chunk tick, losing updates and correlating positions across chunks. `SynchronisePlugin`
 * rewrites every read and write of the field in `Level` and `ServerLevel` to these calls, so each thread
 * keeps its own sequence with the same arithmetic. The level is ignored: the sequence was never observable.
 */
object PerThreadRandValue {
    private val state = ThreadLocal.withInitial { IntArray(1).apply { this[0] = RandomSupport.generateUniqueSeed().toInt() } }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun get(level: Level): Int = state.get()[0]

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun set(level: Level, value: Int) {
        state.get()[0] = value
    }
}
