package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.config.DeveloperProperties

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

    private var armedFor = 0L
    private var armed = false
    private var windowNanos = 0L

    /** Spins at most [windowNanos] before the tick due at [nextTickNanos]; zero disarms. */
    @JvmStatic
    fun arm(nextTickNanos: Long, windowNanos: Long) {
        armed = windowNanos > 0
        armedFor = nextTickNanos
        this.windowNanos = windowNanos
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
}

/**
 * What idle workers do between ticks that leave slack. Chosen with `idleWorkers` in the config, or live with
 * `/vanadium set idleWorkers`.
 */
enum class IdleWorkers {
    /**
     * Workers park when a tick ends and wake when the next starts; the server thread spins through its wait for
     * the next tick ([ServerSpin]). About one core while ticks run parallel work.
     */
    BALANCED,

    /** Workers and the server thread spin through the gap between ticks: every worker that ran holds a core. */
    HOT,

    /** Nothing spins between ticks: workers park when a tick ends and wake when the next one starts. */
    LEAN;

    val configName: String get() = name.lowercase()

    companion object {
        val names: List<String> = entries.map { it.configName }

        /** Case-insensitive; null for anything else. */
        fun parse(text: String): IdleWorkers? = entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
    }
}

/**
 * How long idle workers spin before parking, from the server's tick events. Spinning costs a core per
 * worker; parking costs a wake-up (hundreds of microseconds on a VM) when the next wave comes, and a core
 * left idle for the rest of a 50 ms tick comes back slow.
 *
 * - Under pressure (sprinting, or the last tick ended less than [longNanos] before the next was due), workers
 *   spin [longNanos] through every gap, including the one between ticks: throughput is what matters, and the
 *   next wave is close.
 * - With slack, workers spin [shortNanos] between waves of a tick ([hotNanos] in [IdleWorkers.HOT]); when the
 *   tick ends, [betweenTicks] says what the pool does until the next one.
 *
 * Without tick events (tests, benchmarks, before the first tick) workers spin [longNanos]. Events come from the
 * server thread; workers only read [workerSpinNanos] and [spinUntil].
 */
internal class SpinPolicy(
    private val longNanos: Long,
    private val shortNanos: Long,
    private val hotNanos: Long = HOT_SPIN_MICROS * 1_000L,
    /** How long a wave's caller spins for its tail under pressure; see [callerSpinNanos]. */
    private val callerPressureNanos: Long = CALLER_PRESSURE_SPIN_NANOS,
) {
    /** What the pool does at the end of a tick. */
    enum class Gap {
        /** Under pressure: keep the spin budget, nothing more. */
        PRESSURE,

        /** Spin until just after the next tick is due. */
        SPIN_THROUGH,

        /** Park now; the next tick's start wakes them. */
        PARK,
    }

    @Volatile
    var workerSpinNanos: Long = longNanos
        private set

    /**
     * How long a wave's caller (the server thread) spins for the wave's tail before parking. Its wake-up delays the
     * tick itself, not only a later wave, and it waits on its own processor; so under pressure (and without tick
     * events) it spins through tails of up to [callerPressureNanos]: a combat tick's tail is one long entity tick,
     * often longer than a worker's budget. With slack it spins as long as an idle worker would. Read by the caller.
     */
    var callerSpinNanos: Long = maxOf(longNanos, callerPressureNanos)
        private set

    /**
     * [System.nanoTime] until which idle workers keep spinning whatever [workerSpinNanos] says: set in
     * [IdleWorkers.HOT] by [tickEnded] to just past the next tick, and back to the present when workers are to park.
     */
    @Volatile
    var spinUntil: Long = System.nanoTime()
        private set

    var mode: IdleWorkers = IdleWorkers.BALANCED
        private set

    /** Whether the last tick ended with the next one due soon (see the class comment), or the server was sprinting. */
    var underPressure = true
        private set

    var inTick = true
        private set

    /** Set by [tickEnded]: what the pool does until the next tick. */
    var betweenTicks = Gap.PRESSURE
        private set

    /**
     * Set by [tickEnded]: how long before the next tick the server thread spins instead of sleeping ([ServerSpin]).
     * [IdleWorkers.BALANCED] and [IdleWorkers.HOT] spin through the whole gap ([Long.MAX_VALUE]): a 1 or 5 ms window
     * measured no faster than none (docs/PERFORMANCE.md). Zero in [IdleWorkers.LEAN] and after a tick that ran
     * nothing in parallel.
     */
    var serverWindowNanos = 0L
        private set

    /** Applies [mode] from the next tick event. */
    fun configure(mode: IdleWorkers) {
        this.mode = mode
    }

    fun tickStarted() {
        inTick = true
        update()
    }

    /**
     * Returns [betweenTicks]. Spinning through the gap is arranged before the spin budget drops, so no worker
     * parks in between; [graceNanos] is how long past the due time workers keep spinning for a late tick. Without
     * parallel work in the tick ([ranParallel] false) nobody spins through the gap.
     */
    fun tickEnded(nanosUntilNextTick: Long, sprinting: Boolean, graceNanos: Long = 0L, ranParallel: Boolean = true): Gap {
        inTick = false
        underPressure = sprinting || nanosUntilNextTick < longNanos
        serverWindowNanos = if (!ranParallel || mode == IdleWorkers.LEAN) 0L else Long.MAX_VALUE
        val now = System.nanoTime()
        when {
            underPressure -> betweenTicks = Gap.PRESSURE
            mode == IdleWorkers.HOT && ranParallel -> {
                betweenTicks = Gap.SPIN_THROUGH
                spinUntil = now + nanosUntilNextTick + graceNanos
            }
            else -> {
                betweenTicks = Gap.PARK
                // A spin-through for a tick that ended within its grace period must not keep workers spinning.
                spinUntil = now
            }
        }
        update()
        return betweenTicks
    }

    private fun update() {
        workerSpinNanos = when {
            mode == IdleWorkers.HOT -> if (inTick || underPressure) maxOf(hotNanos, longNanos) else 0L
            underPressure -> longNanos
            inTick -> shortNanos
            else -> 0L
        }
        callerSpinNanos = if (underPressure) maxOf(workerSpinNanos, callerPressureNanos) else workerSpinNanos
    }

    companion object {
        /** Idle spin under pressure: two milliseconds covers inter-wave gaps on the Xeon VM. */
        private const val LONG_SPIN_MICROS = 2_000L

        /**
         * Idle spin between waves of a tick with slack (`vanadium.workerSlackSpinMicros`): covers the usual serial
         * gaps, not long serial phases.
         */
        private const val SHORT_SPIN_MICROS = 500L

        /** Idle spin between waves in [IdleWorkers.HOT]: longer than any normal serial phase of a tick. */
        private const val HOT_SPIN_MICROS = 50_000L

        /**
         * Caller spin for a wave's tail under pressure (`vanadium.callerPressureSpinMicros`): well past a combat
         * tick's tail, which is one long entity tick (1 to 2.5 ms on average in a 600-mob fight at 36 to 48 workers,
         * one villager's path search; measured for 2d4cc99 on 2026-11-14), so the caller parks only for waves that
         * run long.
         */
        private const val CALLER_PRESSURE_SPIN_MICROS = 10_000L

        /** What the two spin overrides accept, in microseconds: none to a second. */
        private val SPIN_OVERRIDE_MICROS = 0L..1_000_000L

        /** Read once per JVM; an invalid value warns and keeps the default. */
        private val SLACK_SPIN_NANOS =
            DeveloperProperties.long("vanadium.workerSlackSpinMicros", SHORT_SPIN_MICROS, SPIN_OVERRIDE_MICROS) * 1_000L
        private val CALLER_PRESSURE_SPIN_NANOS =
            DeveloperProperties.long("vanadium.callerPressureSpinMicros", CALLER_PRESSURE_SPIN_MICROS, SPIN_OVERRIDE_MICROS) * 1_000L

        /**
         * The production policy: `vanadium.workerSlackSpinMicros` sets the short spin and
         * `vanadium.callerPressureSpinMicros` the caller's under pressure; everything else is fixed.
         */
        fun fromSystemProperties(): SpinPolicy = SpinPolicy(LONG_SPIN_MICROS * 1_000L, SLACK_SPIN_NANOS)
    }
}
