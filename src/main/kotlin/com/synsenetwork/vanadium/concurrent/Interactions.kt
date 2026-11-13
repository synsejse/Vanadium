package com.synsenetwork.vanadium.concurrent

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.tick.TickThread
import com.synsenetwork.vanadium.tick.WorkerPool
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import java.util.function.Consumer

/**
 * Ownership of entities while they tick with no spatial separation (the flat entity wave).
 *
 * Every entity tick owns its vehicle tree for the tick ([tick]). Anything it changes on another tree is a
 * foreign write: mixins at the mutation points call [enter] and [exit], which follow [OwnerLocks], so the
 * write waits for the target's own tick and nothing else. Operations two entities start on each other
 * (merging, breeding) own both through [enterPair]. Block changes and other writes to state that
 * entity ticks share call [enterShared] and run one at a time. Pushes, by far the most frequent foreign
 * write in a crowd, never wait: [deferPush] adds them to the target's inbox and the target applies them when
 * it next owns itself, or the wave's end does ([drainImpulses]). Reads of other entities take no lock, as between
 * regions.
 *
 * Outside the flat wave [active] is false and every hook is a no-op.
 */
object Interactions {
    /** What a foreign write changed; indexes [counts]. */
    enum class Kind { PUSH, VELOCITY, POSITION, REMOVE, DATA, HURT, MEMORY, PAIR, BLOCK, EXPLOSION, EVENT, OTHER }

    /** True on every thread while a flat entity wave runs. */
    @JvmField
    @Volatile
    var active = false

    private val locks = OwnerLocks(TimeUnit.MILLISECONDS.toNanos(java.lang.Long.getLong("vanadium.interactionTimeoutMillis", 100)))

    /**
     * The pool that applies a large wave's leftover pushes in parallel; null applies them on the server thread.
     * Set for each server lifecycle, to the pool that runs its waves.
     */
    @JvmField
    @Volatile
    var drainPool: WorkerPool? = null

    @Volatile
    private var caller: Thread? = null

    /** Targets whose inbox each thread filled first (one entry per empty-to-full change). */
    private val impulses = DeferredQueue<Entity>()
    private val callerContext = Context(impulses.register(null))
    private val applyInline = Consumer<Entity> { if (!it.isRemoved) applyImpulse(it) }
    private val applyOwned = Consumer<Entity>(::applyOwned)
    private val runDrainWave = Consumer<List<Runnable>> { slices -> runDrainWave(slices) }
    private val counts = Array(Kind.entries.size) { LongAdder() }
    private val reportedTimeouts = AtomicLong()
    private val lastTimeoutWarning = AtomicLong()
    private val lastSkipWarning = AtomicLong()
    private val skippedTicks = LongAdder()
    private const val MAX_FAILED_TICKS = 3

    /** Diagnostics: log foreign-write counts every this many waves; 0 disables. */
    private val LOG_EVERY_WAVES = java.lang.Long.getLong("vanadium.interactionLogWaves", 0)
    private var waves = 0L

    class Context(internal val pushed: DeferredQueue.Local<Entity>) : OwnerLocks.Context() {
        /** The entity whose wave task runs on this thread, and the vehicle tree it owns. */
        @JvmField var self: Entity? = null
        @JvmField var root: Entity? = null
    }

    /**
     * Server thread: runs [wave], whose tasks call [tick], then applies pushes nobody consumed before returning,
     * so nothing after the wave reads a velocity without them. Many leftovers apply as a wave on [pool].
     */
    fun runWave(wave: Runnable) = runWave(wave, drainPool)

    fun runWave(wave: Runnable, pool: WorkerPool?) {
        caller = Thread.currentThread()
        active = true
        var completed = false
        try {
            wave.run()
            completed = true
        } finally {
            active = false
            try {
                // After a failed wave, the leftovers apply inline: a second wave could hide the first failure.
                drainImpulses(if (completed) pool else null)
            } finally {
                active = false
                caller = null
            }
            warnAboutTimeouts()
            if (LOG_EVERY_WAVES > 0 && ++waves % LOG_EVERY_WAVES == 0L) {
                Vanadium.LOGGER.info("Entity interactions over {} waves: {} cycles={} timeouts={} skipped={}", LOG_EVERY_WAVES,
                    takeCounts(), cycles, timeouts, skipped)
            }
        }
    }

    /** Runs one entity's wave task as the owner of its vehicle tree. */
    @JvmStatic
    fun tick(entity: Entity, body: Consumer<Entity>) {
        val context = context()
        if (context == null || !active) {
            body.accept(entity)
            return
        }
        val root = entity.rootVehicle
        context.self = entity
        context.root = root
        locks.beginOwn(context, lock(root))
        try {
            applyImpulse(entity)
            body.accept(entity)
            val access = entity as InteractionAccess
            if (access.`vanadium$failedTicks`() != 0) access.`vanadium$setFailedTicks`(0)
        } catch (e: Exception) {
            survive(entity, e)
        } finally {
            locks.endOwn(context)
            context.self = null
            context.root = null
        }
    }

    /**
     * Before a write to [target]: true when the caller must pair it with [exit], having taken the target's
     * tree exclusively. That is the case for another tick's entity, and for the caller's own tree while it
     * is inside a foreign scope (its lock is released there). False for the caller's own tree otherwise and
     * for threads not ticking an entity.
     */
    @JvmStatic
    fun enter(target: Entity, kind: Kind): Boolean {
        val context = context() ?: return false
        val root = context.root ?: return false
        val targetRoot = if (target === context.self) root else target.rootVehicle
        if (targetRoot === root) {
            if (context.depth == 0) return false
            val own = context.ownLock ?: return false
            if (own.isHeldByCurrentThread) return false
            locks.enterForeign(context, own)
            return true
        }
        val lock = lock(targetRoot)
        if (context.depth > 0 && lock.isHeldByCurrentThread) return false
        counts[kind.ordinal].increment()
        locks.enterForeign(context, lock)
        return true
    }

    /**
     * Before an operation between the ticking entity and [other] that [other]'s tick may start in reverse in
     * the same instant (two items merging, two parents breeding, a mob taking an item that is merging): true
     * when the caller now owns both trees and must call [exitPair]. Both sides queue in id order, so the
     * second sees what the first did; the caller rechecks the operation's condition inside.
     */
    @JvmStatic
    fun enterPair(other: Entity, kind: Kind): Boolean {
        val context = context() ?: return false
        val root = context.root ?: return false
        val own = context.ownLock ?: return false
        val otherRoot = if (other === context.self) root else other.rootVehicle
        if (otherRoot === root) return false
        counts[kind.ordinal].increment()
        locks.enterPair(context, lock(otherRoot), root.id < otherRoot.id)
        return true
    }

    @JvmStatic
    fun exitPair() {
        val context = context()!!
        locks.exitForeign(context)
        locks.exitForeign(context)
    }

    /**
     * Before a write to world state that entity ticks share (blocks, explosions, listeners): such writes run
     * one at a time. Pair with [exit] when true.
     */
    @JvmStatic
    fun enterShared(kind: Kind): Boolean {
        val context = context() ?: return false
        if (context.root == null) return false
        counts[kind.ordinal].increment()
        locks.enterForeign(context, null)
        return true
    }

    @JvmStatic
    fun exit() = locks.exitForeign(context()!!)

    /**
     * A push on [target] from another entity's tick: true when it was added to the target's inbox instead of
     * being applied. The target applies it at the start of its own tick, or [runWave] does after the wave.
     */
    @JvmStatic
    fun deferPush(target: Entity, x: Double, y: Double, z: Double): Boolean {
        val context = context() ?: return false
        val root = context.root ?: return false
        if (target === context.self) return false
        val targetRoot = target.rootVehicle
        // Inside a scope that owns the target, or that left the caller's own tree open, apply directly: the
        // caller's remaining writes to it are few and take its lock through setDeltaMovement.
        if (targetRoot === root || context.depth > 0 && lock(targetRoot).isHeldByCurrentThread) return false
        val inbox = (target as InteractionAccess).`vanadium$impulse`()
        while (true) {
            val pending = inbox.get()
            if (inbox.compareAndSet(pending, pending?.add(x, y, z) ?: Vec3(x, y, z))) {
                if (pending == null) context.pushed.add(target)
                break
            }
        }
        counts[Kind.PUSH.ordinal].increment()
        return true
    }

    /**
     * An entity tick that throws normally stops the server. Ticks that overlap can fail in ways a serial
     * tick cannot (a value another tick removed between a check and its use), and such a failure does not
     * repeat. With `skipFailedEntityTicks` the rest of that entity's tick is skipped and logged; an entity
     * that fails [MAX_FAILED_TICKS] ticks in a row has a real bug and stops the server as usual.
     */
    private fun survive(entity: Entity, failure: Exception) {
        val access = entity as InteractionAccess
        val failed = access.`vanadium$failedTicks`() + 1
        if (!Vanadium.config.skipFailedEntityTicks || failed >= MAX_FAILED_TICKS) throw failure
        access.`vanadium$setFailedTicks`(failed)
        skippedTicks.increment()
        val now = System.nanoTime()
        val last = lastSkipWarning.get()
        if (last == 0L || now - last >= TimeUnit.SECONDS.toNanos(10)) {
            if (lastSkipWarning.compareAndSet(last, now)) {
                Vanadium.LOGGER.warn("Skipped the rest of one tick of {} at {} after an error ({} skipped so far); " +
                    "the server stops if it fails {} ticks in a row", entity.type, entity.blockPosition(),
                    skippedTicks.sum(), MAX_FAILED_TICKS, failure)
            }
        }
    }

    /** Entity ticks skipped after an error since the server started. */
    val skipped: Long get() = skippedTicks.sum()

    private fun applyImpulse(entity: Entity) {
        val inbox = (entity as InteractionAccess).`vanadium$impulse`()
        if (inbox.get() == null) return
        val impulse = inbox.getAndSet(null) ?: return
        entity.push(impulse.x, impulse.y, impulse.z)
    }

    /**
     * Applies pushes whose target had already ticked in the wave, or does not tick in it. Each target was listed
     * by the thread that first filled its inbox; one that consumed it in its tick has an empty inbox by now, and
     * removed entities keep theirs unapplied, as before. A few apply here; many apply as a wave in which each
     * target is owned as in its own tick, so a push whose override writes elsewhere still takes the usual scopes.
     */
    private fun drainImpulses(pool: WorkerPool?) {
        drainingPool = pool
        try {
            val rounds = impulses.drain(applyInline, applyOwned, if (pool != null) runDrainWave else null)
            if (rounds > 0) parallelDrains.add(rounds.toLong())
        } finally {
            drainingPool = null
        }
    }

    /** The pool of the drain in progress; server thread only. */
    private var drainingPool: WorkerPool? = null

    private fun runDrainWave(slices: List<Runnable>) {
        val pool = drainingPool!!
        active = true
        try {
            pool.runWave(slices)
        } finally {
            active = false
        }
    }

    private fun applyOwned(entity: Entity) {
        val inbox = (entity as InteractionAccess).`vanadium$impulse`()
        if (entity.isRemoved || inbox.get() == null) return
        val context = context() ?: return applyImpulse(entity)
        val root = entity.rootVehicle
        context.self = entity
        context.root = root
        locks.beginOwn(context, lock(root))
        try {
            applyImpulse(entity)
        } finally {
            locks.endOwn(context)
            context.self = null
            context.root = null
        }
    }

    private val parallelDrains = LongAdder()

    /** Parallel rounds of leftover pushes since the server started; for diagnostics and live checks. */
    val parallelPushDrains: Long get() = parallelDrains.sum()

    private fun lock(root: Entity) = (root as InteractionAccess).`vanadium$tickLock`()

    private fun context(): Context? {
        val thread = Thread.currentThread()
        if (thread is TickThread) {
            return thread.context as Context? ?: Context(impulses.register(thread)).also { thread.context = it }
        }
        return if (thread === caller) callerContext else null
    }

    /** Foreign writes since the last call, by kind, and lock timeouts; for reports and live checks. */
    fun takeCounts(): Map<Kind, Long> = Kind.entries.associateWith { counts[it.ordinal].sumThenReset() }

    /** Waits given up after the hard limit; each ran one write unprotected. */
    val timeouts: Long get() = locks.timeouts.get()

    /** Waits given up because two nested writers were waiting for each other. */
    val cycles: Long get() = locks.cycles.get()

    private fun warnAboutTimeouts() {
        val total = locks.timeouts.get()
        if (total == reportedTimeouts.get()) return
        val now = System.nanoTime()
        val last = lastTimeoutWarning.get()
        if (last != 0L && now - last < TimeUnit.SECONDS.toNanos(60) || !lastTimeoutWarning.compareAndSet(last, now)) return
        Vanadium.LOGGER.warn("{} entity interaction lock(s) timed out and ran unprotected; a lock held around an " +
            "interaction is probably missing from Vanadium's catalogue. Please report this with a thread dump.",
            total - reportedTimeouts.getAndSet(total))
    }
}
