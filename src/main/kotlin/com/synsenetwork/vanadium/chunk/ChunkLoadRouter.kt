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
 * - On the server thread, the load runs under the chunk's reentrant lock in [locks]. No other thread holds one,
 *   so taking it never waits, and a load started inside another (a load callback asking for a neighbour, or for
 *   the same chunk) takes its lock in place.
 * - On a wave helper, the load runs on the wave's caller, the server thread, through
 *   [WorkerPool.callOnWaveCaller]: the caller serves it between its own tasks or while it waits for the wave's
 *   tail. The helper holds no load lock while it waits.
 * - Any other thread runs the load itself without a lock; vanilla and C2ME make it wait for the server thread.
 */
internal class ChunkLoadRouter(
    private val locks: ChunkLockTable,
    private val isServerThread: BooleanSupplier,
) {
    /**
     * Runs [work], a lookup of packed chunk [pos], where it can complete, per the class comment. [stalled] runs once
     * on a helper whose request waits too long (see [WorkerPool.callOnWaveCaller]).
     */
    fun <T> load(pos: Long, work: Supplier<T>, stalled: Runnable? = null): T {
        if (isServerThread.asBoolean) return underLoadLock(pos, work)
        if (WorkerPool.isWorkerThread()) {
            // The caller reruns this routing, so the work runs under its load lock if the caller is the server thread.
            return WorkerPool.callOnWaveCaller({ load(pos, work) }, stalled)
        }
        return work.get()
    }

    private fun <T> underLoadLock(pos: Long, work: Supplier<T>): T {
        val held = locks.lock(pos)
        try {
            return work.get()
        } finally {
            locks.unlock(held)
        }
    }
}
