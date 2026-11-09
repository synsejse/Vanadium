package com.synsenetwork.vanadium.mixin.world;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.synsenetwork.vanadium.tick.SpawnPreparation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @ModifyExpressionValue(method = "createState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getAllEntities()Ljava/lang/Iterable;"))
    private static Iterable<Entity> countSlice(Iterable<Entity> original) {
        return SpawnPreparation.entitiesForCount(original);
    }
}
