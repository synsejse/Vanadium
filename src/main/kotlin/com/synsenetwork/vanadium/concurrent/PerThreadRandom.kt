package com.synsenetwork.vanadium.concurrent

import com.synsenetwork.vanadium.tick.TickThread
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
 *
 * Workers cache their active instance's stream on [TickThread] and the constructing thread (the server
 * thread) uses [ownerRandom] directly, avoiding `ThreadLocal` lookups on spawning and chunk-tick hot paths.
 */
class PerThreadRandomSource(seed: Long) : RandomSource {
    private val ownerThread: Thread = Thread.currentThread()
    private val ownerRandom: RandomSource = SingleThreadedRandomSource(seed)
    private val local = ThreadLocal.withInitial<RandomSource> {
        SingleThreadedRandomSource(seed xor Thread.currentThread().threadId() * -0x61c8864680b583ebL)
    }

    fun current(): RandomSource {
        val thread = Thread.currentThread()
        if (thread is TickThread) {
            if (thread.randomOwner === this) return thread.random as RandomSource
            val stream = local.get()
            thread.randomOwner = this
            thread.random = stream
            return stream
        }
        if (thread === ownerThread) return ownerRandom
        return local.get()
    }

    override fun fork(): RandomSource = current().fork()
    override fun forkPositional(): PositionalRandomFactory = current().forkPositional()
    override fun setSeed(seed: Long) = current().setSeed(seed)
    override fun nextInt(): Int = current().nextInt()
    override fun nextInt(bound: Int): Int = current().nextInt(bound)
    override fun nextLong(): Long = current().nextLong()
    override fun nextBoolean(): Boolean = current().nextBoolean()
    override fun nextFloat(): Float = current().nextFloat()
    override fun nextDouble(): Double = current().nextDouble()
    override fun nextGaussian(): Double = current().nextGaussian()
}

/**
 * Per-thread state for `Level.randValue`, the LCG behind random-tick positions. Vanilla's
 * `getBlockRandomPos` and Lithium's random-tick and position shortcuts advance one field per level from
 * every parallel chunk tick, losing updates and correlating positions across chunks. `VanadiumMixinPlugin`
 * rewrites every read and write of the field in `Level` and `ServerLevel` to these calls, so each thread
 * keeps its own sequence with the same arithmetic. The level is ignored: the sequence was never observable.
 *
 * Workers keep their value on [TickThread.randValue] and the registered server thread uses [serverValue],
 * avoiding `ThreadLocal` lookups on random ticks.
 */
object PerThreadRandValue {
    @Volatile
    private var serverThread: Thread? = null
    private var serverValue: Int = RandomSupport.generateUniqueSeed().toInt()
    private val state = ThreadLocal.withInitial { intArrayOf(RandomSupport.generateUniqueSeed().toInt()) }

    fun setServerThread(thread: Thread?) {
        serverThread = thread
    }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun get(level: Level?): Int {
        val thread = Thread.currentThread()
        if (thread is TickThread) return thread.randValue
        if (thread === serverThread) return serverValue
        return state.get()[0]
    }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun set(level: Level?, value: Int) {
        val thread = Thread.currentThread()
        if (thread is TickThread) {
            thread.randValue = value
            return
        }
        if (thread === serverThread) {
            serverValue = value
            return
        }
        state.get()[0] = value
    }
}
