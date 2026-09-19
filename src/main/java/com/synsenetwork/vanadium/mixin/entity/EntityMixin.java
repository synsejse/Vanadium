package com.synsenetwork.vanadium.mixin.entity;

import com.synsenetwork.vanadium.tracking.NavigationIndex;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "setPosRaw", at = @At("HEAD"))
    private void invalidateNavigationPosition(double x, double y, double z, CallbackInfo ci) {
        NavigationIndex.invalidate((Entity) (Object) this);
    }
}
