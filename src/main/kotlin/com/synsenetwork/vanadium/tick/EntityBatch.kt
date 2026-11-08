package com.synsenetwork.vanadium.tick

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import java.util.function.Consumer

/** Per-level ENTITY collection: vanilla's whole per-entity body runs as each entity's cell task. */
class EntityBatch : BatchEnqueue.Mapper<Entity> {
    private val batch = BatchEnqueue(Preparation.ENTITY_BATCH, this)
    private var body: Consumer<Entity>? = null
    private var labels = false

    fun add(entity: Entity) = batch.add(entity)

    /** Dispatches this tick's entities; serial-rule entities run [body] inline, in order. */
    fun dispatch(scheduler: TickScheduler, body: Consumer<Entity>) {
        this.body = body
        labels = scheduler.detailedDiagnostics
        try {
            batch.dispatch(scheduler, Stage.ENTITY, body::accept)
        } finally {
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
        val task = Runnable { tick.accept(item) }
        return if (labels) WaveDiagnostics.NamedTask(BuiltInRegistries.ENTITY_TYPE.getKey(item.type).toString(), task) else task
    }
}
