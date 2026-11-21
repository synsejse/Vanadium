package com.synsenetwork.vanadium.mixin.server.level;

import com.synsenetwork.vanadium.collections.ConcurrentLongByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.ChunkTracker;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes {@link DistanceManager.FixedPlayerDistanceChunkTracker} lock-free on the read path:
 * {@code hasPlayersNearby} calls {@code runAllUpdates()} on every spawn check from every worker, so skipping
 * {@code DynamicGraphMinFixedPoint}'s synchronized {@code runUpdates} when {@code hasWork()} is false and
 * backing {@code chunks} with a {@link ConcurrentLongByteMap} eliminates cross-worker monitor contention
 * during parallel spawning.
 */
@Mixin(DistanceManager.FixedPlayerDistanceChunkTracker.class)
public abstract class FixedPlayerDistanceChunkTrackerMixin extends ChunkTracker {
    protected FixedPlayerDistanceChunkTrackerMixin(int levels, int expectedLevelSize, int expectedTotalSize) {
        super(levels, expectedLevelSize, expectedTotalSize);
    }

    @Shadow
    @Final
    @Mutable
    protected Long2ByteMap chunks;

    /** Installed explicitly at constructor RETURN, copying vanilla's configured default level. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$installConcurrentChunks(CallbackInfo ci) {
        ConcurrentLongByteMap replacement = new ConcurrentLongByteMap(false);
        replacement.defaultReturnValue(chunks.defaultReturnValue());
        chunks = replacement;
    }

    /**
     * @author Vanadium
     * @reason Avoid entering {@code DynamicGraphMinFixedPoint}'s synchronized {@code runUpdates} on every
     *         concurrent {@code hasPlayersNearby} lookup when no player-distance updates are pending.
     */
    @Overwrite
    public void runAllUpdates() {
        if (this.hasWork()) {
            this.runUpdates(Integer.MAX_VALUE);
        }
    }
}
