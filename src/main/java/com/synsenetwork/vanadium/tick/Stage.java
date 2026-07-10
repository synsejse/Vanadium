package com.synsenetwork.vanadium.tick;

/** The four sequential parts of a world tick, in vanilla order. */
public enum Stage {
    SCHEDULED_TICK,
    CHUNK,
    ENTITY,
    BLOCK_ENTITY
}
