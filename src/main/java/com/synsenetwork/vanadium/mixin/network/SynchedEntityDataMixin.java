package com.synsenetwork.vanadium.mixin.network;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Synced data (health, flags, pose, variants) is where most changes to an entity end up, so
 * a write to another tick's entity waits for that tick here, whatever vanilla or modded code caused it.
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {
    @Shadow
    @Final
    private SyncedDataHolder entity;

    @WrapMethod(method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V")
    private <T> void vanadium$set(EntityDataAccessor<T> accessor, T value, boolean force, Operation<Void> original) {
        if (Interactions.active && entity instanceof Entity owner && Interactions.enter(owner, Interactions.Kind.DATA)) {
            try {
                original.call(accessor, value, force);
            } finally {
                Interactions.exit();
            }
        } else {
            original.call(accessor, value, force);
        }
    }
}
