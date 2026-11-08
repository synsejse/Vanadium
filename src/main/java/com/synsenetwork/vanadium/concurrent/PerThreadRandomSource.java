package com.synsenetwork.vanadium.concurrent;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

/**
 * A level-wide random source that gives every thread its own unsynchronized generator.
 *
 * <p>Vanilla's level random is one {@code LegacyRandomSource}; with parallel random ticks,
 * scheduled ticks and entity AI it became a single contended monitor. Callers never relied on a
 * shared sequence (the seed is unique per level instance), so independent per-thread streams
 * preserve the distribution without coordination. {@link #setSeed} reseeds only the calling
 * thread's stream, which matches its previous effect on the following calls of that thread.
 */
public final class PerThreadRandomSource implements RandomSource {
    private final ThreadLocal<RandomSource> local;

    public PerThreadRandomSource(long seed) {
        this.local = ThreadLocal.withInitial(
                () -> new SingleThreadedRandomSource(seed ^ Thread.currentThread().threadId() * 0x9E3779B97F4A7C15L));
    }

    public RandomSource current() {
        return local.get();
    }

    @Override public RandomSource fork() { return local.get().fork(); }
    @Override public PositionalRandomFactory forkPositional() { return local.get().forkPositional(); }
    @Override public void setSeed(long seed) { local.get().setSeed(seed); }
    @Override public int nextInt() { return local.get().nextInt(); }
    @Override public int nextInt(int bound) { return local.get().nextInt(bound); }
    @Override public long nextLong() { return local.get().nextLong(); }
    @Override public boolean nextBoolean() { return local.get().nextBoolean(); }
    @Override public float nextFloat() { return local.get().nextFloat(); }
    @Override public double nextDouble() { return local.get().nextDouble(); }
    @Override public double nextGaussian() { return local.get().nextGaussian(); }
}
