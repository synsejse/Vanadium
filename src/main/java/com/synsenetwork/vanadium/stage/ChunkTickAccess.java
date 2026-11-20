package com.synsenetwork.vanadium.stage;

import com.synsenetwork.vanadium.tick.ChunkTickBatch;

/** Mixed into {@code ServerChunkCache}: the level's persistent chunk tick tasks. */
public interface ChunkTickAccess {
    ChunkTickBatch vanadium$chunkTicks();
}
