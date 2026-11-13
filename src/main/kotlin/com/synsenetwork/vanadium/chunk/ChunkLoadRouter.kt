package com.synsenetwork.vanadium.chunk

import com.synsenetwork.vanadium.tick.WorkerPool
import java.util.function.BooleanSupplier
import java.util.function.Supplier

/**
 * Where a chunk lookup that the chunk system has not answered yet runs: no thread may wait for the server thread
 * while holding a load lock, or the server thread could wait for that lock in turn. Free of Minecraft types so the
 * thread rules are unit-tested.
 *
 * Only the server thread can finish such a lookup (with C2ME, new holders, ticket updates and FULL promotion
 * happen there), and only the server thread takes load locks:
 * - On the server thread, the load runs under the chunk's lock in [locks], or [monitor] with per-chunk locks
 *   off. No other thread holds either, so taking them never waits.
 * - On a wave helper, the load runs on the wave's caller, the server thread, through
 *   [WorkerPool.callOnWaveCaller]: the caller serves it between its own tasks or while it waits for the wave's
 *   tail. The helper holds no load lock while it waits.
 * - Any other thread runs the load itself without a lock; vanilla and C2ME make it wait for the server thread.
 */
internal class ChunkLoadRouter(
    private val locks: ChunkLockTable,
    private val monitor: Any,
    private val isServerThread: BooleanSupplier,
) {
    /**
     * Runs [work], a lookup of packed chunk [pos], where it can complete, per the class comment. [perChunk] selects per-chunk
     * locks over [monitor]; [stalled] runs once on a helper whose request waits too long (see
     * [WorkerPool.callOnWaveCaller]).
     */
    fun <T> load(pos: Long, perChunk: Boolean, work: Supplier<T>, stalled: Runnable? = null): T {
        if (isServerThread.asBoolean) return underLoadLock(pos, perChunk, work)
        if (WorkerPool.isWorkerThread()) {
            // The caller reruns this routing, so the work runs under its load lock if the caller is the server thread.
            return WorkerPool.callOnWaveCaller({ load(pos, perChunk, work) }, stalled)
        }
        return work.get()
    }

    private fun <T> underLoadLock(pos: Long, perChunk: Boolean, work: Supplier<T>): T {
        if (!perChunk) synchronized(monitor) { return work.get() }
        val held = locks.lock(pos)
        try {
            return work.get()
        } finally {
            locks.unlock(held)
        }
    }
}
