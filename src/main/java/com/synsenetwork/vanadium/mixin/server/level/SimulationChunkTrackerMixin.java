package com.synsenetwork.vanadium.mixin.server.level;

import com.synsenetwork.vanadium.collections.ConcurrentLongByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimulationChunkTracker.class)
public abstract class SimulationChunkTrackerMixin {
    @Shadow
    @Final
    @Mutable
    protected Long2ByteMap chunks;

    /**
     * Installed explicitly: Mixin may drop shadow-field initializers once the class adds fields of its own. The
     * constructor has already set vanilla's map's default level (33, which C2ME raises), so it carries over.
     * Versioned: {@code ChunkStage} caches the ticking set.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$install(CallbackInfo ci) {
        ConcurrentLongByteMap replacement = new ConcurrentLongByteMap(true);
        replacement.defaultReturnValue(chunks.defaultReturnValue());
        chunks = replacement;
    }
}
