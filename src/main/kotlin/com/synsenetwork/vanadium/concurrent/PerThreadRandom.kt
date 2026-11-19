package com.synsenetwork.vanadium.concurrent

import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.PositionalRandomFactory
import net.minecraft.world.level.levelgen.RandomSupport
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource

/**
 * A level-wide random source that gives every thread its own unsynchronized generator.
 *
 * Vanilla's level random is one shared generator; with parallel random ticks, scheduled ticks and entity
 * AI it became one contended monitor. Callers never relied on a shared sequence (the seed is unique per
 * level instance), so independent per-thread streams keep the distribution without coordination.
 * [setSeed] reseeds only the calling thread's stream.
 */
class PerThreadRandomSource(seed: Long) : RandomSource {
    private val local = ThreadLocal.withInitial<RandomSource> {
        SingleThreadedRandomSource(seed xor Thread.currentThread().threadId() * -0x61c8864680b583ebL)
    }

    fun current(): RandomSource = local.get()

    override fun fork(): RandomSource = local.get().fork()
    override fun forkPositional(): PositionalRandomFactory = local.get().forkPositional()
    override fun setSeed(seed: Long) = local.get().setSeed(seed)
    override fun nextInt(): Int = local.get().nextInt()
    override fun nextInt(bound: Int): Int = local.get().nextInt(bound)
    override fun nextLong(): Long = local.get().nextLong()
    override fun nextBoolean(): Boolean = local.get().nextBoolean()
    override fun nextFloat(): Float = local.get().nextFloat()
    override fun nextDouble(): Double = local.get().nextDouble()
    override fun nextGaussian(): Double = local.get().nextGaussian()
}

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
