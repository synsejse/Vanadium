package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.concurrent.Interactions
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import java.util.function.Consumer

/**
 * Per-level ENTITY collection: vanilla's whole per-entity body runs as each entity's task. With flat entity
 * ticking the tasks form one wave with no regions, each owning its entity ([Interactions]); otherwise they are
 * queued into the stage's regions.
 */
class EntityBatch : BatchEnqueue.Mapper<Entity> {
    private val batch = BatchEnqueue(Preparation.ENTITY_BATCH, this)
    private val wave = ArrayList<Runnable>()
    private var body: Consumer<Entity>? = null
    private var labels = false
    private var flat = false

    fun add(entity: Entity) = batch.add(entity)

    /** Dispatches this tick's entities; serial-rule entities run [body] inline, in order, before any wave. */
    fun dispatch(scheduler: TickScheduler, body: Consumer<Entity>) {
        this.body = body
        labels = scheduler.detailedDiagnostics
        flat = Vanadium.config.flatEntities
        try {
            batch.dispatch(scheduler, Stage.ENTITY, if (flat) wave else null, body::accept)
            if (flat) scheduler.runFlat(Stage.ENTITY, wave)
        } finally {
            wave.clear()
            this.body = null
        }
    }

    override fun key(item: Entity): Long = when {
        item.isRemoved -> BatchEnqueue.SKIP
        EntityTickRules.requiresSerial(item) -> BatchEnqueue.SERIAL
        else -> item.chunkPosition().pack()
    }

    override fun task(item: Entity): Runnable {
        val tick = body!!
        val task = if (flat) Runnable { Interactions.tick(item, tick) } else Runnable { tick.accept(item) }
        return if (labels) WaveDiagnostics.NamedTask(BuiltInRegistries.ENTITY_TYPE.getKey(item.type).toString(), task) else task
    }
}
