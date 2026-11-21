package com.synsenetwork.vanadium.concurrent

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/**
 * Mutable three-double inbox for deferred entity pushes (`Interactions.deferPush`), replacing
 * `AtomicReference<Vec3>` so concurrent pushes accumulate in place without allocating a `Vec3` per push.
 *
 * Guarded by a three-state spinlock ([EMPTY], [LOCKED], [PENDING]) on a single volatile int field so each
 * entity keeps one flat object with no nested atomic wrapper. The critical section is three double additions
 * with no calls or allocations, so spinning never parks or deadlocks.
 */
class ImpulseSlot {
    @PublishedApi
    @Volatile
    internal var state = EMPTY

    @PublishedApi
    internal var x = 0.0

    @PublishedApi
    internal var y = 0.0

    @PublishedApi
    internal var z = 0.0

    /** Fast wait-free check; returns true when no push is pending or being added. */
    fun isEmpty(): Boolean = state == EMPTY

    /**
     * Adds `(dx, dy, dz)` to this slot. Returns true when this call moved the slot from empty to pending
     * (the caller is then responsible for registering the entity for post-wave drain).
     */
    fun add(dx: Double, dy: Double, dz: Double): Boolean {
        while (true) {
            val current = state
            if (current == LOCKED) {
                Thread.onSpinWait()
                continue
            }
            if (STATE.compareAndSet(this, current, LOCKED)) {
                x += dx
                y += dy
                z += dz
                state = PENDING
                return current == EMPTY
            }
        }
    }

    @PublishedApi
    internal fun tryLockPending(): Boolean = STATE.compareAndSet(this, PENDING, LOCKED)

    /**
     * Atomically clears any pending impulse and passes its components to [apply] outside the lock.
     * Returns true if a pending impulse was drained, false if the slot was empty.
     */
    inline fun drain(apply: (dx: Double, dy: Double, dz: Double) -> Unit): Boolean {
        if (state == EMPTY) return false
        var dx: Double
        var dy: Double
        var dz: Double
        while (true) {
            val current = state
            if (current == EMPTY) return false
            if (current == LOCKED) {
                Thread.onSpinWait()
                continue
            }
            if (tryLockPending()) {
                dx = x
                dy = y
                dz = z
                x = 0.0
                y = 0.0
                z = 0.0
                state = EMPTY
                break
            }
        }
        apply(dx, dy, dz)
        return true
    }

    @PublishedApi
    internal companion object {
        const val EMPTY = 0
        const val LOCKED = 1
        const val PENDING = 2
        val STATE: VarHandle = MethodHandles.lookup().findVarHandle(ImpulseSlot::class.java, "state", java.lang.Integer.TYPE)
    }
}
