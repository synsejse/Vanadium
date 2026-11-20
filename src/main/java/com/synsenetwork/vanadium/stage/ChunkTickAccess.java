package com.synsenetwork.vanadium.stage;

/** Mixed into {@code ServerChunkCache}: the level's spawning and chunk-tick stage. */
public interface ChunkTickAccess {
    ChunkStage vanadium$chunkStage();
}
