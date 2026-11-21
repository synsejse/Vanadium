package com.synsenetwork.vanadium.concurrent

import com.synsenetwork.vanadium.tick.TickThread
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

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
 * Operations that two owners start on each other symmetrically take both locks in an agreed order
 * ([enterPair]).
 *
 * Nested scopes (a write whose side effects write a third object) hold one lock while waiting for another,
 * so two of them can wait on each other. A waiter therefore follows the chain of lock holders and what each
 * waits for; if it leads back to itself, it stops waiting and proceeds without that lock ([cycles]). Locks
 * outside this protocol are invisible to that check, so every wait is also bounded by [timeoutNanos]
 * ([timeouts]). Either way one write runs unprotected rather than stalling the tick.
 */
class OwnerLocks(private val timeoutNanos: Long) {
    /**
     * Compact reentrant exclusive lock built on [VarHandle] and [LockSupport]. Every entity holds one, so
     * avoiding `ReentrantLock`'s extra `NonfairSync`/AQS object halves per-entity lock allocation and keeps
     * the uncontended `beginOwn`/`endOwn` path to one CAS + plain hold counter and one volatile release +
     * null check on [waiters].
     */
    class Lock {
        @JvmField
        @Volatile
        internal var owner: Thread? = null

        @JvmField
        internal var holds: Int = 0

        @JvmField
        @Volatile
        internal var waiters: Waiter? = null

        val holder: Thread? get() = owner

        val isLocked: Boolean get() = owner != null

        val isHeldByCurrentThread: Boolean get() = owner === Thread.currentThread()

        fun hasQueuedThreads(): Boolean {
            var node = waiters
            while (node != null) {
                if (node.active) return true
                node = node.next
            }
            return false
        }

        fun tryLock(): Boolean {
            val current = Thread.currentThread()
            val held = owner
            if (held === null) {
                if (OWNER.compareAndSet(this, null as Thread?, current)) {
                    holds = 1
                    return true
                }
                return false
            }
            if (held === current) {
                holds++
                return true
            }
            return false
        }

        fun lock() {
            if (tryLock()) return
            repeat(SPINS) {
                Thread.onSpinWait()
                if (owner === null && tryLock()) return
            }
            val me = Thread.currentThread()
            var interrupted = false
            var node: Waiter? = null
            try {
                while (true) {
                    if (tryLock()) return
                    if (node == null || node.unparked) {
                        node?.active = false
                        node = pushWaiter(me)
                        // Dekker recheck after publishing waiter onto the Treiber stack.
                        if (tryLock()) return
                    }
                    LockSupport.park(this)
                    if (Thread.interrupted()) interrupted = true
                }
            } finally {
                if (node != null) deactivateWaiter(node)
                if (interrupted) me.interrupt()
            }
        }

        fun unlock() {
            val remaining = holds - 1
            if (remaining > 0) {
                holds = remaining
                return
            }
            check(remaining == 0 && owner === Thread.currentThread()) { "unlock by non-owner" }
            holds = 0
            // Volatile store provides StoreLoad ordering before the volatile read of waiters (Dekker).
            OWNER.setVolatile(this, null as Thread?)
            if (waiters != null) wakeAll()
        }

        internal fun pushWaiter(thread: Thread): Waiter {
            val node = Waiter(thread)
            while (true) {
                val head = waiters
                node.next = head
                if (WAITERS.compareAndSet(this, head, node)) return node
            }
        }

        internal fun deactivateWaiter(node: Waiter) {
            node.active = false
            // Opportunistically pop the head if we are still on top; nodes never re-enter the stack (no ABA).
            if (waiters === node) WAITERS.compareAndSet(this, node, node.next)
        }

        private fun wakeAll() {
            var node = WAITERS.getAndSet(this, null as Waiter?) as Waiter?
            while (node != null) {
                node.unparked = true
                if (node.active) LockSupport.unpark(node.thread)
                node = node.next
            }
        }

        internal class Waiter(@JvmField val thread: Thread) {
            @JvmField var next: Waiter? = null
            @JvmField @Volatile var active: Boolean = true
            @JvmField @Volatile var unparked: Boolean = false
        }

        private companion object {
            val OWNER: VarHandle = MethodHandles.lookup().findVarHandle(Lock::class.java, "owner", Thread::class.java)
            val WAITERS: VarHandle = MethodHandles.lookup().findVarHandle(Lock::class.java, "waiters", Waiter::class.java)
        }
    }

    private val shared = Lock()
    private val waiting = ConcurrentHashMap<Thread, Lock>()

    /** Waits abandoned because the waiter and a lock's holder were waiting for each other. */
    val cycles = AtomicLong()

    /** Waits abandoned after [timeoutNanos]: a lock outside the protocol was probably involved. */
    val timeouts = AtomicLong()

    /** One thread's position in the protocol: plain state that only [OwnerLocks] changes. Each thread uses its own. */
    open class Context {
        /** The lock of the task running on this thread, held or released for a foreign scope. */
        @JvmField internal var ownLock: Lock? = null

        /** Whether this thread holds [ownLock]: false inside a foreign scope, or after a wait for it gave up. */
        @JvmField internal var ownHeld = false

        /** Foreign scopes currently open on this thread. */
        @JvmField internal var depth = 0

        /** The lock each open scope took, by depth; null where its wait gave up. */
        @JvmField internal var held = arrayOfNulls<Lock>(4)

        /** The lock this thread is currently parked waiting for, read by other threads' cycle check. */
        @JvmField @Volatile internal var waitingFor: Lock? = null
    }

    /** Starts a task that owns [lock]'s object; waits for a foreign write to it to finish. */
    fun beginOwn(context: Context, lock: Lock) {
        check(context.ownLock == null && context.depth == 0) { "an owner task is already running on this thread" }
        context.ownLock = lock
        context.ownHeld = acquire(context, lock)
    }

    fun endOwn(context: Context) {
        check(context.depth == 0) { "owner task ended inside a foreign scope" }
        releaseOwn(context)
        context.ownLock = null
    }

    /**
     * Opens a scope for writing to the object guarded by [target], or to shared state with no single owner
     * when [target] is null. Scopes nest; each needs one [exitForeign].
     */
    fun enterForeign(context: Context, target: Lock?) {
        if (context.depth == 0) releaseOwn(context)
        if (context.depth == context.held.size) context.held = context.held.copyOf(context.depth * 2)
        val lock = target ?: shared
        context.held[context.depth++] = if (acquire(context, lock)) lock else null
    }

    /**
     * Opens a scope that owns both the caller's object and [other]'s, for an operation the two owners can
     * each start on the other in the same instant (merging, pairing). Both sides must pass the same order
     * ([ownFirst] on exactly one of them), so they queue instead of each holding one lock and waiting for the
     * other. Counts as two scopes: needs two [exitForeign] calls.
     */
    fun enterPair(context: Context, other: Lock, ownFirst: Boolean) {
        val own = checkNotNull(context.ownLock) { "no owner task for a pair scope" }
        if (context.depth == 0) releaseOwn(context)
        if (context.depth + 2 > context.held.size) context.held = context.held.copyOf(context.held.size * 2)
        val first = if (ownFirst) own else other
        val second = if (ownFirst) other else own
        context.held[context.depth++] = if (acquire(context, first)) first else null
        context.held[context.depth++] = if (acquire(context, second)) second else null
    }

    /** Closes the innermost scope; closing the last takes the owner's lock back. */
    fun exitForeign(context: Context) {
        check(context.depth > 0) { "no foreign scope to exit" }
        val depth = --context.depth
        context.held[depth]?.unlock()
        context.held[depth] = null
        val own = context.ownLock
        if (depth == 0 && own != null) context.ownHeld = acquire(context, own)
    }

    /** Releases the owner's lock if this thread holds it: at a first foreign scope, and when the task ends. */
    private fun releaseOwn(context: Context) {
        if (!context.ownHeld) return
        context.ownHeld = false
        context.ownLock?.unlock()
    }

    private fun acquire(context: Context, lock: Lock): Boolean {
        if (lock.tryLock()) return true
        // Most waits are for the rest of one short task: spin through those without parking.
        repeat(SPINS) {
            Thread.onSpinWait()
            if (lock.owner === null && lock.tryLock()) return true
        }
        val me = Thread.currentThread()
        val onWorker = me is TickThread && me.context === context
        var interrupted = false
        context.waitingFor = lock
        if (!onWorker) waiting[me] = lock
        var node: Lock.Waiter? = null
        try {
            val deadline = System.nanoTime() + timeoutNanos
            var slice = FIRST_SLICE_NANOS
            while (true) {
                val sliceDeadline = System.nanoTime() + slice
                while (true) {
                    if (lock.tryLock()) return true
                    val sliceRemaining = sliceDeadline - System.nanoTime()
                    if (sliceRemaining <= 0L) break
                    if (node == null || node.unparked) {
                        node?.active = false
                        node = lock.pushWaiter(me)
                        // Dekker recheck after publishing waiter.
                        if (lock.tryLock()) return true
                    }
                    LockSupport.parkNanos(lock, sliceRemaining)
                    if (Thread.interrupted()) interrupted = true
                }
                if (leadsBackTo(me, lock)) {
                    cycles.incrementAndGet()
                    return false
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    timeouts.incrementAndGet()
                    return false
                }
                slice = minOf(slice * 2, MAX_SLICE_NANOS, remaining)
            }
        } finally {
            if (node != null) lock.deactivateWaiter(node)
            context.waitingFor = null
            if (!onWorker) waiting.remove(me)
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
            val waited = if (thread is TickThread) (thread.context as? Context)?.waitingFor ?: waiting[thread] else waiting[thread]
            holder = (waited ?: return false).holder
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
