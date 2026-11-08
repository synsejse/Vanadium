package com.synsenetwork.vanadium.mixin.util.profiling;

import com.synsenetwork.vanadium.tick.WorkerPool;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Entity code asks for the profiler several times per mob per tick. While the server's tick profiler
 * is active, vanilla resolves it through a thread-local on every call; Vanadium's workers never have a
 * profiler of their own, so they get the default filler directly.
 */
@Mixin(Profiler.class)
public abstract class ProfilerMixin {
    @Shadow @Final private static ThreadLocal<@Nullable ProfilerFiller> ACTIVE;
    @Shadow @Final private static AtomicInteger ACTIVE_COUNT;

    @Shadow
    private static ProfilerFiller getDefaultFiller() {
        throw new AssertionError();
    }

    /**
     * @author Vanadium
     * @reason Same result without a thread-local lookup on worker threads.
     */
    @Overwrite
    public static ProfilerFiller get() {
        if (ACTIVE_COUNT.get() == 0 || WorkerPool.isWorkerThread()) return getDefaultFiller();
        return Objects.requireNonNullElseGet(ACTIVE.get(), ProfilerMixin::getDefaultFiller);
    }
}
