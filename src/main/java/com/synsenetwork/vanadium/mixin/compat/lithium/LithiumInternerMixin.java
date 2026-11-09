package com.synsenetwork.vanadium.mixin.compat.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Hoppers in different regions create Lithium's per-world trackers concurrently; creation is rare. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.util.deduplication.LithiumInterner", remap = false)
public abstract class LithiumInternerMixin {
    @WrapMethod(method = "getCanonical", remap = false)
    private Object vanadium$getCanonical(Object value, Operation<Object> original) {
        synchronized (this) {
            return original.call(value);
        }
    }

    @WrapMethod(method = "deleteCanonical", remap = false)
    private void vanadium$deleteCanonical(Object value, Operation<Void> original) {
        synchronized (this) {
            original.call(value);
        }
    }
}
