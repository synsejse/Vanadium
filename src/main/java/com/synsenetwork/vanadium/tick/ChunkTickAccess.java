package com.synsenetwork.vanadium.tick;

/** Mixed into {@code ServerChunkCache}: the level's persistent chunk tick tasks. */
public interface ChunkTickAccess {
    ChunkTickBatch vanadium$chunkTicks();
}
