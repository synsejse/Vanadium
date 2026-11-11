package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import com.synsenetwork.vanadium.tracking.NavigationState;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Mob.class)
public abstract class MobMixin implements NavigationState {
    @Unique private volatile boolean vanadium$navigationMember;
    @Unique private final AtomicBoolean vanadium$navigationDirty = new AtomicBoolean();

    @Override public boolean vanadium$navigationMember() { return vanadium$navigationMember; }
    @Override public void vanadium$setNavigationMember(boolean member) { vanadium$navigationMember = member; }
    @Override public AtomicBoolean vanadium$navigationDirty() { return vanadium$navigationDirty; }

    /**
     * Several mobs can reach for one item in the same tick while the item merges into another. Whatever
     * {@code pickUpItem} override runs, it owns the mob and the item, and is skipped when the item is
     * already gone or empty.
     */
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;pickUpItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/item/ItemEntity;)V"))
    private void vanadium$takeItemOnce(Mob mob, ServerLevel level, ItemEntity item, Operation<Void> original) {
        if (Interactions.active && Interactions.enterPair(item, Interactions.Kind.PAIR)) {
            try {
                if (!item.isRemoved() && !item.getItem().isEmpty()) original.call(mob, level, item);
            } finally {
                Interactions.exitPair();
            }
        } else {
            original.call(mob, level, item);
        }
    }
}
