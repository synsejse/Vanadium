package com.synsenetwork.vanadium.mixin.entity.raid;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;

/** Raiders also take the banner from their banner goal, outside the pickup step that {@code MobMixin} covers. */
@Mixin(Raider.class)
public class RaiderMixin {
    @WrapMethod(method = "pickUpItem")
    private void vanadium$takeBannerOnce(ServerLevel level, ItemEntity item, Operation<Void> original) {
        if (Interactions.active && Interactions.enterPair(item, Interactions.Kind.PAIR)) {
            try {
                if (!item.isRemoved() && !item.getItem().isEmpty()) original.call(level, item);
            } finally {
                Interactions.exitPair();
            }
        } else {
            original.call(level, item);
        }
    }
}
