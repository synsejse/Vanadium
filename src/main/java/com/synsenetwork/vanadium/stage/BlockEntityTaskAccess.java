package com.synsenetwork.vanadium.stage;

/**
 * Direct {@link Runnable} dispatch and cached serial-rule classification on
 * {@code LevelChunk$RebindableTickingBlockEntityWrapper}, removing per-tick method-reference
 * allocations and repeated registry lookups in {@link BlockEntityStage}.
 */
public interface BlockEntityTaskAccess extends Runnable {
    Object vanadium$serialRulesKey();
    boolean vanadium$isSerial();
    void vanadium$setSerial(Object rulesKey, boolean serial);
}
