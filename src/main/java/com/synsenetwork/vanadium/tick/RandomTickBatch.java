package com.synsenetwork.vanadium.tick;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Per-level CHUNK collection. While vanilla iterates entity-ticking positions, the server thread
 * only records each chunk holder; workers resolve the holder's ticking chunk (a future lookup that
 * misses the server thread's cache) and its cell. Holders without a ticking chunk are skipped,
 * exactly as vanilla skips them.
 */
public final class RandomTickBatch implements BatchEnqueue.Mapper<ChunkHolder> {
    private final BatchEnqueue<ChunkHolder> batch = new BatchEnqueue<>(this);
    private final ServerLevel level;
    private int tickSpeed;

    public RandomTickBatch(ServerLevel level) {
        this.level = level;
    }

    /** Starts a pass; all chunks of one tick share the speed vanilla reads once per pass. */
    public void begin(int tickSpeed) {
        this.tickSpeed = tickSpeed;
    }

    public void add(ChunkHolder holder) {
        batch.add(holder);
    }

    public void dispatch(TickScheduler scheduler) {
        // Serial items do not occur: every resolved chunk is queued into its cell.
        batch.dispatch(scheduler, Stage.CHUNK, holder -> { });
    }

    @Override public long key(ChunkHolder holder) {
        LevelChunk chunk = holder.getTickingChunk();
        return chunk == null ? BatchEnqueue.SKIP : chunk.getPos().pack();
    }

    @Override public Runnable task(ChunkHolder holder) {
        LevelChunk chunk = holder.getTickingChunk();
        int speed = tickSpeed;
        return () -> level.tickChunk(chunk, speed);
    }
}
