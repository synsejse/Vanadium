package com.synsenetwork.vanadium.mixin.world.level.levelgen;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Replaces blanket synchronization on {@link LegacyRandomSource} (every entity's {@code random} and any
 * {@code RandomSource.create()}) with a lock-free CAS loop identical to {@code ThreadSafeLegacyRandomSource},
 * keeping only {@link MarsagliaPolarGaussian} synchronized so {@code next*} never takes a monitor.
 */
@Mixin(LegacyRandomSource.class)
public abstract class LegacyRandomSourceMixin {
    @Shadow @Final private AtomicLong seed;
    @Shadow @Final private MarsagliaPolarGaussian gaussianSource;

    /**
     * @author Vanadium
     * @reason Atomic set without throwing {@code ThreadingDetector} on concurrent seed updates.
     */
    @Overwrite
    public void setSeed(long seed) {
        this.seed.set((seed ^ 25214903917L) & 281474976710655L);
        synchronized (this.gaussianSource) {
            this.gaussianSource.reset();
        }
    }

    /**
     * @author Vanadium
     * @reason Lock-free CAS loop instead of vanilla's single-try CAS + threading exception or blanket monitor.
     */
    @Overwrite
    public int next(int bits) {
        AtomicLong s = this.seed;
        long current;
        long next;
        do {
            current = s.get();
            next = (current * 25214903917L + 11L) & 281474976710655L;
        } while (!s.compareAndSet(current, next));
        return (int) (next >>> (48 - bits));
    }

    /**
     * @author Vanadium
     * @reason Guard {@link MarsagliaPolarGaussian}'s cached pair without synchronizing {@link #next(int)}.
     */
    @Overwrite
    public double nextGaussian() {
        synchronized (this.gaussianSource) {
            return this.gaussianSource.nextGaussian();
        }
    }
}
