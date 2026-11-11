package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Flat entity wave: two orbs can pick each other to merge into in the same tick. The merge owns both, and is
 * skipped when either was already merged away, so their experience is never dropped.
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
    @WrapMethod(method = "merge")
    private void vanadium$merge(ExperienceOrb other, Operation<Void> original) {
        if (Interactions.active && Interactions.enter(other, Interactions.Kind.OTHER)) {
            try {
                if (!other.isRemoved() && !((ExperienceOrb) (Object) this).isRemoved()) original.call(other);
            } finally {
                Interactions.exit();
            }
        } else {
            original.call(other);
        }
    }
}
