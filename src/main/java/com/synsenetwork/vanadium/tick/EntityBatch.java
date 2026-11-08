package com.synsenetwork.vanadium.tick;

import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

/** Per-level ENTITY collection: vanilla's per-entity body runs as each entity's cell task. */
public final class EntityBatch implements BatchEnqueue.Mapper<Entity> {
    private final BatchEnqueue<Entity> batch = new BatchEnqueue<>(this);
    private Consumer<Entity> body;
    private boolean labels;

    public void add(Entity entity) {
        batch.add(entity);
    }

    /** Dispatches this tick's entities; serial-rule entities run {@code body} inline, in order. */
    public void dispatch(TickScheduler scheduler, Consumer<Entity> body) {
        this.body = body;
        this.labels = scheduler.detailedDiagnostics();
        try {
            batch.dispatch(scheduler, Stage.ENTITY, body);
        } finally {
            this.body = null;
        }
    }

    @Override public long key(Entity entity) {
        if (entity.isRemoved()) return BatchEnqueue.SKIP;
        if (EntityTickRules.requiresSerial(entity)) return BatchEnqueue.SERIAL;
        return entity.chunkPosition().pack();
    }

    @Override public Runnable task(Entity entity) {
        Consumer<Entity> tick = body;
        Runnable task = () -> tick.accept(entity);
        // Detailed slow-wave reports name the entity type of the running task.
        return labels ? new WaveDiagnostics.NamedTask(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), task) : task;
    }
}
