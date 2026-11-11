package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Two stacks lying together each try to merge with the other in the same tick. A merge owns both items and
 * rechecks that both can still merge, so neither stack is counted twice or lost.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @WrapMethod(method = "tryToMerge")
    private void vanadium$mergeOnce(ItemEntity other, Operation<Void> original) {
        if (Interactions.active && Interactions.enterPair(other, Interactions.Kind.PAIR)) {
            try {
                if (((ItemEntity) (Object) this).isMergable() && other.isMergable()) original.call(other);
            } finally {
                Interactions.exitPair();
            }
        } else {
            original.call(other);
        }
    }
}
