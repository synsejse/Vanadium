package com.synsenetwork.vanadium.stage

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.Stage
import java.util.function.BiConsumer
import net.minecraft.core.BlockPos
import net.minecraft.world.ticks.LevelTicks
import net.minecraft.world.ticks.ScheduledTick

/**
 * The SCHEDULED_TICK stage: a level's block and fluid ticks (`ServerLevel.tick`'s two `LevelTicks.tick` calls).
 *
 * Due ticks are collected under the `LevelTicks` monitor on the server thread and run after it is released,
 * using reusable [TickTask] slots so steady waves allocate no per-tick closures. Each task claims its slot
 * lock-free in `LevelTicksMixin` (`clearArea` may cancel it meanwhile), and pending and executed ticks stay
 * visible to `willTickThisTick`/`copyArea` until the wave finishes, as during vanilla's drain.
 */
object ScheduledTickStage {
    private val taskPool = ArrayList<TickTask>()

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
        var count = 0
        try {
            val due = access.`vanadium$collect`(time, maxTicks)
            count = due.size
            ensurePool(count)
            for (i in 0 until count) {
                val tick = due[i]
                val pos = tick.pos()
                val task = taskPool[i]
                task.set(access, ticker, tick, i)
                scheduler.enqueue(Stage.SCHEDULED_TICK, pos.x shr 4, pos.z shr 4, task, null)
            }
            scheduler.run(Stage.SCHEDULED_TICK)
        } finally {
            for (i in 0 until count) taskPool[i].clear()
            access.`vanadium$finish`()
        }
    }

    private fun ensurePool(needed: Int) {
        while (taskPool.size < needed) taskPool.add(TickTask())
    }

    private class TickTask : Runnable {
        private var access: ScheduledTickAccess<Any>? = null
        private var ticker: BiConsumer<BlockPos, Any>? = null
        private var tick: ScheduledTick<Any>? = null
        private var index = 0

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> set(access: ScheduledTickAccess<T>, ticker: BiConsumer<BlockPos, T>, tick: ScheduledTick<T>, index: Int) {
            this.access = access as ScheduledTickAccess<Any>
            this.ticker = ticker as BiConsumer<BlockPos, Any>
            this.tick = tick as ScheduledTick<Any>
            this.index = index
        }

        fun clear() {
            access = null
            ticker = null
            tick = null
        }

        override fun run() {
            val a = access ?: return
            val t = tick ?: return
            val cb = ticker ?: return
            if (a.`vanadium$claim`(index)) cb.accept(t.pos(), t.type())
        }
    }
}
