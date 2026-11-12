package com.synsenetwork.vanadium.tick

/**
 * Spinning of the server thread for the last part of its wait for the next tick, or all of it. On the Xeon VM
 * a server that sleeps through most of each 50 ms has at times run its ticks at half speed; spinning keeps one
 * core busy for the window instead.
 *
 * [arm] is called at the end of a tick, for the wait right after it; the wait mixin only spins while the wait is
 * for exactly that tick, so a wait no tick event preceded (a server paused when empty, startup, shutdown) never
 * spins. Spinning happens in [SLICE_NANOS] slices and the wait loop around them keeps polling tasks between
 * slices, as vanilla's own waits do every 100 µs. Server thread only.
 */
object ServerSpin {
    private const val SLICE_NANOS = 50_000L

    /** `vanadium.serverSpinMicros` replaces the mode's spin window; negative or absent keeps the mode's. */
    private val OVERRIDE_NANOS: Long? = java.lang.Long.getLong("vanadium.serverSpinMicros")?.let { it.coerceAtLeast(0L) * 1_000L }

    private var armedFor = 0L
    private var armed = false
    private var windowNanos = 0L

    /** Spins at most [windowNanos] before the tick due at [nextTickNanos]; zero disarms. */
    @JvmStatic
    fun arm(nextTickNanos: Long, windowNanos: Long) {
        val window = OVERRIDE_NANOS ?: windowNanos
        armed = window > 0
        armedFor = nextTickNanos
        this.windowNanos = window
    }

    @JvmStatic
    fun disarm() {
        armed = false
    }

    /** Whether the server thread would spin before the tick it is now waiting for; for status and tests. */
    val armedWindowNanos: Long get() = if (armed) windowNanos else 0L

    /**
     * Called instead of parking while waiting for the tick due at [nextTickNanos], at [now]: true when it spun one
     * slice (the caller then returns to its task loop), false when the caller should park as usual.
     */
    @JvmStatic
    fun spin(nextTickNanos: Long, now: Long): Boolean {
        if (!armed || nextTickNanos != armedFor) return false
        val remaining = nextTickNanos - now
        if (remaining > windowNanos) return false
        if (remaining > 0) {
            val end = System.nanoTime() + minOf(remaining, SLICE_NANOS)
            while (end - System.nanoTime() > 0) Thread.onSpinWait()
        }
        return true
    }

    /** Shortens a park until the tick due at [nextTickNanos] so it ends where the spin window starts. */
    @JvmStatic
    fun parkNanos(nextTickNanos: Long, nanos: Long): Long =
        if (!armed || nextTickNanos != armedFor) nanos else maxOf(0L, nanos - windowNanos)

    /** Whether `vanadium.serverSpinMicros` replaces the mode's window; for status output. */
    val overridden: Boolean get() = OVERRIDE_NANOS != null
}
