package com.synsenetwork.vanadium.tick;

/** Mixed into {@code ChunkMap}: while set, ticking-chunk iteration records holders into the batch. */
public interface ChunkTickSink {
    void vanadium$setChunkTickSink(ChunkTickBatch batch);
}
