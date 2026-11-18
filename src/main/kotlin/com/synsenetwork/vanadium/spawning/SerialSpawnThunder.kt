package com.synsenetwork.vanadium.spawning

import com.synsenetwork.vanadium.tick.Stage
import com.synsenetwork.vanadium.tick.TickScheduler
import java.util.function.Consumer

/**
 * Thunder while spawning is serial (`parallelSpawning = false`) and chunk ticks are parallel. Vanilla runs each
 * spawning chunk's thunder, then its spawn attempt. Here the thunder of the chunk being visited is held back
 * until it is known whether a spawn attempt follows: if one does, the thunder runs inline on the server thread
 * just before it, so neither waits for a wave; if none does (no spawn categories, or the chunk cannot spawn),
 * the thunder joins the [Stage.SPAWNING] wave that runs before the block-ticking pass, as with parallel spawning.
 * Thunder-only work therefore still runs in parallel, and every stage barrier stays where it was.
 *
 * Holds at most one chunk. Server thread only; per world.
 */
class SerialSpawnThunder<C : Any>(private val thunder: Consumer<C>) {
    private var pending: C? = null
    private var pendingX = 0
    private var pendingZ = 0

    /** Holds [chunk]'s thunder; the previously held chunk's thunder had no spawn attempt and joins the wave. */
    fun hold(scheduler: TickScheduler, chunk: C, chunkX: Int, chunkZ: Int) {
        flush(scheduler)
        pending = chunk
        pendingX = chunkX
        pendingZ = chunkZ
    }

    /** Before [chunk]'s inline spawn attempt: runs its held thunder now; another chunk's joins the wave. */
    fun beforeSpawn(scheduler: TickScheduler, chunk: C) {
        val held = pending ?: return
        pending = null
        if (held === chunk) thunder.accept(held) else enqueue(scheduler, held, pendingX, pendingZ)
    }

    /** Before the spawning wave runs: a held thunder that no spawn attempt followed joins it. */
    fun flush(scheduler: TickScheduler) {
        val held = pending ?: return
        pending = null
        enqueue(scheduler, held, pendingX, pendingZ)
    }

    /** At the start of a world's chunk tick: drops anything a failed tick left. */
    fun clear() {
        pending = null
    }

    private fun enqueue(scheduler: TickScheduler, chunk: C, chunkX: Int, chunkZ: Int) {
        scheduler.enqueue(Stage.SPAWNING, chunkX, chunkZ, { thunder.accept(chunk) }, null)
    }
}
