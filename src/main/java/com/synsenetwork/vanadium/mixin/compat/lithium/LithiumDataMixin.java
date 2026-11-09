package com.synsenetwork.vanadium.mixin.compat.lithium;

import com.synsenetwork.vanadium.collections.SynchronizedLong2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lithium's per-world chunk-section change callbacks are registered by hopper trackers in the parallel
 * block-entity stage and removed on chunk unload, possibly off the server thread. Both are rare.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.world.LithiumData$Data", remap = false)
public abstract class LithiumDataMixin {
    @Redirect(method = "<init>(Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At(value = "NEW", target = "it/unimi/dsi/fastutil/longs/Long2ReferenceOpenHashMap", remap = false), remap = false)
    private static Long2ReferenceOpenHashMap<?> vanadium$synchronizedCallbacks() {
        return new SynchronizedLong2ReferenceOpenHashMap<>();
    }
}
