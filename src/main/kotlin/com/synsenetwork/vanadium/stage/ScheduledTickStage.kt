package com.synsenetwork.vanadium.stage

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.Stage
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet
import java.util.AbstractQueue
import java.util.function.BiConsumer
import net.minecraft.core.BlockPos
import net.minecraft.world.ticks.LevelTicks
import net.minecraft.world.ticks.ScheduledTick

/**
 * The SCHEDULED_TICK stage: a level's block and fluid ticks (`ServerLevel.tick`'s two `LevelTicks.tick` calls).
 * It keeps no state of its own: each `LevelTicks` holds its due ticks in a [Queue] (`LevelTicksMixin`).
 *
 * Due ticks are collected under the `LevelTicks` monitor and run after it is released, so a callback that
 * schedules or clears ticks never waits for a worker that holds it. Each runs only if it is still due when its
 * task claims it (`clearArea` may cancel it meanwhile), and pending and executed ticks stay visible to
 * `willTickThisTick`/`copyArea` until the wave finishes, as during vanilla's drain.
 */
object ScheduledTickStage {
    /** Replaces `LevelTicks.tick(time, maxTicks, ticker)`. */
    @JvmStatic
    fun <T : Any> dispatch(ticks: LevelTicks<T>, time: Long, maxTicks: Int, ticker: BiConsumer<BlockPos, T>) {
        val config = Vanadium.config
        if (!config.enabled || !config.parallelScheduledTicks) {
            ticks.tick(time, maxTicks, ticker)
            return
        }
        // LevelTicksMixin preserves T when adding the duck interface after Java compilation.
        @Suppress("UNCHECKED_CAST")
        val access = ticks as ScheduledTickAccess<T>
        val scheduler = Vanadium.scheduler
        scheduler.begin(Stage.SCHEDULED_TICK)
        try {
            val due = access.`vanadium$collect`(time, maxTicks)
            for (i in due.indices) {
                val tick = due[i]
                val pos = tick.pos()
                scheduler.enqueue(Stage.SCHEDULED_TICK, pos.x shr 4, pos.z shr 4, Runnable {
                    if (access.`vanadium$claim`(tick)) ticker.accept(tick.pos(), tick.type())
                }, null)
            }
            scheduler.run(Stage.SCHEDULED_TICK)
        } finally {
            access.`vanadium$finish`()
        }
    }

    /** Due ticks are unique; retain drain order with constant-time removal by parallel callbacks. */
    class Queue<T : Any> : AbstractQueue<ScheduledTick<T>>() {
        private val ticks = ReferenceLinkedOpenHashSet<ScheduledTick<T>>()

        override fun offer(tick: ScheduledTick<T>): Boolean = ticks.add(tick)

        override fun peek(): ScheduledTick<T>? = if (ticks.isEmpty()) null else ticks.first()

        override fun poll(): ScheduledTick<T>? = if (ticks.isEmpty()) null else ticks.removeFirst()

        override fun remove(element: ScheduledTick<T>): Boolean = ticks.remove(element)

        override fun iterator(): MutableIterator<ScheduledTick<T>> = ticks.iterator()

        override val size: Int get() = ticks.size

        override fun clear() = ticks.clear()
    }
}
