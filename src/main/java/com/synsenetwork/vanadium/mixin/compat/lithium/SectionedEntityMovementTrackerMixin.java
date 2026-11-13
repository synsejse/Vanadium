package com.synsenetwork.vanadium.mixin.compat.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.compat.LithiumCompat;
import net.caffeinemc.mods.lithium.common.tracking.entity.SectionedEntityMovementTracker;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Registration changes a tracker's sections and lists, which entity ticks reach through
 * {@link EntitySectionMovementTrackingMixin}; it holds the same lock. The tracker's other methods run from its
 * hoppers' ticks (block-entity stage, where hoppers sharing a tracker are neighbours and never overlap) or
 * from section methods that already hold the lock.
 */
@Mixin(SectionedEntityMovementTracker.class)
public abstract class SectionedEntityMovementTrackerMixin {
    @WrapMethod(method = "register")
    private void vanadium$register(ServerLevel level, Operation<Void> original) {
        synchronized (LithiumCompat.MOVEMENT_TRACKING) {
            original.call(level);
        }
    }

    @WrapMethod(method = "unRegister")
    private void vanadium$unRegister(ServerLevel level, Operation<Void> original) {
        synchronized (LithiumCompat.MOVEMENT_TRACKING) {
            original.call(level);
        }
    }
}
