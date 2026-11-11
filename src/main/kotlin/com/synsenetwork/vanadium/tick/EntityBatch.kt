package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.concurrent.Interactions
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import java.util.function.Consumer

/**
 * Per-level ENTITY collection: vanilla's whole per-entity body runs as each entity's task, and the tasks form
 * one wave with no regions. Each task owns its entity ([Interactions]).
 */
class EntityBatch : BatchEnqueue.Mapper<Entity> {
    private val batch = BatchEnqueue(Preparation.ENTITY_BATCH, this)
    private val wave = ArrayList<Runnable>()
    private var body: Consumer<Entity>? = null
    private var labels = false

    fun add(entity: Entity) = batch.add(entity)

    /** Runs this tick's entities: serial-rule entities inline with [body], in order, then the rest as one wave. */
    fun dispatch(scheduler: TickScheduler, body: Consumer<Entity>) {
        this.body = body
        labels = scheduler.detailedDiagnostics
        try {
            batch.dispatch(scheduler, Stage.ENTITY, wave, body::accept)
            scheduler.runFlat(Stage.ENTITY, wave)
        } finally {
            wave.clear()
            this.body = null
        }
    }

    /** Only serial, skipped or parallel matters here: the wave has no regions to place an entity in. */
    override fun key(item: Entity): Long = when {
        item.isRemoved -> BatchEnqueue.SKIP
        EntityTickRules.requiresSerial(item) -> BatchEnqueue.SERIAL
        else -> 0L
    }

    override fun task(item: Entity): Runnable {
        val tick = body!!
        val task = Runnable { Interactions.tick(item, tick) }
        return if (labels) WaveDiagnostics.NamedTask(BuiltInRegistries.ENTITY_TYPE.getKey(item.type).toString(), task) else task
    }
}
