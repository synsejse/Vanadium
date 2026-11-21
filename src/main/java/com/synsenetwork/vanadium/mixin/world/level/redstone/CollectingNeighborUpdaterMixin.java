package com.synsenetwork.vanadium.mixin.world.level.redstone;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.tick.TickThread;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Each thread runs its own neighbor-update cascade buffer instead of serializing the whole level on one
 * monitor. Vanilla clears {@code stack}, {@code addedThisLayer}, and {@code count} in {@code finally} at the
 * end of {@code runUpdates()}, so no state carries across cascades; parallel region stages isolate block
 * mutations spatially, and flat-wave {@code setBlock} calls serialize through {@code Interactions}.
 */
@Mixin(CollectingNeighborUpdater.class)
public abstract class CollectingNeighborUpdaterMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private int maxChainedNeighborUpdates;
    @Shadow private @Nullable Consumer<BlockPos> debugListener;

    @Shadow
    private void addAndRun(BlockPos pos, CollectingNeighborUpdater.NeighborUpdates entry) {
        throw new AssertionError();
    }

    @Unique
    private Thread vanadium$ownerThread;

    @Unique
    private boolean vanadium$isDelegate;

    @Unique
    private volatile @Nullable Consumer<BlockPos> vanadium$sharedDebugListener;

    @Unique
    private volatile @Nullable ThreadLocal<CollectingNeighborUpdater> vanadium$delegates;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$initOwner(Level level, int maxChainedNeighborUpdates, CallbackInfo ci) {
        this.vanadium$ownerThread = Thread.currentThread();
    }

    @WrapMethod(method = "setDebugListener")
    private void vanadium$captureDebugListener(@Nullable Consumer<BlockPos> listener, Operation<Void> original) {
        this.vanadium$sharedDebugListener = listener;
        original.call(listener);
    }

    @WrapMethod(method = "addAndRun")
    private void vanadium$perThreadAddAndRun(BlockPos pos, CollectingNeighborUpdater.NeighborUpdates entry, Operation<Void> original) {
        if (this.vanadium$isDelegate) {
            original.call(pos, entry);
            return;
        }
        Thread current = Thread.currentThread();
        if (current == this.vanadium$ownerThread) {
            original.call(pos, entry);
            return;
        }
        CollectingNeighborUpdater delegate = vanadium$delegateFor(current);
        CollectingNeighborUpdaterMixin mixin = (CollectingNeighborUpdaterMixin) (Object) delegate;
        Consumer<BlockPos> listener = this.vanadium$sharedDebugListener;
        if (mixin.debugListener != listener) {
            delegate.setDebugListener(listener);
        }
        mixin.vanadium$invokeAddAndRun(pos, entry);
    }

    @Unique
    private void vanadium$invokeAddAndRun(BlockPos pos, CollectingNeighborUpdater.NeighborUpdates entry) {
        this.addAndRun(pos, entry);
    }

    @Unique
    private CollectingNeighborUpdater vanadium$delegateFor(Thread thread) {
        if (thread instanceof TickThread tickThread) {
            if (tickThread.neighborUpdaterOwner == this) {
                return (CollectingNeighborUpdater) tickThread.neighborUpdater;
            }
            CollectingNeighborUpdater delegate = vanadium$threadLocal().get();
            tickThread.neighborUpdaterOwner = this;
            tickThread.neighborUpdater = delegate;
            return delegate;
        }
        return vanadium$threadLocal().get();
    }

    @Unique
    private ThreadLocal<CollectingNeighborUpdater> vanadium$threadLocal() {
        ThreadLocal<CollectingNeighborUpdater> local = this.vanadium$delegates;
        if (local != null) return local;
        synchronized (this) {
            local = this.vanadium$delegates;
            if (local == null) {
                local = ThreadLocal.withInitial(this::vanadium$newDelegate);
                this.vanadium$delegates = local;
            }
            return local;
        }
    }

    @Unique
    private CollectingNeighborUpdater vanadium$newDelegate() {
        CollectingNeighborUpdater delegate = new CollectingNeighborUpdater(this.level, this.maxChainedNeighborUpdates);
        ((CollectingNeighborUpdaterMixin) (Object) delegate).vanadium$isDelegate = true;
        return delegate;
    }
}
