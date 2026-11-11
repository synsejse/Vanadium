package com.synsenetwork.vanadium.mixin.compat.lithium;

import com.synsenetwork.vanadium.collections.SynchronizedLong2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.caffeinemc.mods.lithium.common.world.LithiumData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lithium's per-world chunk-section change callbacks are registered by hopper trackers on any worker in the
 * block-entity stage and removed by chunk-unload callbacks on the server thread. Both are rare.
 */
@Mixin(LithiumData.Data.class)
public abstract class LithiumDataMixin {
    @Redirect(method = "<init>(Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At(value = "NEW", target = "it/unimi/dsi/fastutil/longs/Long2ReferenceOpenHashMap"))
    private static Long2ReferenceOpenHashMap<?> vanadium$synchronizedCallbacks() {
        return new SynchronizedLong2ReferenceOpenHashMap<>();
    }
}
