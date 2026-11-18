package com.synsenetwork.vanadium.coldexplosion

import com.synsenetwork.vanadium.concurrent.Interactions
import com.synsenetwork.vanadium.tick.WorkerPool
import java.util.concurrent.ConcurrentLinkedQueue
import jdk.jfr.Category
import jdk.jfr.Description
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity

/**
 * Every `ServerLevel.explode` call since startup, recorded by the fixture's `ExplosionObserver` mixin on whichever
 * thread runs it. `ServerLevel` is transformed at startup, so observing it adds nothing to the first explosion's
 * class loading: the measured span covers constructing `ServerExplosion` (its first load and Mixin transform), its
 * `explode()` and the explosion packets to nearby players.
 */
object ExplosionLog {
    /** How the explosion was reached: inside the flat entity wave on a worker or its caller, or outside it. */
    enum class Route { ENTITY_WAVE_WORKER, ENTITY_WAVE_CALLER, OUTSIDE_ENTITY_WAVE }

    class Record(
        val source: Entity?,
        /** `MinecraftServer.tickCount` while it ran: the ID of the tick it belongs to. */
        val tick: Int,
        val thread: String,
        val route: Route,
        /** `System.nanoTime()` at `explode`'s start and end. */
        val startNanos: Long,
        val endNanos: Long,
    )

    private class Pending {
        var start = 0L
        var event: ColdExplosionEvent? = null
    }

    private val pending = ThreadLocal.withInitial(::Pending)

    @JvmField
    val records = ConcurrentLinkedQueue<Record>()

    @JvmStatic
    fun begin() {
        val state = pending.get()
        state.event = ColdExplosionEvent().also { it.begin() }
        state.start = System.nanoTime()
    }

    @JvmStatic
    fun end(level: ServerLevel, source: Entity?) {
        val end = System.nanoTime()
        val state = pending.get()
        val route = when {
            !Interactions.active -> Route.OUTSIDE_ENTITY_WAVE
            WorkerPool.isWorkerThread() -> Route.ENTITY_WAVE_WORKER
            else -> Route.ENTITY_WAVE_CALLER
        }
        val record = Record(source, level.server.tickCount, Thread.currentThread().name, route, state.start, end)
        records.add(record)
        state.event?.let { event ->
            state.event = null
            event.end()
            if (event.shouldCommit()) {
                event.tick = record.tick
                event.source = source?.let { net.minecraft.world.entity.EntityType.getKey(it.type).toString() }
                event.route = route.name
                event.commit()
            }
        }
    }

    /**
     * Loads and initialises everything [begin] and [end] use on the server thread at setup, so the observed
     * explosion is not charged for the observer's own first use. Records nothing.
     */
    fun prime() {
        pending.get()
        ColdExplosionEvent().let { it.begin(); it.end() }
        Record(null, 0, "", Route.OUTSIDE_ENTITY_WAVE, 0, 0).route.name
        Interactions.active
        WorkerPool.isWorkerThread()
        net.minecraft.world.entity.EntityType.getKey(net.minecraft.world.entity.EntityTypes.CREEPER)
    }
}

/** A `ServerLevel.explode` call (or the load-only control's class load) on the JFR timeline, on its own thread. */
@Name("vanadium.ColdExplosion")
@Label("Cold Explosion Fixture Explosion")
@Category("Vanadium", "Cold Explosion")
@Description("ServerLevel.explode, from its start to its return, or the load-only ServerExplosion control")
class ColdExplosionEvent : Event() {
    @field:Label("Tick")
    @JvmField var tick = 0

    @field:Label("Source")
    @JvmField var source: String? = null

    @field:Label("Route")
    @JvmField var route: String? = null
}
