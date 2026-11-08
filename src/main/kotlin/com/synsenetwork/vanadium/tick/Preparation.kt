package com.synsenetwork.vanadium.tick

/** Independent jobs run through [TickScheduler.prepare], named for profiles and JFR events. */
enum class Preparation {
    /** Entity stage collection: chunk keys and tasks for large entity lists. */
    ENTITY_BATCH,

    /** Random-tick collection: ticking chunk keys and tasks. */
    CHUNK_BATCH,

    /** Natural spawn counts over entity slices. */
    SPAWN_COUNTS,

    /** Tracker movement scans before the area map is updated. */
    TRACKING_MOVES,

    /** Per-player tracking visibility diffs. */
    TRACKING_DIFFS,

    /** Chunk section serialization for a player's chunk batch. */
    CHUNK_PACKETS,
}
