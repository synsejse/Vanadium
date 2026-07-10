package com.synsenetwork.vanadium.tick;

/** The sequential parts of a world tick, in vanilla order. */
public enum Stage {
    SCHEDULED_TICK,
    CHUNK,
    TRACKING,
    ENTITY,
    BLOCK_ENTITY
}
