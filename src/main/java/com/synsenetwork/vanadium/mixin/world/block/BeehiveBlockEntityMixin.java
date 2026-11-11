package com.synsenetwork.vanadium.mixin.world.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Bees enter their hive from their own ticks, several in the same tick. Each entry owns
 * the hive's occupant list, so the capacity check and the insertion stay together.
 */
@Mixin(BeehiveBlockEntity.class)
public abstract class BeehiveBlockEntityMixin {
    @WrapMethod(method = "addOccupant")
    private void vanadium$oneBeeAtATime(Bee bee, Operation<Void> original) {
        synchronized (this) {
            original.call(bee);
        }
    }
}
