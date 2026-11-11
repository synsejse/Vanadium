package com.synsenetwork.vanadium.concurrent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Lock protocol for tasks that each own one object and occasionally write to another task's object, with no
 * spatial separation between them.
 *
 * A task holds its own object's lock while it runs ([beginOwn], [endOwn]). To write to a foreign object it
 * calls [enterForeign]: it releases its own lock, then takes the target's, and [exitForeign] reverses that.
 * Releasing first is what keeps the common cases free of deadlock: two tasks writing to each other each hold
 * only the other's lock, and a writer waits for a target's own task holding nothing. The cost is that the
 * owner's object is open to foreign writes while it is inside the scope, at a point where it was calling out
 * anyway; a write back to its own object inside the scope takes its lock like any other target.
 *
 * Writes to state with no single owner pass a null target and serialize on one shared lock instead.
 *
 * Nested scopes (a write whose side effects write a third object) hold one lock while waiting for another,
 * so two of them can wait on each other. A waiter therefore follows the chain of lock holders and what each
 * waits for; if it leads back to itself, it stops waiting and proceeds without that lock ([cycles]). Locks
 * outside this protocol are invisible to that check, so every wait is also bounded by [timeoutNanos]
 * ([timeouts]). Either way one write runs unprotected rather than stalling the tick.
 */
class OwnerLocks(private val timeoutNanos: Long) {
    /** A lock whose holder is visible to waiters, for [OwnerLocks]' cycle check. */
    class Lock : ReentrantLock() {
        val holder: Thread? get() = owner
    }

    private val shared = Lock()
    private val waiting = ConcurrentHashMap<Thread, Lock>()

    /** Waits abandoned because the waiter and a lock's holder were waiting for each other. */
    val cycles = AtomicLong()

    /** Waits abandoned after [timeoutNanos]: a lock outside the protocol was probably involved. */
    val timeouts = AtomicLong()

    /** One thread's position in the protocol. Not thread-safe: each thread uses its own. */
    open class Context {
        private var own: Lock? = null
        private var ownHeld = false
        private var held = arrayOfNulls<Lock>(4)

        /** Foreign scopes currently open on this thread. */
        var depth = 0
            private set

        /** The lock of the task running on this thread, held or released for a foreign scope. */
        val ownLock: Lock? get() = own

        internal fun begin(locks: OwnerLocks, lock: Lock) {
            check(own == null && depth == 0) { "an owner task is already running on this thread" }
            own = lock
            ownHeld = locks.acquire(lock)
        }

        internal fun end() {
            check(depth == 0) { "owner task ended inside a foreign scope" }
            if (ownHeld) own!!.unlock()
            own = null
            ownHeld = false
        }

        internal fun enter(locks: OwnerLocks, target: Lock) {
            if (depth == 0 && ownHeld) {
                own!!.unlock()
                ownHeld = false
            }
            if (depth == held.size) held = held.copyOf(depth * 2)
            held[depth++] = target.takeIf(locks::acquire)
        }

        internal fun exit(locks: OwnerLocks) {
            check(depth > 0) { "no foreign scope to exit" }
            held[--depth]?.unlock()
            held[depth] = null
            if (depth == 0) own?.let { ownHeld = locks.acquire(it) }
        }
    }

    /** Starts a task that owns [lock]'s object; waits for a foreign write to it to finish. */
    fun beginOwn(context: Context, lock: Lock) = context.begin(this, lock)

    fun endOwn(context: Context) = context.end()

    /**
     * Opens a scope for writing to the object guarded by [target], or to shared state with no single owner
     * when [target] is null. Scopes nest; each needs one [exitForeign].
     */
    fun enterForeign(context: Context, target: Lock?) = context.enter(this, target ?: shared)

    fun exitForeign(context: Context) = context.exit(this)

    private fun acquire(lock: Lock): Boolean {
        if (lock.tryLock()) return true
        // Most waits are for the rest of one short task: spin through those without parking.
        repeat(SPINS) {
            Thread.onSpinWait()
            if (lock.tryLock()) return true
        }
        val me = Thread.currentThread()
        var interrupted = false
        waiting[me] = lock
        try {
            val deadline = System.nanoTime() + timeoutNanos
            var slice = FIRST_SLICE_NANOS
            while (true) {
                try {
                    if (lock.tryLock(slice, TimeUnit.NANOSECONDS)) return true
                } catch (_: InterruptedException) {
                    interrupted = true
                }
                if (leadsBackTo(me, lock)) {
                    cycles.incrementAndGet()
                    return false
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    timeouts.incrementAndGet()
                    return false
                }
                slice = minOf(slice * 2, MAX_SLICE_NANOS, remaining)
            }
        } finally {
            waiting.remove(me)
            if (interrupted) me.interrupt()
        }
    }

    /** Whether [lock]'s holder is waiting, directly or through other waiters, for a lock [me] holds. */
    private fun leadsBackTo(me: Thread, lock: Lock): Boolean {
        var holder = lock.holder
        repeat(MAX_CHAIN) {
            val thread = holder ?: return false
            if (thread === me) return true
            // A holder that is not waiting will release in its own time.
            holder = (waiting[thread] ?: return false).holder
        }
        return false
    }

    private companion object {
        const val SPINS = 2_000
        val FIRST_SLICE_NANOS = TimeUnit.MICROSECONDS.toNanos(200)
        val MAX_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(5)
        const val MAX_CHAIN = 16
    }
}
